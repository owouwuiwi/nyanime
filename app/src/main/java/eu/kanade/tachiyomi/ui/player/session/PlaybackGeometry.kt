package eu.kanade.tachiyomi.ui.player.session

import kotlin.math.min
import kotlin.math.roundToInt

internal enum class MiniCorner { TopStart, TopEnd, BottomStart, BottomEnd }

internal data class MiniLayout(val widthDp: Float = 196f, val corner: MiniCorner = MiniCorner.BottomEnd)

internal data class PlaybackRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun interpolate(target: PlaybackRect, fraction: Float): PlaybackRect {
        val progress = fraction.coerceIn(0f, 1f)
        if (progress == 0f) return this
        if (progress == 1f) return target
        fun mix(from: Float, to: Float) = from + (to - from) * progress
        return PlaybackRect(mix(x, target.x), mix(y, target.y), mix(width, target.width), mix(height, target.height))
    }
}

/** Geometry is independent of the engine and always derives from the current window, not the physical screen. */
internal object PlaybackGeometry {
    /** Rotating a window does not change the source-shaped renderer or allocate another buffer. */
    fun renderSize(width: Float, height: Float, aspect: Float): Pair<Int, Int> {
        val shortEdge = min(width, height).coerceAtLeast(1f)
        val longEdge = maxOf(width, height).coerceAtLeast(1f)
        val safeAspect = aspect.takeIf { it.isFinite() && it in 0.1f..10f } ?: 16f / 9f
        val renderWidth = if (safeAspect >= 1f) {
            min(longEdge, shortEdge * safeAspect)
        } else {
            min(shortEdge, longEdge * safeAspect)
        }.roundToInt().coerceAtLeast(1)
        return renderWidth to (renderWidth / safeAspect).roundToInt().coerceAtLeast(1)
    }

    /** The grab point, not the top edge, follows the finger while a page becomes a mini player. */
    fun pageDrag(origin: PlaybackRect, target: PlaybackRect, anchorY: Float, distance: Float): PlaybackRect {
        val anchor = anchorY.coerceIn(0f, 1f)
        val travel = pageDragTravel(origin, target, anchor)
        val fraction = (distance / travel).coerceIn(0f, 1f)
        val resized = origin.interpolate(target, fraction)
        return resized.copy(y = origin.y + distance + anchor * (origin.height - resized.height))
    }

    fun pageDragTravel(origin: PlaybackRect, target: PlaybackRect, anchorY: Float): Float {
        val anchor = anchorY.coerceIn(0f, 1f)
        return (target.y + target.height * anchor - origin.y - origin.height * anchor)
            .coerceAtLeast(origin.height)
    }

    fun page(width: Float, height: Float, top: Float, bottom: Float, aspect: Float, wide: Boolean): PlaybackRect {
        val usable = (height - top - bottom).coerceAtLeast(1f)
        val videoWidth = if (wide) width * 0.62f else width
        val safeAspect = aspect.takeIf { it.isFinite() && it in 0.1f..10f } ?: 16f / 9f
        val videoHeight = min(videoWidth / safeAspect, usable * if (wide) 0.85f else 0.55f)
        return PlaybackRect(0f, top, videoWidth, videoHeight.coerceAtLeast(1f))
    }

    fun mini(
        width: Float,
        height: Float,
        top: Float,
        bottom: Float,
        density: Float,
        aspect: Float,
        requestedWidthDp: Float,
        corner: MiniCorner,
    ): PlaybackRect {
        val margin = 12f * density
        val usableWidth = (width - margin * 2).coerceAtLeast(1f)
        val usableHeight = (height - top - bottom - margin * 2).coerceAtLeast(1f)
        val safeAspect = aspect.takeIf { it.isFinite() && it in 0.1f..10f } ?: 16f / 9f
        val maximum = min(min(usableWidth, width * 0.62f), usableHeight * safeAspect)
        val minimum = min(144f * density, maximum)
        val videoWidth = (requestedWidthDp * density).coerceIn(minimum, maximum)
        val videoHeight = videoWidth / safeAspect
        val x = if (corner in
            listOf(MiniCorner.TopStart, MiniCorner.BottomStart)
        ) {
            margin
        } else {
            width - margin - videoWidth
        }
        val y = if (corner in
            listOf(MiniCorner.TopStart, MiniCorner.TopEnd)
        ) {
            top + margin
        } else {
            height - bottom - margin - videoHeight
        }
        return PlaybackRect(x.coerceAtLeast(0f), y.coerceAtLeast(0f), videoWidth, videoHeight)
    }

    fun constrain(rect: PlaybackRect, width: Float, height: Float, top: Float, bottom: Float) = rect.copy(
        x = rect.x.coerceIn(0f, (width - rect.width).coerceAtLeast(0f)),
        y = rect.y.coerceIn(top, (height - bottom - rect.height).coerceAtLeast(top)),
    )

    /** A dragged mini may cross the lower safe area; resting positions still avoid app controls. */
    fun dragMini(rect: PlaybackRect, width: Float, height: Float, top: Float) = rect.copy(
        x = rect.x.coerceIn(0f, (width - rect.width).coerceAtLeast(0f)),
        y = rect.y.coerceIn(top, (height + rect.height).coerceAtLeast(top)),
    )

    fun dismissMini(rect: PlaybackRect, height: Float, bottom: Float, density: Float, downwardTravel: Float): Boolean {
        if (!downwardTravel.isFinite() || !density.isFinite() || density <= 0f) return false
        return downwardTravel >= 96f * density && rect.y + rect.height * 0.35f > height - bottom
    }

    fun nearestCorner(centerX: Float, centerY: Float, width: Float, height: Float): MiniCorner = when {
        centerY < height / 2 && centerX < width / 2 -> MiniCorner.TopStart
        centerY < height / 2 -> MiniCorner.TopEnd
        centerX < width / 2 -> MiniCorner.BottomStart
        else -> MiniCorner.BottomEnd
    }

    fun settlePresentation(start: PlaybackPresentation, distance: Float, velocity: Float): PlaybackPresentation {
        if (!distance.isFinite() || !velocity.isFinite()) return start
        val advancing = distance > 0.24f || distance > 0.06f && velocity > 0.8f
        val expanding = distance < -0.24f || distance < -0.06f && velocity < -0.8f
        return when (start) {
            PlaybackPresentation.Fullscreen -> if (advancing) PlaybackPresentation.Page else start
            PlaybackPresentation.Page -> when {
                advancing -> PlaybackPresentation.Mini
                expanding -> PlaybackPresentation.Fullscreen
                else -> start
            }
            PlaybackPresentation.Mini -> start
        }
    }
}
