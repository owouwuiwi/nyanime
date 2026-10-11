package eu.kanade.tachiyomi.ui.player.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class PlaybackFrame(val rect: PlaybackRect, val page: Float, val expanded: Float) {
    fun interpolate(target: PlaybackFrame, fraction: Float) = PlaybackFrame(
        rect.interpolate(target.rect, fraction),
        page + (target.page - page) * fraction.coerceIn(0f, 1f),
        expanded + (target.expanded - expanded) * fraction.coerceIn(0f, 1f),
    )
}

/** One clock for geometry and visibility. Read [current] in layout/draw, never in composition. */
internal class PlaybackMotion(initial: PlaybackFrame) {
    private val progress = Animatable(1f)
    private var start by mutableStateOf(initial)
    private var end by mutableStateOf(initial)
    private var gesture by mutableStateOf<PlaybackFrame?>(null)
    val current: PlaybackFrame get() = gesture ?: start.interpolate(end, progress.value)

    fun follow(frame: PlaybackFrame) {
        gesture = frame
    }

    suspend fun stop() {
        progress.stop()
    }

    suspend fun moveTo(frame: PlaybackFrame, duration: Int) {
        val previous = current
        gesture = previous
        progress.stop()
        progress.snapTo(0f)
        start = previous
        end = frame
        gesture = null
        if (duration == 0) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(
                1f,
                tween(duration, easing = eu.kanade.presentation.motion.ModernMotion.SmoothOutEasing),
            )
        }
    }
}
