package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.R
import kotlinx.serialization.Serializable

/** Optional host-authenticated presentation metadata; never an executable playback command. */
@Serializable
data class WatchActivity(
    val id: Long,
    val at: Long,
    val actorId: String,
    val name: String,
    val command: String,
    val mediaKey: String,
    val value: Double = 0.0,
) {
    fun valid(): Boolean = id > 0 &&
        at >= 0 &&
        actorId.matches(Regex("[0-9a-f]{64}")) &&
        name.length in 1..32 &&
        command in listOf("play", "pause", "seek", "speed") &&
        mediaKey.length in 1..2200 &&
        value.isFinite() &&
        value in 0.0..86_400.0

    fun label(text: RoomText): String = when (command) {
        "pause" -> text(R.string.room_actor_pause, name)
        "play" -> text(R.string.room_actor_play, name)
        "seek" -> text(R.string.room_actor_seek, name, watchPositionLabel(value))
        "speed" -> text(R.string.room_actor_speed, name)
        else -> ""
    }
}

private fun watchPositionLabel(value: Double): String {
    val seconds = value.toInt().coerceAtLeast(0)
    val minutes = seconds / 60
    return if (minutes >= 60) {
        "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    } else {
        "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
    }
}

enum class WatchRecovery { None, Command, Connection, Details }

val WatchRoomState.showPreparationFeedback: Boolean get() = preparingPlayback ||
    active &&
    phase in listOf(WatchPhase.Connecting, WatchPhase.Reconnecting)

val WatchRoomState.recovery: WatchRecovery get() = when {
    !active -> WatchRecovery.None
    commandFailed && (!sharedControls && !host || localHold) -> WatchRecovery.Details
    commandFailed -> WatchRecovery.Command
    waitingSeconds >= 12 && (relayCount == 0 || phase == WatchPhase.Reconnecting) -> WatchRecovery.Connection
    waitingSeconds >= 15 && (phase == WatchPhase.DifferentVideo || preparingPlayback) -> WatchRecovery.Details
    else -> WatchRecovery.None
}

val WatchRoomState.waitingFor: WatchMember? get() = members.firstOrNull {
    it.id != localMemberId && (!it.ready || it.buffering || it.problem != WatchProblem.None)
}.takeIf { waitForEveryone || phase == WatchPhase.Buffering }

fun WatchRoomState.preparationCaption(text: RoomText, localLoading: Boolean = false): String = when {
    commandFailed && recovery == WatchRecovery.Details -> text(R.string.room_paused)
    commandFailed -> text(R.string.room_no_confirmation)
    recovery == WatchRecovery.Connection -> text(R.string.room_connection_retry)
    phase == WatchPhase.Reconnecting || relayCount == 0 -> text(R.string.room_reconnecting)
    localHold -> text(R.string.room_local_pause)
    localLoading -> text(R.string.room_local_loading)
    waitingFor != null -> {
        val friend = waitingFor!!
        when (friend.problem) {
            WatchProblem.Connection -> text(R.string.room_wait_friend_reconnecting, friend.name)
            WatchProblem.MissingSource, WatchProblem.SourceError, WatchProblem.DifferentEdition ->
                text(R.string.room_friend_check_video, friend.name)
            WatchProblem.LocalPause -> text(R.string.room_friend_paused, friend.name)
            else -> text(R.string.room_wait_for_friend, friend.name)
        }
    }
    phase == WatchPhase.DifferentVideo -> if (recovery == WatchRecovery.Details) {
        text(R.string.room_check_episode)
    } else {
        text(R.string.room_prepare_room_episode)
    }
    preparingPlayback && members.size <= 1 -> text(R.string.room_waiting_friend)
    else -> text(R.string.room_almost_ready)
}
