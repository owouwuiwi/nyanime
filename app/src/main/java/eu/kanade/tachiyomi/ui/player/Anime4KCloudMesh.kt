package eu.kanade.tachiyomi.ui.player

import kotlin.math.min
import kotlin.math.roundToInt

/** One feathered triangle strip, with reusable buffers and no overlapping glow passes. */
internal class Anime4KCloudMesh {
    private val offsets =
        floatArrayOf(-1f, -.75f, -.5f, -.25f, -.1f, -.04f, -.028f, 0f, .028f, .04f, .1f, .25f, .5f, .75f, 1f)
    private val opacity =
        floatArrayOf(0f, .035f, .075f, .14f, .25f, .55f, .95f, 1f, .95f, .55f, .25f, .14f, .075f, .035f, 0f)
    private val rings = offsets.size
    val bandSegments get() = rings - 1
    val vertices = FloatArray((Anime4KCloudGeometry.POINTS + 1) * rings * 2)
    val colors = IntArray(vertices.size / 2)
    val indices = ShortArray(Anime4KCloudGeometry.POINTS * (rings - 1) * 6)

    init {
        val stops = intArrayOf(0xFF73E0FF.toInt(), 0xFF8A9FFF.toInt(), 0xFFC6A8FF.toInt(), 0xFF73E0FF.toInt())
        for (point in 0..Anime4KCloudGeometry.POINTS) {
            val position = point.toFloat() / Anime4KCloudGeometry.POINTS * 3
            val stop = position.toInt().coerceAtMost(2)
            val color = mix(stops[stop], stops[stop + 1], position - stop)
            for (ring in offsets.indices) {
                val bright = if (ring in 6..8) mix(color, 0xFFDFF6FF.toInt(), .85f) else color
                colors[point * rings + ring] = (bright and 0x00FFFFFF) or ((opacity[ring] * 255).roundToInt() shl 24)
            }
        }
        var cursor = 0
        for (point in 0 until Anime4KCloudGeometry.POINTS) {
            for (ring in 0 until rings - 1) {
                val start = point * rings + ring
                indices[cursor++] = start.toShort()
                indices[cursor++] = (start + rings).toShort()
                indices[cursor++] = (start + 1).toShort()
                indices[cursor++] = (start + 1).toShort()
                indices[cursor++] = (start + rings).toShort()
                indices[cursor++] = (start + rings + 1).toShort()
            }
        }
    }

    fun update(radius: Float, maxHalfWidth: Float): Boolean {
        if (!radius.isFinite() || radius <= 0 || !maxHalfWidth.isFinite() || maxHalfWidth <= 0) return false
        // Keep the band narrow at the origin so inward normals cannot fold across it.
        val width = min(maxHalfWidth, radius * .08f)
        val contour = Anime4KCloudGeometry.contour
        var cursor = 0
        for (point in 0..Anime4KCloudGeometry.POINTS) {
            val index = point * 4
            val x = contour[index] * radius
            val y = contour[index + 1] * radius
            for (offset in offsets) {
                val distance = width * offset
                vertices[cursor++] = x + contour[index + 2] * distance
                vertices[cursor++] = y + contour[index + 3] * distance
            }
        }
        return true
    }

    private fun mix(first: Int, second: Int, fraction: Float): Int {
        var result = 0xFF000000.toInt()
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (first ushr shift) and 255
            val b = (second ushr shift) and 255
            result = result or ((a + (b - a) * fraction).roundToInt().coerceIn(0, 255) shl shift)
        }
        return result
    }
}
