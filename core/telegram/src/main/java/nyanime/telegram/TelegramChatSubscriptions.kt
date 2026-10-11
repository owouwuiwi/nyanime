package nyanime.telegram

/**
 * Independent consumers share the TDLib connection. Closing the room panel must not stop Cloud
 * updates, and disabling Cloud must not unsubscribe an active conversation.
 */
class TelegramChatSubscriptions {
    private val owners = mutableMapOf<String, Set<Long>>()

    @Synchronized
    fun update(owner: String, chats: Set<Long>): Set<Long> {
        require(owner.length in 1..80 && chats.all { it < 0 })
        val next = owners.toMutableMap()
        if (chats.isEmpty()) next.remove(owner) else next[owner] = chats.toSet()
        val union = next.values.flatten().toSet()
        require(union.size <= 2048)
        owners.clear()
        owners.putAll(next)
        return union
    }

    @Synchronized
    fun snapshot(): Set<Long> = owners.values.flatten().toSet()
}
