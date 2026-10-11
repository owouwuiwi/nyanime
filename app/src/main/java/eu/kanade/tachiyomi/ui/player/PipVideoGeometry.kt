package eu.kanade.tachiyomi.ui.player

import kotlin.math.roundToInt

/** Validated PiP dimensions, independent of Android and transient MPV properties. */
data class PipVideoGeometry(val width: Int, val height: Int) {
    companion object {
        private const val SCALE = 10_000
        private const val MAX_ASPECT = 2.39

        fun fromAspect(aspect: Double?): PipVideoGeometry? {
            if (aspect == null || !aspect.isFinite() || aspect !in (1.0 / MAX_ASPECT)..MAX_ASPECT) return null
            val size = if (aspect >= 1.0) {
                PipVideoGeometry((aspect * SCALE).roundToInt(), SCALE)
            } else {
                PipVideoGeometry(SCALE, (SCALE / aspect).roundToInt())
            }
            // Rounding must not move a boundary value outside Android's allowed range.
            return size.takeIf { it.width.toDouble() / it.height in (1.0 / MAX_ASPECT)..MAX_ASPECT }
        }
    }
}
