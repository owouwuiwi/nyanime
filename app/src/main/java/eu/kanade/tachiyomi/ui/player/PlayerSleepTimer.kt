package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A playback-session timer measured against a monotonic clock, including time spent paused. */
internal class PlayerSleepTimer(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val onExpired: () -> Unit,
) {
    private var job: Job? = null
    private var deadline: Long? = null
    private var expired = false
    private val remaining = MutableStateFlow(0)
    val remainingTime = remaining.asStateFlow()
    private val endEpisode = MutableStateFlow<Long?>(null)
    val endEpisodeId = endEpisode.asStateFlow()

    fun start(seconds: Int) {
        endEpisode.value = null
        expired = false
        startMillis(seconds.coerceAtLeast(0) * 1000L)
    }

    fun stopAtEpisodeEnd(episodeId: Long) {
        start(0)
        endEpisode.value = episodeId
    }

    /** A different episode ends a one-episode timer; timed sessions can span multiple episodes. */
    fun onEpisodeChanged(episodeId: Long) {
        if (endEpisode.value != null && endEpisode.value != episodeId) endEpisode.value = null
        expired = false
    }

    fun acknowledgeUserPlayback() {
        expired = false
    }

    fun onEpisodeEnded(episodeId: Long): Boolean {
        if (endEpisode.value == episodeId) expire()
        expireIfDue()
        return expired
    }

    /** Checks the clock too: an autoplay callback may run before the timer's due coroutine. */
    fun allowsAutoPlay(episodeId: Long): Boolean {
        expireIfDue()
        return !expired && endEpisode.value != episodeId
    }

    private fun expireIfDue() {
        if (deadline?.let { it <= nowMillis() } == true) expire()
    }

    private fun expire() {
        job?.cancel()
        deadline = null
        remaining.value = 0
        endEpisode.value = null
        if (expired) return
        expired = true
        onExpired()
    }

    /** Extend the actual deadline, without rounding seconds or reviving an expired timer. */
    fun extend(seconds: Int) {
        val now = nowMillis()
        val millisLeft = (deadline ?: return) - now
        if (millisLeft <= 0L || seconds <= 0) return
        startMillis((millisLeft + seconds * 1000L).coerceAtMost(Int.MAX_VALUE * 1000L), now)
    }

    private fun startMillis(duration: Long, now: Long = nowMillis()) {
        job?.cancel()
        deadline = null
        remaining.value = ((duration + 999L) / 1000L).toInt()
        if (duration == 0L) return
        val target = now + duration
        deadline = target
        job = scope.launch {
            while (true) {
                val millisLeft = target - nowMillis()
                remaining.value = ((millisLeft.coerceAtLeast(0L) + 999L) / 1000L).toInt()
                if (millisLeft <= 0L) break
                delay(minOf(millisLeft, 1000L))
            }
            expire()
        }
    }
}
