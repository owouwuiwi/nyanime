package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvPlayback
import nyanime.tv.api.TvRecovery
import nyanime.tv.api.TvStream

/** The Android adapter owns MPV. Every callback is bound to the corresponding load ticket. */
interface TvNativeOutput {
    fun load(stream: TvStream, ticket: Long)
    fun pause(value: Boolean)
    fun speed(value: Double)
    fun seek(seconds: Double)
    fun enhancement(enabled: Boolean)
    fun seekWindow(): ClosedFloatingPointRange<Double>? = null
    fun release()
}

data class TvPlayerState(
    val reference: TvChannelRef? = null,
    val media: TvPlayback? = null,
    val streamIndex: Int = 0,
    val loading: Boolean = false,
    val buffering: Boolean = false,
    val paused: Boolean = false,
    val ready: Boolean = false,
    val failed: Boolean = false,
    val retryAttempt: Int = 0,
    val room: Boolean = false,
    val enhanced: Boolean = false,
    val failure: TvPlaybackFailure? = null,
) {
    val stream: TvStream? get() = media?.streams?.getOrNull(streamIndex)
}

/** This session has no episode DB, tracker, intro-skip, completion or download side effects. */
class TvPlayerSession(
    private val scope: CoroutineScope,
    private val repository: TvRepository,
    private val native: TvNativeOutput,
    private val now: () -> Long,
    private val enhancementPreference: () -> Boolean,
    private val incognito: () -> Boolean,
    private val beforePlay: () -> Boolean = { true },
) {
    private val mutable = MutableStateFlow(TvPlayerState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var watchdog: Job? = null
    private var generation = 0L
    private var ticket = 0L
    private var closed = false
    private val recovery = TvRecovery()
    private var enhancementChosen = enhancementPreference()
    private val rejectedStreams = mutableSetOf<Pair<String, Map<String, String>>>()
    private var refreshedRejectedStreams = false

    /** Invoked on the owner dispatcher, the main thread in Android production. */
    fun select(reference: TvChannelRef, prepared: nyanime.tv.api.TvPlayback? = null) {
        if (closed) return
        job?.cancel()
        watchdog?.cancel()
        ticket++
        native.pause(true)
        val version = ++generation
        recovery.select(version)
        rejectedStreams.clear()
        refreshedRejectedStreams = false
        mutable.value = TvPlayerState(reference = reference, loading = true, room = state.value.room)
        job = scope.launch {
            try {
                val playback = prepared ?: repository.resolve(reference)
                if (closed || generation != version) return@launch
                mutable.value = state.value.copy(media = playback)
                applyEnhancement()
                load()
                // A local settings fault must not label an already playing stream as failed.
                runCatching { repository.preferences.visited(reference, incognito()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed && generation == version) mutable.value = state.value.copy(loading = false, failed = true)
            }
        }
    }

    fun loaded(eventTicket: Long) {
        if (!accept(eventTicket) || state.value.ready) return
        watchdog?.cancel()
        if (!state.value.buffering && !state.value.paused) recovery.ready(generation, now())
        mutable.value = state.value.copy(loading = false, ready = true, failed = false, failure = null)
        native.pause(state.value.paused)
        if (state.value.buffering) watch(eventTicket)
    }

    fun buffering(eventTicket: Long, value: Boolean) {
        if (!accept(eventTicket)) return
        mutable.value = state.value.copy(buffering = value)
        watchdog?.cancel()
        if (value) {
            recovery.interrupted(generation)
            if (!state.value.paused) watch(eventTicket)
        } else if (state.value.ready && !state.value.paused) {
            recovery.ready(generation, now())
        } else if (state.value.loading && !state.value.paused) {
            watch(eventTicket)
        }
    }

    fun failed(eventTicket: Long, failure: TvPlaybackFailure = TvPlaybackFailure.UNKNOWN) {
        if (!accept(eventTicket)) return
        // One terminal native event can be followed by duplicate property/end-file events.
        // Invalidate that load before scheduling another attempt.
        ticket++
        watchdog?.cancel()
        job?.cancel()
        val version = generation
        mutable.value = state.value.copy(failure = failure)
        if (!failure.retryable && !state.value.paused) {
            state.value.stream?.address()?.let(rejectedStreams::add)
            mutable.value = state.value.copy(
                ready = false,
                loading = true,
                buffering = false,
                failed = false,
                retryAttempt = state.value.retryAttempt + 1,
            )
            job = scope.launch {
                try {
                    var next = state.value.media?.streams?.indexOfFirst { it.address() !in rejectedStreams } ?: -1
                    if (next < 0 && !refreshedRejectedStreams) {
                        // One fresh extension resolution can replace an expired URL. Unchanged
                        // rejected URLs are never fed to the native player repeatedly.
                        refreshedRejectedStreams = true
                        val reference = state.value.reference ?: return@launch
                        val playback = repository.resolve(reference)
                        if (closed || generation != version || state.value.paused) return@launch
                        mutable.value = state.value.copy(media = playback)
                        next = playback.streams.indexOfFirst { it.address() !in rejectedStreams }
                    }
                    if (closed || generation != version || state.value.paused) return@launch
                    if (next < 0) {
                        terminalFailure(failure)
                    } else {
                        mutable.value = state.value.copy(streamIndex = next)
                        load()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (!closed && generation == version) terminalFailure(failure)
                }
            }
            return
        }
        val delayMs = recovery.retry(version, now(), state.value.paused)
        if (delayMs == null) {
            terminalFailure(failure)
            return
        }
        mutable.value =
            state.value.copy(
                ready = false,
                loading = true,
                buffering = false,
                failed = false,
                retryAttempt = recovery.attempts,
            )
        job = scope.launch {
            delay(delayMs)
            if (closed || generation != version || state.value.paused) return@launch
            val streams = state.value.media?.streams.orEmpty()
            val next = (1..streams.size).map { (state.value.streamIndex + it) % streams.size }
                .firstOrNull { streams[it].address() !in rejectedStreams }
            if (next == null) {
                terminalFailure(failure)
                return@launch
            }
            try {
                // A new cycle asks the extension for fresh ephemeral stream URLs.
                if (next == 0) {
                    val reference = state.value.reference ?: return@launch
                    val playback = repository.resolve(reference)
                    if (closed || generation != version || state.value.paused) return@launch
                    val candidate = playback.streams.indexOfFirst { it.address() !in rejectedStreams }
                    if (candidate < 0) {
                        terminalFailure(failure)
                        return@launch
                    }
                    mutable.value = state.value.copy(media = playback, streamIndex = candidate)
                } else {
                    mutable.value = state.value.copy(streamIndex = next)
                }
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed && generation == version) failed(ticket)
            }
        }
    }

    fun pause(value: Boolean) {
        if (closed) return
        job?.cancel()
        watchdog?.cancel()
        recovery.interrupted(generation)
        mutable.value = state.value.copy(paused = value, loading = false, buffering = false)
        if (value) {
            native.pause(true)
        } else {
            val ref = state.value.reference ?: return
            if (state.value.media == null) select(ref) else load()
        }
    }

    fun live() = pause(false)

    fun unavailable() {
        if (closed) return
        generation++
        ticket++
        job?.cancel()
        watchdog?.cancel()
        native.pause(true)
        mutable.value =
            state.value.copy(loading = false, buffering = false, ready = false, failed = true, paused = true)
    }

    fun quality(index: Int) {
        if (closed || state.value.media?.streams?.getOrNull(index) == null) return
        job?.cancel()
        rejectedStreams.clear()
        refreshedRejectedStreams = false
        mutable.value = state.value.copy(streamIndex = index, paused = false, failure = null)
        load()
    }

    fun speed(value: Double) {
        if (!closed &&
            state.value.stream?.adjustableSpeed == true &&
            value.isFinite() &&
            value in 0.5..2.0
        ) {
            native.speed(value)
        }
    }

    fun seek(seconds: Double) {
        if (!closed &&
            state.value.stream?.rewind == true &&
            seconds.isFinite() &&
            seconds >= 0 &&
            native.seekWindow()?.contains(seconds) == true
        ) {
            native.seek(seconds)
        }
    }

    fun room(active: Boolean) {
        if (closed) return
        mutable.value = state.value.copy(room = active)
        applyEnhancement()
    }

    fun toggleEnhancement() {
        if (closed || state.value.room) return
        enhancementChosen = !enhancementChosen
        applyEnhancement()
    }

    private fun applyEnhancement() {
        val enabled = enhancementChosen && !state.value.room
        mutable.value = state.value.copy(enhanced = enabled)
        native.enhancement(enabled)
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        ticket++
        job?.cancel()
        watchdog?.cancel()
        mutable.value = state.value.copy(ready = false, loading = false, buffering = false)
        native.release()
    }

    private fun load() {
        if (closed) return
        val stream = state.value.stream ?: return
        if (!beforePlay()) {
            mutable.value = state.value.copy(paused = true, loading = false, buffering = false)
            return
        }
        mutable.value = state.value.copy(loading = true, buffering = false, ready = false, failed = false)
        val next = ++ticket
        try {
            native.load(stream, next)
            watch(next)
        } catch (_: Exception) {
            failed(next)
        }
    }

    private fun accept(eventTicket: Long): Boolean = !closed && eventTicket == ticket && state.value.media != null

    // Updated request headers can make the same URL playable. Labels, IDs and
    // header name capitalization alone must not resurrect a failed request.
    private fun TvStream.address() = url to headers.mapKeys { it.key.lowercase() }

    private fun terminalFailure(failure: TvPlaybackFailure) {
        mutable.value = state.value.copy(
            loading = false,
            buffering = false,
            ready = false,
            failed = true,
            failure = failure,
        )
    }

    private fun watch(eventTicket: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(30_000)
            if (accept(eventTicket) &&
                !state.value.paused &&
                (state.value.loading || state.value.buffering)
            ) {
                failed(eventTicket)
            }
        }
    }
}
