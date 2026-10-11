package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Display a committed seek until native playback acknowledges it; never change saved progress. */
internal class PlaybackSeekFeedback(private val scope: CoroutineScope, private val timeoutMillis: Long = 8_000) {
    private data class Pending(val target: Float, var nativeStarted: Boolean = false)

    private val mutablePosition = MutableStateFlow(0f)
    val position = mutablePosition.asStateFlow()
    private var lastNativePosition = 0f
    private var pending: Pending? = null
    private var deadline: Job? = null

    fun request(target: Float) {
        if (!target.isFinite() || target < 0f) return
        deadline?.cancel()
        val request = Pending(target)
        pending = request
        mutablePosition.value = target
        deadline = scope.launch {
            delay(timeoutMillis)
            if (pending === request) reset(lastNativePosition)
        }
    }

    fun observe(nativePosition: Float) {
        if (!nativePosition.isFinite() || nativePosition < 0f) return
        lastNativePosition = nativePosition
        val request = pending
        if (request == null || abs(nativePosition - request.target) <= 1.25f) {
            reset(nativePosition)
        }
    }

    fun nativeStarted() {
        pending?.nativeStarted = true
    }

    /** A completed keyframe seek can legitimately land away from the requested exact second. */
    fun nativeFinished(nativePosition: Float) {
        if (pending?.nativeStarted == true && nativePosition.isFinite() && nativePosition >= 0f) {
            reset(nativePosition)
        }
    }

    fun reset(nativePosition: Float = 0f) {
        deadline?.cancel()
        deadline = null
        pending = null
        lastNativePosition = nativePosition.takeIf { it.isFinite() && it >= 0f } ?: 0f
        mutablePosition.value = lastNativePosition
    }
}
