package eu.kanade.tachiyomi.ui.player.session

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

internal enum class PlaybackGestureAxis { Undecided, Horizontal, Vertical }

/** The first deliberate direction owns the entire pointer sequence. */
internal class PlaybackGestureAxisLock(private val touchSlop: Float) {
    private var axis = PlaybackGestureAxis.Undecided

    fun sample(displacement: Offset): PlaybackGestureAxis {
        if (axis == PlaybackGestureAxis.Undecided) {
            val horizontal = abs(displacement.x)
            val vertical = abs(displacement.y)
            axis = when {
                horizontal > touchSlop && horizontal >= vertical -> PlaybackGestureAxis.Horizontal
                vertical > touchSlop -> PlaybackGestureAxis.Vertical
                else -> PlaybackGestureAxis.Undecided
            }
        }
        return axis
    }
}
