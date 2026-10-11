package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.TelegramAccount
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import nyanime.telegram.TelegramState
import nyanime.telegram.objectOrNull
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncEnvelope
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.syncJson
import nyanime.telegram.tdRequest
import nyanime.telegram.type
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class CloudSyncTransportTest {
    @TempDir lateinit var root: File
    private val id = "b41fca19-82ab-46c4-9cbf-12515553be49"
    private val space = "debc23ab-1945-4c51-bc99-ce7d85292bbc"

    @Test
    fun anExpiredCachedFailureCannotRenewTheServerWait() = runTest {
        val (client, transport, packet) = retryFixture(0.0)
        val receipt = transport.resume(packet, -1) { assertEquals(100L, it) }
        assertEquals(100L, receipt!!.messageId)
        coVerify(exactly = 1) { client.request(match { it.type() == "getMessage" }) }
        coVerify(exactly = 1) { client.request(match { it.type() == "resendMessages" }) }
    }

    @Test
    fun theCurrentFractionalServerWaitIsRoundedUpWithoutResending() = runTest {
        val (client, transport, packet) = retryFixture(0.25)
        try {
            transport.resume(packet, -1) {}
            error("A current server wait must be respected")
        } catch (error: TelegramException) {
            assertEquals(TelegramFailure.RATE_LIMIT, error.failure)
            assertEquals(1L, error.retryAfterSeconds)
        }
        coVerify(exactly = 1) { client.request(match { it.type() == "getMessage" }) }
        coVerify(exactly = 0) { client.request(match { it.type() == "resendMessages" }) }
    }

    private fun retryFixture(currentWait: Double): Triple<TelegramClient, TelegramSyncTransport, SyncPacket> {
        val context = mockk<Context>()
        val client = mockk<TelegramClient>()
        every { client.state } returns MutableStateFlow(
            TelegramState("authorizationStateReady", "connectionStateReady", TelegramAccount(1, "Owner", "")),
        )
        val packet = SyncPacket(kind = "delta", id = id, space = space, payload = "encrypted")
        fun message(wait: Double?, messageId: Long = -1) = tdRequest(
            "message",
            "id" to JsonPrimitive(messageId),
            "date" to JsonPrimitive(1),
            "chat_id" to JsonPrimitive(-100),
            "content" to tdRequest(
                "messageText",
                "text" to
                    tdRequest(
                        "formattedText",
                        "text" to JsonPrimitive(SyncEnvelope.MARKER + syncJson.encodeToString(packet)),
                    ),
            ),
        ).let { value ->
            if (wait == null) {
                value
            } else {
                JsonObject(
                    value +
                        (
                            "sending_state" to tdRequest(
                                "messageSendingStateFailed",
                                "can_retry" to JsonPrimitive(true),
                                "retry_after" to JsonPrimitive(wait),
                            )
                            ),
                )
            }
        }
        every { client.receipts } returns MutableStateFlow(
            mapOf(-1L to tdRequest("updateMessageSendFailed", "message" to message(120.0))),
        )
        coEvery { client.request(any()) } coAnswers {
            when (firstArg<JsonObject>().type()) {
                "getChat" -> tdRequest(
                    "chat",
                    "type" to
                        tdRequest(
                            "chatTypeSupergroup",
                            "is_channel" to JsonPrimitive(true),
                            "supergroup_id" to JsonPrimitive(1),
                        ),
                )
                "getSupergroup" -> tdRequest(
                    "supergroup",
                    "is_channel" to JsonPrimitive(true),
                    "status" to tdRequest("chatMemberStatusCreator"),
                )
                "getSupergroupFullInfo" -> tdRequest(
                    "supergroupFullInfo",
                    "member_count" to JsonPrimitive(1),
                    "description" to JsonPrimitive(SyncEnvelope.ARCHIVE_MARKER),
                )
                "getMessage" -> message(currentWait)
                "resendMessages" -> tdRequest("messages", "messages" to JsonArray(listOf(message(null, 100))))
                else -> error("Unexpected retry transport method")
            }
        }
        return Triple(client, TelegramSyncTransport(context, client, 1, -100, space), packet)
    }

    @Test
    fun interruptedDocumentDeliveryKeepsOriginalUntilHistoryIsPersisted() = runTest {
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns root
        val client = mockk<TelegramClient>()
        every { client.state } returns MutableStateFlow(
            TelegramState("authorizationStateReady", "connectionStateReady", TelegramAccount(1, "Owner", "")),
        )
        every { client.receipts } returns MutableStateFlow(emptyMap<Long, JsonObject>())
        val packet = SyncPacket(kind = "checkpoint", id = id, space = space, payload = "encrypted".repeat(600))
        val original = File(root, "cloud-sync-outbox/$id.nysync")
        var originalBytes = 0L
        fun nativeFile() = tdRequest(
            "file",
            "id" to JsonPrimitive(9),
            "size" to JsonPrimitive(originalBytes.takeIf { it > 0 } ?: original.length()),
            "local" to tdRequest(
                "localFile",
                "path" to JsonPrimitive(original.path),
                "is_downloading_completed" to JsonPrimitive(true),
            ),
        )
        fun message() = tdRequest(
            "message",
            "id" to JsonPrimitive(100),
            "date" to JsonPrimitive(1),
            "chat_id" to JsonPrimitive(-100),
            "sender_id" to tdRequest("messageSenderUser", "user_id" to JsonPrimitive(1)),
            "content" to tdRequest(
                "messageDocument",
                "caption" to tdRequest(
                    "formattedText",
                    "text" to JsonPrimitive(SyncEnvelope.MARKER + id + "|checkpoint"),
                ),
                "document" to tdRequest(
                    "document",
                    "file_name" to JsonPrimitive("$id.nysync"),
                    "document" to nativeFile(),
                ),
            ),
        )
        coEvery { client.request(any()) } coAnswers {
            val request = firstArg<JsonObject>()
            when (request.type()) {
                "getChat" -> tdRequest(
                    "chat",
                    "type" to tdRequest(
                        "chatTypeSupergroup",
                        "is_channel" to JsonPrimitive(true),
                        "supergroup_id" to JsonPrimitive(1),
                    ),
                )
                "getSupergroup" -> tdRequest(
                    "supergroup",
                    "is_channel" to JsonPrimitive(true),
                    "status" to tdRequest("chatMemberStatusCreator"),
                )
                "getSupergroupFullInfo" -> tdRequest(
                    "supergroupFullInfo",
                    "member_count" to JsonPrimitive(1),
                    "description" to JsonPrimitive(SyncEnvelope.ARCHIVE_MARKER),
                )
                "sendMessage" -> {
                    val content = request.objectOrNull("input_message_content")!!
                    assertEquals("inputMessageDocument", content.type())
                    assertTrue(original.isFile)
                    originalBytes = original.length()
                    assertEquals(packet, syncJson.decodeFromString<SyncPacket>(original.readText()))
                    message()
                }
                "getMessage" -> message()
                "getChatHistory" -> tdRequest("messages", "messages" to JsonArray(listOf(message())))
                "downloadFile" -> nativeFile()
                else -> error("Unexpected transport method: " + request.type())
            }
        }
        var retained: SyncDelivery? = null
        val transport =
            TelegramSyncTransport(context, client, 1, -100, space) {
                retained?.takeIf { item ->
                    item.receipt.messageId ==
                        it
                }
            }
        val receipt = transport.send(packet) { assertEquals(100L, it) }
        assertEquals(100L, receipt.messageId)
        assertTrue(original.isFile, "A server receipt does not mean the history has been staged locally")

        try {
            transport.page(0, 0, null) {
                assertEquals(packet, it.packet)
                throw IOException("Interrupted local staging")
            }
            error("Staging failure must propagate")
        } catch (_: IOException) {
            assertTrue(original.isFile, "An interrupted pull must keep its only local original")
        }

        var staged = false
        transport.page(0, 0, null) {
            assertTrue(original.isFile)
            assertEquals(packet, it.packet)
            retained = it
            staged = true
        }
        assertTrue(staged)
        assertFalse(original.exists(), "Cleanup is permitted only after the durable receive callback succeeds")

        // Another document on that page may have failed after this one was staged.
        transport.page(0, 0, null) { assertEquals(retained, it) }
        assertEquals(retained, transport.message(100))
        retained = retained!!.copy(receipt = retained!!.receipt.copy(serverDate = 2))
        try {
            transport.message(100)
            error("A changed server receipt must not be replaced with cached content")
        } catch (_: IllegalArgumentException) {
            // A mismatched cache must fail rather than silently advancing the server cursor.
        }
        coVerify(exactly = 2) { client.request(match { it.type() == "downloadFile" }) }

        // Discovery/recovery can inspect a history page without staging its payload.
        val discovery = TelegramSyncTransport(context, client, 1, -100, space)
        discovery.send(packet) {}
        discovery.page(0, 0, null) { assertEquals(packet, it.packet) }
        assertTrue(original.isFile, "An in-memory discovery callback does not permit cleanup")
    }
}
