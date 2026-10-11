package nyanime.telegram

import kotlinx.serialization.json.JsonObject

/** The isolated engine forwards only explicitly subscribed conversations. */
object TelegramUpdatePolicy {
    private val types = setOf(
        "updateNewMessage",
        "updateMessageContent",
        "updateMessageEdited",
        "updateMessageInteractionInfo",
        "updateDeleteMessages",
        "updateChatReadInbox",
        "updateChatReadOutbox",
        "updateChatPendingJoinRequests",
    )
    fun accepts(update: JsonObject, registeredChats: Set<Long>): Boolean {
        if (update.type() !in types) return false
        val chat = update.objectOrNull("message")?.number("chat_id") ?: update.number("chat_id")
        return chat != 0L && chat in registeredChats
    }
}
