package eu.kanade.tachiyomi.data.watch

import kotlinx.serialization.Serializable

/** Auxiliary room features negotiate independently of video, reading and live playback. */
data class WatchInteractionContext(
    val active: Boolean = false,
    val roomId: String = "",
    val localId: String = "",
    val ownerId: String = "",
    val members: Map<String, String> = emptyMap(),
    val connected: Boolean = false,
    val epoch: String = "",
) {
    val host: Boolean get() = localId == ownerId
    val membership: String get() = watchHash(
        (epoch + ":" + members.keys.sorted().joinToString(":")).toByteArray(),
    ).watchHex()
    val supported: Boolean get() = active &&
        epoch.matches(Regex("[0-9a-f]{32}")) &&
        ownerId in members &&
        localId in members
}

@Serializable
data class WatchInteractionSealed(val membership: String, val content: String) {
    fun valid(): Boolean = membership.matches(Regex("[0-9a-f]{64}")) &&
        content.length in 38..18_000 &&
        content.matches(Regex("[A-Za-z0-9_-]+"))
}

@Serializable
enum class WatchInteractionKind { Hello, Snapshot, Chat, Reaction, Voice, Signal, Receipt, Rejected }

@Serializable
data class WatchVoicePresence(val joined: Boolean = false, val muted: Boolean = true, val session: String = "") {
    fun valid(): Boolean = if (joined) session.matches(Regex("[0-9a-f]{32}")) else muted && session.isEmpty()
}

@Serializable
data class WatchInteractionPayload(
    val id: String,
    val kind: WatchInteractionKind,
    val author: String = "",
    val name: String = "",
    val text: String = "",
    val reference: String = "",
    val target: String = "",
    val signal: String = "",
    val order: Long = 0,
    val request: Long = 0,
    val presence: WatchVoicePresence = WatchVoicePresence(),
    val voices: Map<String, WatchVoicePresence> = emptyMap(),
) {
    fun valid(): Boolean = id.matches(Regex("[0-9a-f]{32}")) &&
        (author.isEmpty() || author.matches(Regex("[0-9a-f]{64}"))) &&
        name.length <= 32 &&
        (target.isEmpty() || target.matches(Regex("[0-9a-f]{64}"))) &&
        (reference.isEmpty() || reference.matches(Regex("[0-9a-f]{32}"))) &&
        order >= 0 &&
        request >= 0 &&
        text.length <= 1000 &&
        signal.length <= 9000 &&
        voices.size <= 8 &&
        presence.valid() &&
        voices.all { (id, value) -> id.matches(Regex("[0-9a-f]{64}")) && value.valid() } &&
        when (kind) {
            WatchInteractionKind.Chat -> text.isNotBlank() && signal.isEmpty()
            WatchInteractionKind.Reaction -> text in WatchReaction.entries.map { it.name } && signal.isEmpty()
            WatchInteractionKind.Signal -> target.isNotEmpty() && signal.isNotEmpty() && text.isEmpty()
            WatchInteractionKind.Receipt, WatchInteractionKind.Rejected -> reference.isNotEmpty()
            else -> text.isEmpty() && signal.isEmpty()
        }
}

enum class WatchReaction(val glyph: String) {
    Heart(
        "♥",
    ),
    Laugh("😄"),
    Surprise("😮"),
    Applause("👏"),
    Sleepy("😴"),
    Popcorn("🍿"),
}

enum class WatchChatDelivery { Sending, Sent, Failed }

data class WatchChatMessage(
    val id: String,
    val author: String,
    val name: String,
    val text: String,
    val own: Boolean,
    val delivery: WatchChatDelivery = WatchChatDelivery.Sent,
    val order: Long = Long.MAX_VALUE,
    val receivedAt: Long = 0,
)

data class WatchReactionEvent(val id: String, val author: String, val reaction: WatchReaction, val at: Long)

data class WatchInteractionState(
    val supported: Boolean = false,
    val connected: Boolean = false,
    val localId: String = "",
    val messages: List<WatchChatMessage> = emptyList(),
    val reactions: List<WatchReactionEvent> = emptyList(),
    val voices: Map<String, WatchVoicePresence> = emptyMap(),
    val unread: Int = 0,
    val reactionsEnabled: Boolean = true,
    val overlayEnabled: Boolean = false,
    val draft: String = "",
)
