package nyanime.telegram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CloudWireTest {
    private val manifest =
        CloudManifest(
            backupId = UUID.randomUUID().toString(),
            deviceId = UUID.randomUUID().toString(),
            deviceName = "Device",
            sha256 = "b".repeat(64),
            bytes = 1024,
            appVersion = "1.0.0.0",
            createdAt = 1000,
        )
    private fun document(sending: Boolean = false) = buildJsonObject {
        put("@type", "message")
        put("chat_id", 20)
        put("id", 100)
        put("date", 1000)
        put("sender_id", tdRequest("messageSenderUser", "user_id" to JsonPrimitive(10)))
        if (sending) put("sending_state", tdRequest("messageSendingStatePending"))
        put(
            "content",
            tdRequest(
                "messageDocument",
                "caption" to tdRequest("formattedText", "text" to JsonPrimitive(manifest.caption())),
                "document" to
                    buildJsonObject {
                        put("file_name", "backup.nyabk")
                        put(
                            "document",
                            buildJsonObject {
                                put("id", 3)
                                put("size", 1024)
                            },
                        )
                    },
            ),
        )
    }

    @Test fun queuedMessageIsNotAConfirmedBackup() {
        assertNotNull(CloudWire.manifest(document(true), 10, 20))
        assertNull(CloudWire.copy(document(true), 10, 20))
        assertNotNull(CloudWire.copy(document(), 10, 20))
    }

    @Test fun unrelatedUserOrChannelCannotSupplyBackup() {
        assertNull(CloudWire.copy(document(), 11, 20))
        assertNull(CloudWire.copy(document(), 10, 21))
    }

    @Test fun soleOwnerChannelPostsAreRecognizedButForeignChannelsAreNot() {
        val own =
            JsonObject(document() + ("sender_id" to tdRequest("messageSenderChat", "chat_id" to JsonPrimitive(20))))
        val foreign =
            JsonObject(document() + ("sender_id" to tdRequest("messageSenderChat", "chat_id" to JsonPrimitive(21))))
        assertNotNull(CloudWire.copy(own, 10, 20))
        assertNull(CloudWire.copy(foreign, 10, 20))
    }

    @Test fun sharedOrUnmarkedArchiveCannotReceivePrivateData() {
        val group = buildJsonObject { put("status", tdRequest("chatMemberStatusCreator")) }
        fun info(
            members: Int,
            marker: String,
        ) = buildJsonObject {
            put("member_count", members)
            put("description", marker)
        }
        assertTrue(CloudWire.privateArchive(group, info(1, CloudManifest.ARCHIVE_MARKER)))
        assertFalse(CloudWire.privateArchive(group, info(2, CloudManifest.ARCHIVE_MARKER)))
        assertFalse(CloudWire.privateArchive(group, info(1, "A personal channel")))
        val linked = JsonObject(info(1, CloudManifest.ARCHIVE_MARKER) + ("linked_chat_id" to JsonPrimitive(99)))
        val public = JsonObject(
            group +
                (
                    "usernames" to buildJsonObject {
                        put("active_usernames", JsonArray(listOf(JsonPrimitive("public-archive"))))
                    }
                    ),
        )
        assertFalse(CloudWire.privateArchive(group, linked))
        assertFalse(CloudWire.privateArchive(public, info(1, CloudManifest.ARCHIVE_MARKER)))
    }
}
