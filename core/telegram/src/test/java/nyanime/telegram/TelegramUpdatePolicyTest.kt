package nyanime.telegram

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramUpdatePolicyTest {
    @Test
    fun `call events cannot pass through a Cloud subscription`() {
        val registered = setOf(-10L)
        for (type in listOf("updateGroupCall", "updateGroupCallParticipant")) {
            assertFalse(
                TelegramUpdatePolicy.accepts(
                    tdRequest(type, "group_call_id" to JsonPrimitive(1), "chat_id" to JsonPrimitive(-10)),
                    registered,
                ),
            )
        }
    }

    @Test
    fun `unrelated personal Telegram messages never reach subscribed features`() {
        val registered = setOf(-10L)
        fun message(chat: Long) = tdRequest(
            "updateNewMessage",
            "message" to tdRequest("message", "chat_id" to JsonPrimitive(chat)),
        )
        assertTrue(TelegramUpdatePolicy.accepts(message(-10), registered))
        assertFalse(TelegramUpdatePolicy.accepts(message(99), registered))
        assertFalse(TelegramUpdatePolicy.accepts(message(-10), emptySet()))
        assertTrue(
            TelegramUpdatePolicy.accepts(
                tdRequest(
                    "updateChatPendingJoinRequests",
                    "chat_id" to JsonPrimitive(-10),
                ),
                registered,
            ),
        )
    }
}
