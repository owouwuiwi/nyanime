package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.R
import kotlin.math.abs
import kotlin.math.roundToLong

/** The room clock belongs to playback intent, never to a decoder that happens to be buffering. */
internal class WatchPlaybackTimeline {
    private var key: String? = null
    private var position = 0.0
    private var at = 0L
    private var moving = false
    private var speed = 1.0

    fun reset() {
        key = null
        position = 0.0
        at = 0L
        moving = false
        speed = 1.0
    }

    fun bind(mediaKey: String?, localPosition: Double, now: Long) {
        if (key == mediaKey && key != null) return
        reset()
        key = mediaKey
        position = localPosition.coerceAtLeast(0.0)
        at = now
    }

    fun position(now: Long, duration: Double): Double =
        (position + if (moving) (now - at).coerceAtLeast(0) / 1000.0 * speed else 0.0)
            .coerceIn(0.0, duration.takeIf { it > 0 } ?: 86_400.0)

    fun update(running: Boolean, value: Double, now: Long) {
        if (moving == running && speed == value) return
        position = position(now, 86_400.0)
        at = now
        moving = running
        speed = value
    }

    fun seek(value: Double, now: Long) {
        position = value.coerceIn(0.0, 86_400.0)
        at = now
    }

    fun speed(value: Double, now: Long) = update(moving, value, now)
}

/** Provisional manifest durations must settle before an otherwise exact episode is rejected. */
internal class WatchDurationVerifier {
    private var value: Triple<String, Double, Double>? = null
    private var since = 0L

    fun incompatible(local: WatchMedia, remote: WatchMedia, now: Long): Boolean {
        val next = Triple(local.key + "|" + remote.key, local.duration, remote.duration)
        if (next != value) {
            value = next
            since = now
        }
        return local.duration > 0 &&
            remote.duration > 0 &&
            now - since >= 3000 &&
            !local.compatibleDuration(remote)
    }

    fun reset() {
        value = null
        since = 0L
    }
}

/** Retains a single shared deadline; every interruption invalidates it before another start is planned. */
internal class WatchStartGate {
    private var readySince: Long? = null
    var deadline: Long? = null
        private set

    fun reset() {
        readySince = null
        deadline = null
    }

    fun update(ready: Boolean, requested: Boolean, now: Long): Boolean {
        if (!ready || !requested) {
            reset()
            return false
        }
        val since = readySince ?: now.also { readySince = it }
        if (deadline == null && now - since >= 1000) deadline = now + 2000
        return deadline?.let { now >= it } == true
    }
}

/** Each player fills its own cache in parallel. A missing mpv estimate cannot hold the room forever. */
internal class WatchPrebufferGate {
    private var waitingSince: Long? = null

    fun reset() {
        waitingSince = null
    }

    fun waiting(enabled: Boolean, firstStart: Boolean, ready: Boolean, now: Long, buffers: List<Int?>): Boolean {
        if (!enabled || !firstStart) {
            reset()
            return false
        }
        if (!ready) return false
        val since = waitingSince ?: now.also { waitingSince = it }
        val elapsed = now - since
        return elapsed < MAX_WAIT_MS &&
            (
                buffers.any { it != null && it < TARGET_SECONDS } ||
                    (elapsed < UNKNOWN_WAIT_MS && buffers.any { it == null })
                )
    }

    companion object {
        const val TARGET_SECONDS = 15
        const val UNKNOWN_WAIT_MS = 4_000L
        const val MAX_WAIT_MS = 15_000L
    }
}

/** A filtered error, hysteresis and slew limit prevent the speed from following individual jitter samples. */
internal class WatchDriftCorrector {
    private var previousAt: Long? = null
    private var filtered = 0.0
    private var adjusting = false
    private var factor = 1.0
    private var largeSince: Long? = null

    fun reset() {
        previousAt = null
        filtered = 0.0
        adjusting = false
        factor = 1.0
        largeSince = null
    }

    fun correct(
        position: Double,
        target: Double,
        speed: Double,
        paused: Boolean,
        canSeek: Boolean,
        now: Long,
    ): WatchCorrection {
        val drift = target - position
        if (paused) {
            reset()
            return WatchCorrection(if (canSeek && abs(drift) > 3.0) target else null, speed)
        }
        val dt = ((previousAt?.let { now - it } ?: 250).coerceIn(1, 1000)) / 1000.0
        val alpha = dt / (0.8 + dt)
        filtered = if (previousAt == null) drift else filtered + alpha * (drift - filtered)
        previousAt = now
        if (abs(drift) > 3.0) {
            if (largeSince == null) largeSince = now
        } else {
            largeSince = null
        }
        if (canSeek && (abs(drift) > 8.0 || largeSince?.let { now - it >= 3000 } == true)) {
            reset()
            return WatchCorrection(target, speed)
        }
        if (!adjusting && abs(filtered) > 1.0) adjusting = true
        if (adjusting && abs(filtered) < 0.75) adjusting = false
        val desired = if (adjusting) 1.0 + (filtered * 0.025).coerceIn(-0.03, 0.03) else 1.0
        factor += (desired - factor).coerceIn(-0.012 * dt, 0.012 * dt)
        factor = factor.coerceIn(0.97, 1.03)
        return WatchCorrection(speed = speed * ((factor * 10000).roundToLong() / 10000.0))
    }
}

internal fun remainingSeconds(deadline: Long?, now: Long): Int? =
    deadline?.let { ((it - now).coerceAtLeast(0) + 999).div(1000).toInt() }

fun WatchProblem.description(text: RoomText): String = when (this) {
    WatchProblem.None -> text(R.string.room_ready)
    WatchProblem.Opening -> text(R.string.room_opening)
    WatchProblem.Buffering -> text(R.string.room_loading)
    WatchProblem.MissingSource -> text(R.string.room_missing_extension)
    WatchProblem.SourceError -> text(R.string.room_source_unavailable)
    WatchProblem.DifferentEdition -> text(R.string.room_different_edition)
    WatchProblem.LocalPause -> text(R.string.room_local_pause_short)
    WatchProblem.Connection -> text(R.string.room_reconnection)
}
