package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** A preview is not a native seek or a change to the shared playback clock. Main-thread owned. */
internal class PlaybackScrubCoordinator(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class State(
        val identity: String,
        val origin: Float,
        val target: Float,
        val duration: Float,
        val paused: Boolean,
        val speed: Float,
        val clockPosition: Float,
        val clockAt: Long,
    ) {
        val delta: Float get() = target - origin
    }

    data class Completion(val target: Float?, val paused: Boolean)

    private val mutableState = MutableStateFlow<State?>(null)
    val state = mutableState.asStateFlow()
    val active: Boolean get() = mutableState.value != null

    fun begin(identity: String, position: Float, duration: Float, paused: Boolean, speed: Float): Boolean {
        mutableState.value?.let { return it.identity == identity }
        if (!position.isFinite() || !duration.isFinite() || duration <= 0f || identity.isBlank()) return false
        val origin = position.coerceIn(0f, duration)
        mutableState.value = State(
            identity,
            origin,
            origin,
            duration,
            paused,
            speed.takeIf { it.isFinite() && it > 0f } ?: 1f,
            origin,
            now(),
        )
        return true
    }

    fun update(target: Float): Float? {
        val current = mutableState.value ?: return null
        if (!target.isFinite()) return null
        val bounded = target.coerceIn(0f, current.duration)
        mutableState.value = current.copy(target = bounded)
        return bounded
    }

    /** Remote pause/play commands remain authoritative, without releasing the local preview hold. */
    fun playbackIntent(paused: Boolean) {
        val current = mutableState.value ?: return
        if (current.paused == paused) return
        mutableState.value = current.copy(paused = paused, clockPosition = clockPosition(), clockAt = now())
    }

    fun clockPosition(): Float {
        val current = mutableState.value ?: return 0f
        val elapsed = if (current.paused) 0f else (now() - current.clockAt).coerceAtLeast(0) / 1000f * current.speed
        return (current.clockPosition + elapsed).coerceIn(0f, current.duration)
    }

    fun finish(identity: String, commit: Boolean, restorePlayback: Boolean = true): Completion? {
        val current = mutableState.value ?: return null
        mutableState.value = null
        if (current.identity != identity) return null
        return Completion(
            current.target.takeIf { commit && abs(current.delta) >= .001f },
            if (restorePlayback) current.paused else true,
        )
    }
}
