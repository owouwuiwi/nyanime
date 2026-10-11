package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlaybackFailure {
    Format,
    Network,
    SourcePage,
    Timeout,
    Unknown,
    ;

    companion object {
        fun fromNative(message: String): PlaybackFailure = when {
            message.contains("HTTP", true) || message.contains("network", true) -> Network
            message.contains("format", true) -> Format
            else -> Unknown
        }
    }
}

/** One owner for independent MPV wait reasons. Repeated events cannot extend the deadline. */
class PlaybackLoadMonitor(
    private val scope: CoroutineScope,
    private val onTimeout: () -> Unit,
    private val timeoutMs: Long = 45_000,
) {
    data class State(
        val generation: Long = 0,
        val opening: Boolean = false,
        val seeking: Boolean = false,
        val buffering: Boolean = false,
        val started: Boolean = false,
        val failure: PlaybackFailure? = null,
    ) {
        val loading: Boolean get() = failure == null && (opening || seeking || buffering)
        val canSaveProgress: Boolean get() = started && !loading && failure == null
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var deadline: Job? = null

    @Synchronized
    fun begin() {
        deadline?.cancel()
        deadline = null
        publish(State(generation = state.value.generation + 1, opening = true))
    }

    @Synchronized
    fun seeking(value: Boolean) = update { copy(seeking = value) }

    @Synchronized
    fun buffering(value: Boolean) = update { copy(buffering = value) }

    @Synchronized
    fun restarted(buffering: Boolean) = update {
        copy(opening = false, seeking = false, buffering = buffering, started = true)
    }

    /** MPV's cache wait can outlive a room pause. Cached media can resume without waiting for restart. */
    @Synchronized
    fun recoverPausedCache(
        paused: Boolean,
        nativeSeeking: Boolean?,
        bufferedAhead: Double?,
        remaining: Double,
    ): Boolean {
        val current = state.value
        if (!paused ||
            !current.started ||
            current.opening ||
            current.failure != null ||
            (!current.buffering && !current.seeking) ||
            nativeSeeking != false ||
            bufferedAhead == null ||
            !bufferedAhead.isFinite() ||
            bufferedAhead < minOf(2.0, remaining.coerceAtLeast(0.5))
        ) {
            return false
        }
        publish(current.copy(buffering = false, seeking = false))
        return true
    }

    @Synchronized
    fun fail(reason: PlaybackFailure) {
        publish(state.value.copy(opening = false, seeking = false, buffering = false, failure = reason))
    }

    @Synchronized
    fun cancel() {
        publish(State(generation = state.value.generation + 1))
    }

    private fun update(transform: State.() -> State) {
        if (state.value.failure == null) publish(state.value.transform())
    }

    private fun publish(next: State) {
        mutableState.value = next
        if (!next.loading) {
            deadline?.cancel()
            deadline = null
        } else if (deadline == null) {
            val generation = next.generation
            deadline = scope.launch {
                delay(timeoutMs)
                val expired = synchronized(this@PlaybackLoadMonitor) {
                    if (state.value.generation != generation || !state.value.loading) {
                        false
                    } else {
                        deadline = null
                        mutableState.value = state.value.copy(
                            opening = false,
                            seeking = false,
                            buffering = false,
                            failure = PlaybackFailure.Timeout,
                        )
                        true
                    }
                }
                if (expired) onTimeout()
            }
        }
    }
}
