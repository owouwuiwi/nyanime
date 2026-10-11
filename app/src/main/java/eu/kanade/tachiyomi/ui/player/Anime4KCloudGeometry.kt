package eu.kanade.tachiyomi.ui.player

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Fixed ink contour and outward normals, computed once rather than tessellated on every frame. */
internal object Anime4KCloudGeometry {
    const val POINTS = 192
    private fun shape(angle: Double) = 1.0 + .14 * sin(3 * angle) + .065 * sin(7 * angle) + .022 * cos(2 * angle)

    /** x, y, outward-normal x/y, including the repeated endpoint for a seamless strip. */
    val contour = FloatArray((POINTS + 1) * 4).also { result ->
        for (i in 0 until POINTS) {
            val angle = i * Math.PI * 2 / POINTS
            val scale = shape(angle)
            val derivative = .42 * cos(3 * angle) + .455 * cos(7 * angle) - .044 * sin(2 * angle)
            val dx = derivative * cos(angle) - scale * sin(angle)
            val dy = derivative * sin(angle) + scale * cos(angle)
            val length = hypot(dx, dy)
            result[i * 4] = (cos(angle) * scale).toFloat()
            result[i * 4 + 1] = (sin(angle) * scale).toFloat()
            result[i * 4 + 2] = (dy / length).toFloat()
            result[i * 4 + 3] = (-dx / length).toFloat()
        }
        result.copyInto(result, POINTS * 4, 0, 4)
    }

    fun reach(width: Float, height: Float, originX: Float, originY: Float): Float {
        if (width <= 0 ||
            height <= 0 ||
            !width.isFinite() ||
            !height.isFinite() ||
            !originX.isFinite() ||
            !originY.isFinite()
        ) {
            return 0f
        }
        var reach = 0.0
        fun include(x: Double, y: Double) {
            val dx = x - originX
            val dy = y - originY
            reach = max(reach, hypot(dx, dy) / shape(atan2(dy, dx)))
        }
        for (i in 0..32) {
            val fraction = i / 32.0
            include(width * fraction, 0.0)
            include(width * fraction, height.toDouble())
            include(0.0, height * fraction)
            include(width.toDouble(), height * fraction)
        }
        return (reach * 1.02).toFloat()
    }
}
