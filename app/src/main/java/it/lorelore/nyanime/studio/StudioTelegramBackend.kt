package it.lorelore.nyanime.studio

import android.content.Context
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import it.lorelore.nyanime.source.telegram.TelegramSourceAttachment
import it.lorelore.nyanime.source.telegram.TelegramSourceDocument
import it.lorelore.nyanime.source.telegram.TelegramSourceMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import nyanime.telegram.flag
import nyanime.telegram.number
import nyanime.telegram.objectOrNull
import nyanime.telegram.tdRequest
import nyanime.telegram.text
import nyanime.telegram.type
import java.io.File
import java.security.MessageDigest

/** The only TDLib-facing implementation for source reads. Never supplied to extension code. */
internal class StudioTelegramBackend(context: Context) : TelegramChannelPagedBackend {
    private val app = context.applicationContext
    private val client
        get() = TelegramClient.get(app)

    private suspend fun <T> connected(operation: suspend () -> T): T {
        val lease = client.retain()
        return try {
            client.connect()
            val state =
                withTimeout(15_000) {
                    client.state.first {
                        it.ready || it.authorization !in setOf("connecting", "disconnected")
                    }
                }
            check(state.ready) { "Connect your Telegram account first" }
            operation()
        } finally {
            client.release(lease)
        }
    }

    override suspend fun channel(username: String): TelegramPublicChannelState = connected {
        val name = TelegramPublicChannel.name(username)
        val chat = client.request(tdRequest("searchPublicChat", "username" to JsonPrimitive(name)))
        val type = chat.objectOrNull("type") ?: error("Channel metadata is unavailable")
        require(type.type() == "chatTypeSupergroup") { "Choose a public broadcast channel" }
        val group =
            client.request(
                tdRequest(
                    "getSupergroup",
                    "supergroup_id" to JsonPrimitive(type.number("supergroup_id")),
                ),
            )
        val names = group.objectOrNull("usernames")
        val active =
            (names?.get("active_usernames") as? JsonArray).orEmpty().map {
                it.studioText().lowercase()
            }
        val publicName = name in active || group.text("username").equals(name, true)
        val status = group.objectOrNull("status")
        val member =
            status?.type() in
                setOf(
                    "chatMemberStatusCreator",
                    "chatMemberStatusAdministrator",
                    "chatMemberStatusMember",
                ) ||
                (status?.type() == "chatMemberStatusRestricted" && status.flag("is_member"))
        TelegramPublicChannelState(
            chat.number("id"),
            name,
            chat.text("title").take(2048),
            group.flag("is_channel") && publicName,
            member,
        )
    }

    override suspend fun join(channel: Long) = connected {
        client.request(tdRequest("joinChat", "chat_id" to JsonPrimitive(channel)))
        Unit
    }

    override suspend fun mute(channel: Long) = connected {
        val chat = client.request(tdRequest("getChat", "chat_id" to JsonPrimitive(channel)))
        val previous = requireNotNull(chat.objectOrNull("notification_settings"))
        // Keep custom sounds/previews and all other settings unchanged.
        val settings =
            JsonObject(
                previous +
                    mapOf(
                        "use_default_mute_for" to JsonPrimitive(false),
                        "mute_for" to JsonPrimitive(Int.MAX_VALUE),
                    ),
            )
        client.request(
            tdRequest(
                "setChatNotificationSettings",
                "chat_id" to JsonPrimitive(channel),
                "notification_settings" to settings,
            ),
        )
        Unit
    }

    override suspend fun leave(channel: Long) = connected {
        client.request(tdRequest("leaveChat", "chat_id" to JsonPrimitive(channel)))
        Unit
    }

    override suspend fun history(
        channel: Long,
        before: Long,
        limit: Int,
    ): List<TelegramSourceMessage> = historyPage(channel, before, limit).messages

    override suspend fun historyPage(channel: Long, before: Long, limit: Int): TelegramChannelHistoryPage = connected {
        require(limit in 1..100 && before >= 0)
        val answer =
            client.request(
                tdRequest(
                    "searchChatMessages",
                    "chat_id" to JsonPrimitive(channel),
                    "from_message_id" to JsonPrimitive(before),
                    "offset" to JsonPrimitive(0),
                    "limit" to JsonPrimitive(limit),
                    "query" to JsonPrimitive(""),
                    "filter" to JsonNull,
                    "sender_id" to JsonNull,
                    "topic_id" to JsonNull,
                ),
            )
        val messages = requireNotNull(answer["messages"] as? JsonArray).map { raw ->
            require(raw is JsonObject && raw.number("chat_id") == channel)
            convert(raw)
        }
        val next = requireNotNull(answer["next_from_message_id"]?.jsonPrimitive?.longOrNull)
        require(next >= 0)
        TelegramChannelHistoryPage(messages, next.takeIf { it > 0 })
    }

    override suspend fun message(channel: Long, message: Long): TelegramSourceMessage = connected {
        convert(rawMessage(channel, message))
    }

    private suspend fun rawMessage(channel: Long, message: Long): JsonObject =
        client
            .request(
                tdRequest(
                    "getMessage",
                    "chat_id" to JsonPrimitive(channel),
                    "message_id" to JsonPrimitive(message),
                ),
            )
            .also {
                require(it.number("chat_id") == channel && it.number("id") == message) {
                    "Message identity changed"
                }
            }

    override suspend fun document(
        channel: Long,
        message: Long,
        reference: String,
    ): TelegramSourceDocument = connected {
        val raw = rawMessage(channel, message)
        val attachment =
            requireNotNull(convert(raw).document) { "This message no longer contains a document" }
        require(attachment.reference == reference) { "The attachment has changed" }
        val size = requireNotNull(attachment.size)
        require(size in 0..TelegramSourceDocument.MAX_DOCUMENT_BYTES.toLong()) {
            "Text document is too large"
        }
        val file =
            requireNotNull(
                raw.objectOrNull("content")?.objectOrNull("document")?.objectOrNull("document"),
            )
        val fileId = file.number("id")
        require(fileId > 0)
        val ready =
            withTimeout(60_000) {
                var current =
                    client.request(
                        tdRequest(
                            "downloadFile",
                            "file_id" to JsonPrimitive(fileId),
                            "priority" to JsonPrimitive(1),
                            "offset" to JsonPrimitive(0),
                            "limit" to JsonPrimitive(size),
                            "synchronous" to JsonPrimitive(false),
                        ),
                    )
                while (current.objectOrNull("local")?.flag("is_downloading_completed") != true) {
                    delay(300)
                    current =
                        client.request(tdRequest("getFile", "file_id" to JsonPrimitive(fileId)))
                    require(current.number("size") <= TelegramSourceDocument.MAX_DOCUMENT_BYTES)
                }
                current
            }
        // Do not cancel a shared TDLib transfer when a source stops reading it.
        val bytes =
            withContext(Dispatchers.IO) {
                val root = File(app.noBackupFilesDir, "telegram/files").canonicalFile
                val downloaded =
                    File(requireNotNull(ready.objectOrNull("local")).text("path")).canonicalFile
                require(
                    downloaded.toPath().startsWith(root.toPath()) &&
                        downloaded.isFile &&
                        downloaded.length() == size,
                ) {
                    "Unexpected or incomplete attachment storage"
                }
                downloaded.inputStream().use { stream ->
                    SourceBytes.read(stream, TelegramSourceDocument.MAX_DOCUMENT_BYTES)
                        .also { require(it.size.toLong() == size) }
                }
            }
        // A post edited during the download cannot silently supply different bytes.
        require(convert(rawMessage(channel, message)).document?.reference == reference) {
            "The attachment has changed"
        }
        TelegramSourceDocument(reference, attachment.name, attachment.mimeType, bytes)
    }

    private fun convert(raw: JsonObject): TelegramSourceMessage {
        val chat = raw.number("chat_id")
        val id = raw.number("id")
        val content = requireNotNull(raw.objectOrNull("content"))
        val formatted = content.objectOrNull("text") ?: content.objectOrNull("caption")
        val body = formatted?.text("text").orEmpty().take(65_536)
        val links =
            (formatted?.get("entities") as? JsonArray)
                .orEmpty()
                .mapNotNull { element ->
                    val entity = element as? JsonObject ?: return@mapNotNull null
                    val type = entity.objectOrNull("type") ?: return@mapNotNull null
                    when (type.type()) {
                        "textEntityTypeTextUrl" -> type.text("url")
                        "textEntityTypeUrl" -> {
                            val start = entity.number("offset").toInt()
                            val end = start.toLong() + entity.number("length")
                            if (start >= 0 && end in start.toLong()..body.length.toLong()) {
                                body.substring(start, end.toInt())
                            } else {
                                null
                            }
                        }
                        else -> null
                    }?.takeIf { it.length in 1..8192 }
                }
                .distinct()
                .take(100)
        val document =
            content
                .objectOrNull("document")
                ?.takeIf { content.type() == "messageDocument" }
                ?.let { data ->
                    val file = requireNotNull(data.objectOrNull("document"))
                    val identity = file.objectOrNull("remote")?.text("unique_id").orEmpty()
                    require(identity.isNotBlank()) { "Attachment identity is unavailable" }
                    val fingerprint =
                        MessageDigest.getInstance("SHA-256")
                            .digest(
                                buildJsonObject {
                                    put("id", identity)
                                    put("size", file.number("size"))
                                    put("name", data.text("file_name"))
                                    put("mime", data.text("mime_type"))
                                }
                                    .toString()
                                    .toByteArray(),
                            )
                            .joinToString("") { "%02x".format(it) }
                    TelegramSourceAttachment(
                        "document:$chat:$id:$fingerprint",
                        data.text("file_name"),
                        data.text("mime_type"),
                        file.number("size"),
                    )
                }
        return TelegramSourceMessage(
            chat,
            id,
            body,
            raw.number("date").takeIf { it > 0 }?.times(1000),
            raw.number("edit_date").takeIf { it > 0 }?.times(1000),
            links,
            document,
        )
    }
}
