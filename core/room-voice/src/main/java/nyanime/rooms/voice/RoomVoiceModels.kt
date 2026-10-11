package nyanime.rooms.voice

import kotlinx.serialization.Serializable

@Serializable
enum class RoomVoiceSignalType { Offer, Answer, Candidates, Restart }

@Serializable
data class RoomVoiceCandidate(val mid: String?, val line: Int, val candidate: String) {
    fun valid(): Boolean = (mid == null || mid.length <= 64) &&
        line in 0..8 &&
        candidate.length in 1..1024 &&
        candidate.startsWith("candidate:") &&
        '\n' !in candidate &&
        '\r' !in candidate
}

@Serializable
data class RoomVoiceSignal(
    val connection: String,
    val revision: Long,
    val type: RoomVoiceSignalType,
    val description: String = "",
    val candidates: List<RoomVoiceCandidate> = emptyList(),
    val senderSession: String = "",
    val targetSession: String = "",
) {
    fun valid(): Boolean = connection.matches(Regex("[0-9a-f]{32}")) &&
        revision in 1..1_000_000 &&
        senderSession.matches(Regex("[0-9a-f]{32}")) &&
        targetSession.matches(Regex("[0-9a-f]{32}")) &&
        description.length <= 8000 &&
        candidates.size <= 8 &&
        candidates.all { it.valid() } &&
        when (type) {
            RoomVoiceSignalType.Offer, RoomVoiceSignalType.Answer ->
                description.startsWith("v=0") &&
                    description.lineSequence().count { it.startsWith("m=") } == 1 &&
                    description.lineSequence().any { it.startsWith("m=audio ") } &&
                    candidates.isEmpty()
            RoomVoiceSignalType.Candidates -> description.isEmpty() && candidates.isNotEmpty()
            RoomVoiceSignalType.Restart -> description.isEmpty() && candidates.isEmpty()
        }
}

enum class RoomVoicePhase { Connecting, Connected, Reconnecting, Unavailable }

data class RoomVoicePeer(val phase: RoomVoicePhase, val speaking: Boolean = false)

data class RoomVoiceState(
    val active: Boolean = false,
    val muted: Boolean = true,
    val available: Boolean = true,
    val microphoneUnavailable: Boolean = false,
    val peers: Map<String, RoomVoicePeer> = emptyMap(),
    val gain: Float = 1f,
)
