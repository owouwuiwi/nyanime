package eu.kanade.tachiyomi.ui.player.session

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

internal data class PlaybackPointerDelta(val pan: Offset = Offset.Zero, val zoom: Float = 1f)

/** Positions are converted to app-window coordinates before sampling a moving rendering host. */
internal class PlaybackPointerTracker {
    private var previous: Map<Long, Offset> = emptyMap()
    fun sample(points: Map<Long, Offset>): PlaybackPointerDelta {
        val common = points.keys.intersect(previous.keys)
        val result = if (common.isEmpty()) {
            PlaybackPointerDelta()
        } else {
            val before = common.map { previous.getValue(it) }
            val after = common.map { points.getValue(it) }
            fun center(values: List<Offset>) = values.reduce(Offset::plus) / values.size.toFloat()
            val oldCenter = center(before)
            val newCenter = center(after)
            val oldRadius = before.sumOf { (it - oldCenter).getDistance().toDouble() }
            val newRadius = after.sumOf { (it - newCenter).getDistance().toDouble() }
            val zoom = if (common.size > 1 && abs(oldRadius) > 0.001) (newRadius / oldRadius).toFloat() else 1f
            PlaybackPointerDelta(newCenter - oldCenter, zoom.takeIf { it.isFinite() } ?: 1f)
        }
        previous = points
        return result
    }
}
