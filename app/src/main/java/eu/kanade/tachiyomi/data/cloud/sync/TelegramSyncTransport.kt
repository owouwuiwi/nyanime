package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import eu.kanade.tachiyomi.data.rin.RinSessions
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import nyanime.telegram.flag
import nyanime.telegram.number
import nyanime.telegram.objectOrNull
import nyanime.telegram.sync.SyncArchiveDescriptor
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncEnvelope
import nyanime.telegram.sync.SyncHistoryPage
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncReceipt
import nyanime.telegram.sync.SyncTransport
import nyanime.telegram.sync.SyncWire
import nyanime.telegram.sync.syncJson
import nyanime.telegram.tdRequest
import nyanime.telegram.text
import nyanime.telegram.type
import nyanime.telegram.withTelegramTimeout
import java.io.File
import java.util.UUID

internal class CloudSyncTransferDeferred : IllegalStateException("Bulk synchronization waits for idle playback")

/** Reuses the existing account; it neither owns nor closes the shared TDLib session. */
internal class TelegramSyncTransport(
    private val context: Context,
    private val client: TelegramClient,
    private val account: Long,
    private val chat: Long,
    private val space: String,
) : SyncTransport {
    var beforeSend: suspend () -> Unit = {}
    override suspend fun verifyArchive() {
        check(client.state.value.ready && client.state.value.account?.id == account)
        val value = client.request(tdRequest("getChat", "chat_id" to JsonPrimitive(chat)))
        val type = value.objectOrNull("type")
        if (type?.type() != "chatTypeSupergroup" || !type.flag("is_channel")) unsafe()
        val id = JsonPrimitive(type.number("supergroup_id"))
        val group = client.request(tdRequest("getSupergroup", "supergroup_id" to id))
        val info = client.request(tdRequest("getSupergroupFullInfo", "supergroup_id" to id))
        if (group.objectOrNull("status")?.type() in setOf("chatMemberStatusBanned", "chatMemberStatusLeft")) {
            throw TelegramException(TelegramFailure.ARCHIVE_MISSING)
        }
        if (!SyncWire.privateArchive(group, info)) unsafe()
        val descriptor = SyncArchiveDescriptor.parse(info.text("description"))
        if (descriptor != null && descriptor.space != space) unsafe()
    }

    override suspend fun send(packet: SyncPacket, onQueued: (Long) -> Unit): SyncReceipt = withContext(
        Dispatchers.IO,
    ) {
        require(packet.space == space)
        packet.validate()
        beforeSend()
        verifyArchive()
        val payload = syncJson.encodeToString(packet)
        deferBulk(payload.toByteArray(Charsets.UTF_8).size.toLong())
        val content = if (payload.length + SyncEnvelope.MARKER.length <= 3600) {
            tdRequest(
                "inputMessageText",
                "text" to formatted(SyncEnvelope.MARKER + payload),
                "clear_draft" to JsonPrimitive(false),
            )
        } else {
            val file = outbox(packet.id)
            // Bytes are already encrypted; retrying uses the same persisted envelope identity.
            file.writeText(payload, Charsets.UTF_8)
            tdRequest(
                "inputMessageDocument",
                "document" to tdRequest(
                    "inputDocument",
                    "document" to tdRequest("inputFileLocal", "path" to JsonPrimitive(file.absolutePath)),
                    "disable_content_type_detection" to JsonPrimitive(true),
                ),
                "caption" to formatted(SyncEnvelope.MARKER + packet.id + "|" + packet.kind),
            )
        }
        beforeSend()
        val queued = client.request(
            tdRequest(
                "sendMessage",
                "chat_id" to JsonPrimitive(chat),
                "options" to tdRequest(
                    "messageSendOptions",
                    "disable_notification" to JsonPrimitive(true),
                    "protect_content" to JsonPrimitive(true),
                ),
                "input_message_content" to content,
            ),
        )
        val temporary = queued.number("id")
        require(temporary != 0L)
        onQueued(temporary)
        val receipt = awaitReceipt(queued)
        outbox(packet.id).delete()
        receipt
    }

    private suspend fun awaitReceipt(queued: JsonObject): SyncReceipt = withTelegramTimeout(60_000) {
        var message = queued
        val temporary = queued.number("id")
        while (true) {
            val failure = message.objectOrNull("sending_state")
            if (failure?.type() == "messageSendingStateFailed") {
                throw failure.objectOrNull("error")?.let(nyanime.telegram.TelegramErrors::parse)
                    ?: TelegramException(TelegramFailure.REQUEST)
            }
            if (message["sending_state"] !is JsonObject && message.number("id") > 0 && message.number("date") > 0) {
                return@withTelegramTimeout SyncReceipt(message.number("id"), message.number("date"))
            }
            delay(250)
            client.receipts.value[temporary]?.let { result ->
                if (result.objectOrNull("message")?.number("chat_id") != chat) return@let
                if (result.type() == "updateMessageSendFailed") {
                    throw result.objectOrNull("error")?.let(nyanime.telegram.TelegramErrors::parse)
                        ?: TelegramException(TelegramFailure.REQUEST)
                }
                result.objectOrNull("message")?.let { message = it }
            }
        }
        error("Unreachable")
    }

    /** Reuse TDLib's pending upload after a timeout/restart instead of silently queuing it twice. */
    suspend fun resume(packet: SyncPacket, temporary: Long, onQueued: (Long) -> Unit): SyncReceipt? {
        if (temporary == 0L) return null
        beforeSend()
        verifyArchive()
        var message = client.receipts.value[temporary]?.objectOrNull("message")
            ?.takeIf { it.number("chat_id") == chat }
            ?: try {
                client.request(
                    tdRequest(
                        "getMessage",
                        "chat_id" to JsonPrimitive(chat),
                        "message_id" to JsonPrimitive(temporary),
                    ),
                )
            } catch (error: TelegramException) {
                if (error.code == 404) return null else throw error
            }
        require(message.number("chat_id") == chat)
        val content = message.objectOrNull("content")
        val text = content?.objectOrNull("text")?.text("text").orEmpty()
        val caption = content?.objectOrNull("caption")?.text("text").orEmpty()
        require(
            text == SyncEnvelope.MARKER + syncJson.encodeToString(packet) ||
                caption == SyncEnvelope.MARKER + packet.id + "|" + packet.kind,
        )
        val failed = message.objectOrNull("sending_state")
        if (failed?.type() == "messageSendingStateFailed") {
            check(failed.flag("can_retry") && failed.number("required_paid_message_star_count") == 0L)
            val delay = failed.number("retry_after")
            if (delay > 0) throw TelegramException(TelegramFailure.RATE_LIMIT, retryAfterSeconds = delay)
            val result = client.request(
                tdRequest(
                    "resendMessages",
                    "chat_id" to JsonPrimitive(chat),
                    "message_ids" to JsonArray(listOf(JsonPrimitive(message.number("id")))),
                    "paid_message_star_count" to JsonPrimitive(0),
                ),
            )
            message = (result["messages"] as? JsonArray)?.filterIsInstance<JsonObject>()?.singleOrNull()
                ?: throw TelegramException(TelegramFailure.REQUEST)
            onQueued(message.number("id"))
        }
        return awaitReceipt(message).also { outbox(packet.id).delete() }
    }

    override suspend fun page(
        before: Long,
        after: Long,
        kinds: Set<String>?,
        receive: suspend (SyncDelivery) -> Unit,
    ): SyncHistoryPage =
        withContext(Dispatchers.IO) {
            check(client.state.value.account?.id == account)
            val result = client.request(
                tdRequest(
                    "getChatHistory",
                    "chat_id" to JsonPrimitive(chat),
                    "from_message_id" to JsonPrimitive(before),
                    "offset" to JsonPrimitive(0),
                    "limit" to JsonPrimitive(100),
                    "only_local" to JsonPrimitive(false),
                ),
            )
            val messages = (result["messages"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            messages.forEach { message ->
                if (message.number("id") <= after) return@forEach
                if (!SyncWire.ownedMessage(message, account, chat)) return@forEach
                val caption = message.objectOrNull("content")?.objectOrNull("caption")?.text("text").orEmpty()
                if (kinds != null &&
                    caption.startsWith(SyncEnvelope.MARKER) &&
                    caption.substringAfter('|', "") !in kinds
                ) {
                    return@forEach
                }
                val payload = SyncWire.text(message) ?: document(message) ?: return@forEach
                // A recognized but unsupported/corrupt payload is an error, not a skipped cursor.
                val packet = syncJson.decodeFromString<SyncPacket>(payload)
                packet.validate()
                require(packet.space == space)
                if (kinds != null && packet.kind !in kinds) return@forEach
                // Persist each bounded delivery before downloading the next document.
                receive(SyncDelivery(packet, SyncReceipt(message.number("id"), message.number("date"))))
            }
            val next = messages.minOfOrNull { it.number("id") } ?: 0L
            SyncHistoryPage(
                next,
                messages.maxOfOrNull { it.number("id") } ?: 0L,
                messages.isEmpty() || next == before || next <= after,
            )
        }

    private suspend fun document(message: JsonObject): String? {
        val content = message.objectOrNull("content") ?: return null
        if (content.type() != "messageDocument") return null
        val caption = content.objectOrNull("caption")?.text("text").orEmpty()
        if (!caption.startsWith(SyncEnvelope.MARKER)) return null
        val descriptor = caption.removePrefix(SyncEnvelope.MARKER).split('|')
        require(descriptor.size == 2)
        val (id, kind) = descriptor
        require(UUID.fromString(id).toString() == id)
        val document = content.objectOrNull("document") ?: error("Missing synchronization document")
        require(document.text("file_name") == "$id.nysync")
        val file = document.objectOrNull("document") ?: error("Missing synchronization file")
        require(file.number("size") in 1..SyncEnvelope.MAX_ENCODED_SIZE.toLong())
        deferBulk(file.number("size"))
        var current = client.request(
            tdRequest(
                "downloadFile",
                "file_id" to JsonPrimitive(file.number("id")),
                "priority" to JsonPrimitive(1),
                "synchronous" to JsonPrimitive(false),
            ),
        )
        withTelegramTimeout(60_000) {
            while (current.objectOrNull("local")?.flag("is_downloading_completed") != true) {
                delay(300)
                current = client.request(tdRequest("getFile", "file_id" to JsonPrimitive(file.number("id"))))
            }
        }
        val downloaded = File(current.objectOrNull("local")!!.text("path")).canonicalFile
        val root = File(context.noBackupFilesDir, "telegram").canonicalPath + File.separator
        require(downloaded.path.startsWith(root) && downloaded.length() == file.number("size"))
        return downloaded.readText(Charsets.UTF_8).also {
            val packet = syncJson.decodeFromString<SyncPacket>(it)
            require(packet.id == id && packet.kind == kind)
        }
    }

    suspend fun message(id: Long): SyncDelivery {
        require(id > 0)
        val message = client.request(
            tdRequest("getMessage", "chat_id" to JsonPrimitive(chat), "message_id" to JsonPrimitive(id)),
        )
        require(SyncWire.ownedMessage(message, account, chat))
        val payload = SyncWire.text(message) ?: document(message) ?: error("Synchronization packet is missing")
        val packet = syncJson.decodeFromString<SyncPacket>(payload)
        packet.validate()
        require(packet.space == space && message.number("id") == id)
        return SyncDelivery(packet, SyncReceipt(id, message.number("date")))
    }

    private fun outbox(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(context.noBackupFilesDir, "cloud-sync-outbox/$id.nysync").apply { parentFile!!.mkdirs() }
    }

    private fun formatted(text: String) = tdRequest("formattedText", "text" to JsonPrimitive(text))
    private fun deferBulk(bytes: Long) {
        if (bytes > 64 * 1024 && RinSessions.busy.value) throw CloudSyncTransferDeferred()
    }
    private fun unsafe(): Nothing = throw TelegramException(TelegramFailure.ARCHIVE_UNSAFE)
}
