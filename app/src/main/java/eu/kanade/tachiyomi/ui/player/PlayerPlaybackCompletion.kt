package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NextEpisodePrompt(val episodeId: Long, val secondsRemaining: Int)

/** One decision per actual EOF. No stream is fetched until the user or countdown commits it. */
internal class PlayerPlaybackCompletion(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val canAdvance: () -> Boolean,
    private val onPlayNext: (Long) -> Unit,
) {
    private var job: Job? = null
    private val next = MutableStateFlow<NextEpisodePrompt?>(null)
    val prompt = next.asStateFlow()
    var atEnd = false
        private set

    fun onEnded(nextEpisodeId: Long?, autoPlay: Boolean) {
        if (atEnd) return
        atEnd = true
        if (nextEpisodeId == null || !autoPlay || !canAdvance()) return
        val deadline = nowMillis() + COUNTDOWN_SECONDS * 1000L
        next.value = NextEpisodePrompt(nextEpisodeId, COUNTDOWN_SECONDS)
        job = scope.launch {
            while (next.value?.episodeId == nextEpisodeId) {
                if (!canAdvance()) {
                    cancel()
                    return@launch
                }
                val remaining = deadline - nowMillis()
                if (remaining <= 0) {
                    playNow()
                    return@launch
                }
                next.value = NextEpisodePrompt(nextEpisodeId, ((remaining + 999) / 1000).toInt())
                delay(minOf(1000L, remaining))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        next.value = null
    }

    fun playbackRestarted() {
        cancel()
        atEnd = false
    }

    fun playNow() {
        val target = next.value ?: return
        cancel()
        if (canAdvance()) onPlayNext(target.episodeId)
    }

    companion object {
        const val COUNTDOWN_SECONDS = 10
    }
}
