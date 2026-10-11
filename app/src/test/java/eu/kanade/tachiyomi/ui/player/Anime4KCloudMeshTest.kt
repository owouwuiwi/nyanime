package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Anime4KCloudMeshTest {
    @Test
    fun `mesh triangles are bounded and close without a color or geometry seam`() {
        val mesh = Anime4KCloudMesh()
        assertTrue(mesh.update(2_400f, 96f))
        assertTrue(mesh.vertices.all(Float::isFinite))
        assertEquals(0, mesh.indices.size % 3)
        assertTrue(mesh.indices.all { it.toInt() in mesh.colors.indices })
        val rings = mesh.colors.size / (Anime4KCloudGeometry.POINTS + 1)
        for (ring in 0 until rings) {
            val last = Anime4KCloudGeometry.POINTS * rings + ring
            assertEquals(mesh.colors[ring], mesh.colors[last])
            assertEquals(mesh.vertices[ring * 2], mesh.vertices[last * 2])
            assertEquals(mesh.vertices[ring * 2 + 1], mesh.vertices[last * 2 + 1])
        }
    }

    @Test
    fun `repeated animation frames reuse all buffers and retain the approved centerline`() {
        val mesh = Anime4KCloudMesh()
        val vertices = mesh.vertices
        val colors = mesh.colors
        val indices = mesh.indices
        val rings = colors.size / (Anime4KCloudGeometry.POINTS + 1)
        for (radius in listOf(.01f, 20f, 200f, 1_000f, 3_000f)) {
            assertTrue(mesh.update(radius, 96f))
            assertSame(vertices, mesh.vertices)
            assertSame(colors, mesh.colors)
            assertSame(indices, mesh.indices)
            assertTrue(vertices.all(Float::isFinite))
            for (point in 0..Anime4KCloudGeometry.POINTS) {
                val center = (point * rings + rings / 2) * 2
                assertEquals(Anime4KCloudGeometry.contour[point * 4] * radius, vertices[center])
                assertEquals(Anime4KCloudGeometry.contour[point * 4 + 1] * radius, vertices[center + 1])
            }
        }
    }

    @Test
    fun `outer feather is transparent while the bright core is opaque`() {
        val mesh = Anime4KCloudMesh()
        val rings = mesh.colors.size / (Anime4KCloudGeometry.POINTS + 1)
        for (point in 0..Anime4KCloudGeometry.POINTS) {
            assertEquals(0, mesh.colors[point * rings] ushr 24)
            assertEquals(0, mesh.colors[point * rings + rings - 1] ushr 24)
            assertEquals(255, mesh.colors[point * rings + rings / 2] ushr 24)
        }
        assertFalse(mesh.update(Float.NaN, 96f))
        assertFalse(mesh.update(1_000f, Float.POSITIVE_INFINITY))
        assertFalse(mesh.update(0f, 96f))
    }
}
