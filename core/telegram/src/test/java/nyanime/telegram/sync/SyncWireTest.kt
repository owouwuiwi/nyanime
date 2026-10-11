package nyanime.telegram.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.tdRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncWireTest {
    @Test fun ignoresForwardedMessagesAndUnconfirmedUploadsEvenFromTheSameAccount() {
        val message = tdRequest(
            "message",
            "id" to JsonPrimitive(11),
            "chat_id" to JsonPrimitive(-20),
            "sender_id" to tdRequest("messageSenderUser", "user_id" to JsonPrimitive(10)),
        )
        assertTrue(SyncWire.ownedMessage(message, 10, -20))
        assertFalse(SyncWire.ownedMessage(message, 12, -20))
        assertFalse(SyncWire.ownedMessage(message, 10, -21))
        assertFalse(
            SyncWire.ownedMessage(JsonObject(message + ("forward_info" to tdRequest("messageForwardInfo"))), 10, -20),
        )
        assertFalse(
            SyncWire.ownedMessage(
                JsonObject(message + ("sending_state" to tdRequest("messageSendingStatePending"))),
                10,
                -20,
            ),
        )
    }

    @Test fun requiresAPrivateSoleOwnerChannelWithTheExactProtocolMarker() {
        val group = tdRequest(
            "supergroup",
            "is_channel" to JsonPrimitive(true),
            "status" to tdRequest("chatMemberStatusCreator"),
        )
        val info = tdRequest(
            "supergroupFullInfo",
            "member_count" to JsonPrimitive(1),
            "linked_chat_id" to JsonPrimitive(0),
            "description" to JsonPrimitive(SyncEnvelope.ARCHIVE_MARKER),
        )
        assertTrue(SyncWire.privateArchive(group, info))
        assertFalse(SyncWire.privateArchive(group, JsonObject(info + ("member_count" to JsonPrimitive(2)))))
        assertFalse(SyncWire.privateArchive(JsonObject(group + ("is_channel" to JsonPrimitive(false))), info))
    }
}
