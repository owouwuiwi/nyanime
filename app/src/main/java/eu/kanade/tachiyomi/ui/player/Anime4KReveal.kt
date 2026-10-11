package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Window coordinates of the actual SM button. No video pixels or renderer parameters. */
internal data class Anime4KRevealOrigin(val x: Float, val y: Float) {
    val valid get() = x.isFinite() && y.isFinite() && x in -1_000_000f..1_000_000f && y in -1_000_000f..1_000_000f
}

internal data class Anime4KCloudFrame(val id: Long, val origin: Anime4KRevealOrigin, val exiting: Boolean = false)

/** A bounded UI effect. SM itself is applied once through the ordinary pipeline. */
internal class Anime4KReveal(
    private val scope: CoroutineScope,
    private val selection: StateFlow<Anime4KSelection>,
    private val measurementBoundary: () -> Unit,
) {
    private val current = MutableStateFlow<Anime4KCloudFrame?>(null)
    val state = current.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var lastSample: Anime4KPerformanceSample? = null
    val measuringSuppressed get() = current.value != null

    fun toggle(origin: Anime4KRevealOrigin?, enabled: Boolean, animate: Boolean, action: () -> Unit) {
        val previous = current.value
        job?.cancel()
        job = null
        val sequence = ++generation
        if (enabled || !animate) publish(null)
        try {
            action()
        } catch (error: Throwable) {
            finish()
            throw error
        }
        if (!enabled) {
            if (animate && previous != null) {
                publish(previous.copy(exiting = true))
                job = scope.launch {
                    delay(FADE_MILLIS * 10 + 250)
                    if (sequence == generation) finish()
                }
            } else {
                publish(null)
            }
            return
        }
        if (!animate || origin?.valid != true) return
        job = scope.launch {
            val applied = withTimeoutOrNull(Anime4KContentAnalysis.DEADLINE_MILLIS + 500) {
                selection.first { it.profile != Anime4KProfile.Smart || it.mode != Anime4KMode.Off }
            }
            val actual = applied ?: return@launch
            if (sequence != generation || actual.profile != Anime4KProfile.Smart || actual.mode == Anime4KMode.Off) {
                return@launch
            }
            publish(Anime4KCloudFrame(sequence, origin))
            // Compose normally completes the effect. This bounds an absent UI and
            // also permits the system's deliberately slowed animation scale.
            delay(DURATION_MILLIS * 10 + 250)
            if (sequence == generation) finish()
        }
    }

    fun complete(id: Long) {
        if (current.value?.id == id) finish()
    }

    fun observePerformance(sample: Anime4KPerformanceSample) {
        val previous = lastSample
        lastSample = sample
        if (!measuringSuppressed || !sample.playing || previous == null) return
        val output = sample.outputDroppedFrames?.let { now -> previous.outputDroppedFrames?.let { now - it } }
        val decoder = sample.decoderDroppedFrames?.let { now -> previous.decoderDroppedFrames?.let { now - it } }
        if (output != null && output >= 2 && decoder == 0L) finish()
    }

    fun finish() {
        generation++
        job?.cancel()
        job = null
        publish(null)
    }

    private fun publish(frame: Anime4KCloudFrame?) {
        val wasActive = current.value != null
        current.value = frame
        if (wasActive != (frame != null)) {
            // Preparation can include ordinary shader startup drops. Only fresh
            // samples inside this decoration may end it for presentation pressure.
            lastSample = null
            measurementBoundary()
        }
    }

    companion object {
        const val DURATION_MILLIS = 1_150L
        const val FADE_MILLIS = 150L
    }
}
