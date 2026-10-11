package eu.kanade.tachiyomi.ui.player

import kotlin.math.roundToInt

/** Pixel geometry after rotation, before deciding whether a second restoration pass is useful. */
data class Anime4KGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int,
    val targetHeight: Int,
) {
    companion object {
        fun resolve(
            sourceWidth: Int,
            sourceHeight: Int,
            rotation: Int = 0,
            displayAspect: Double? = null,
            osdWidth: Int? = null,
            osdHeight: Int? = null,
            marginLeft: Int? = null,
            marginRight: Int? = null,
            marginTop: Int? = null,
            marginBottom: Int? = null,
            surfaceWidth: Int = 0,
            surfaceHeight: Int = 0,
        ): Anime4KGeometry? {
            if (sourceWidth <= 0 || sourceHeight <= 0) return null
            val rotated = ((rotation % 360) + 360) % 360 in listOf(90, 270)
            val width = if (rotated) sourceHeight else sourceWidth
            val height = if (rotated) sourceWidth else sourceHeight
            // MPV margins can be negative when zoom/pan crops the video. Keep that actual render size.
            if (osdWidth != null &&
                osdHeight != null &&
                osdWidth > 0 &&
                osdHeight > 0 &&
                marginLeft != null &&
                marginRight != null &&
                marginTop != null &&
                marginBottom != null
            ) {
                val renderedWidth = osdWidth.toLong() - marginLeft - marginRight
                val renderedHeight = osdHeight.toLong() - marginTop - marginBottom
                if (renderedWidth in 1..Int.MAX_VALUE.toLong() && renderedHeight in 1..Int.MAX_VALUE.toLong()) {
                    return Anime4KGeometry(width, height, renderedWidth.toInt(), renderedHeight.toInt())
                }
            }
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return null
            val aspect = displayAspect?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { if (rotated) 1.0 / it else it } ?: width.toDouble() / height
            val targetWidth = minOf(surfaceWidth.toDouble(), surfaceHeight * aspect)
            val targetHeight = minOf(surfaceHeight.toDouble(), surfaceWidth / aspect)
            return Anime4KGeometry(
                width,
                height,
                targetWidth.roundToInt().coerceAtLeast(1),
                targetHeight.roundToInt().coerceAtLeast(1),
            )
        }
    }
}
