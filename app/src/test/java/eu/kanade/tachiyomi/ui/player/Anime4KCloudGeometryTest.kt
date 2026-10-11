package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class Anime4KCloudGeometryTest {
    @Test
    fun `the closed contour is finite and keeps its original organic shape`() {
        val points = Anime4KCloudGeometry.contour
        assertEquals(193 * 4, points.size)
        assertTrue(points.all(Float::isFinite))
        for (axis in 0..3) assertEquals(points[axis], points[points.size - 4 + axis])
        for (i in 0..Anime4KCloudGeometry.POINTS) {
            val angle = i * Math.PI * 2 / Anime4KCloudGeometry.POINTS
            val expected = 1.0 + .14 * sin(3 * angle) + .065 * sin(7 * angle) + .022 * cos(2 * angle)
            assertEquals(expected, hypot(points[i * 4].toDouble(), points[i * 4 + 1].toDouble()), .00001)
            assertEquals(1.0, hypot(points[i * 4 + 2].toDouble(), points[i * 4 + 3].toDouble()), .00001)
        }
    }

    @Test
    fun `the front reaches every boundary at completion for portrait landscape and external origins`() {
        for ((width, height) in listOf(2_640f to 1_080f, 1_080f to 2_640f)) {
            for ((x, y) in listOf(width * .9f to 90f, width * .5f to height * .5f, width + 80f to -50f)) {
                val reach = Anime4KCloudGeometry.reach(width, height, x, y)
                for (i in 0..100) {
                    val f = i / 100.0
                    for ((px, py) in listOf(
                        width * f to 0.0,
                        width * f to height.toDouble(),
                        0.0 to height * f,
                        width.toDouble() to height * f,
                    )) {
                        val dx = px - x
                        val dy = py - y
                        val angle = atan2(dy, dx)
                        val shape = 1.0 + .14 * sin(3 * angle) + .065 * sin(7 * angle) + .022 * cos(2 * angle)
                        assertTrue(reach * shape >= hypot(dx, dy))
                    }
                }
            }
        }
    }

    @Test
    fun `invalid geometry cannot create unbounded drawing coordinates`() {
        assertEquals(0f, Anime4KCloudGeometry.reach(0f, 100f, 0f, 0f))
        assertEquals(0f, Anime4KCloudGeometry.reach(100f, Float.NaN, 0f, 0f))
        assertEquals(0f, Anime4KCloudGeometry.reach(100f, 100f, Float.NaN, 0f))
    }
}
