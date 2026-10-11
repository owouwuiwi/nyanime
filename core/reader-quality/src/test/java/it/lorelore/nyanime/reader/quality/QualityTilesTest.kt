package it.lorelore.nyanime.reader.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QualityTilesTest {
    @Test
    fun `sampled narrow edges retain at least one output pixel for every backend`() {
        for (engine in QualityEngineId.entries.filter { it != QualityEngineId.OFF }) {
            for (sample in listOf(1, 2, 4, 8, 16, 32)) {
                val tiles = QualityTiles.visible(385, 769, QualityRegion(0, 0, 385, 769), sample, engine = engine)
                for (tile in tiles) {
                    assertTrue(tile.input.width <= 256 * sample && tile.input.height <= 256 * sample)
                    for (scale in 1..2) {
                        val width = (tile.input.width + sample - 1) / sample * scale
                        val height = (tile.input.height + sample - 1) / sample * scale
                        val crop = tile.sampledCrop(sample, scale, width, height)
                        assertTrue(crop.width > 0 && crop.height > 0)
                        assertTrue(crop.right <= width && crop.bottom <= height)
                    }
                }
            }
        }
    }

    @Test
    fun `irregular pages have no gaps or overlapping interiors`() {
        for ((width, height) in listOf(1 to 1, 191 to 193, 411 to 603, 768 to 1152)) {
            val tiles = QualityTiles.visible(width, height, QualityRegion(0, 0, width, height), prefetch = 0)
            assertEquals(width.toLong() * height, tiles.sumOf { it.content.area })
            tiles.forEachIndexed { index, tile ->
                tiles.drop(index + 1).forEach { other ->
                    val a = tile.content
                    val b = other.content
                    assertFalse(a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
                }
            }
        }
    }

    @Test
    fun `halos are clamped and removed from the output`() {
        val tiles = QualityTiles.visible(411, 603, QualityRegion(0, 0, 411, 603), prefetch = 0)
        tiles.forEach { tile ->
            assertTrue(tile.input.left <= tile.content.left && tile.input.top <= tile.content.top)
            assertTrue(tile.input.right >= tile.content.right && tile.input.bottom >= tile.content.bottom)
            assertTrue(tile.input.width <= QualityTiles.EDGE + QualityTiles.HALO * 2)
            val crop = tile.outputCrop(2)
            assertEquals(tile.content.width * 2, crop.width)
            assertEquals(tile.content.height * 2, crop.height)
            assertTrue(crop.left >= 0 && crop.top >= 0)
            assertTrue(crop.right <= tile.input.width * 2 && crop.bottom <= tile.input.height * 2)
        }
    }

    @Test
    fun `visible tiles precede bounded prefetch`() {
        val viewport = QualityRegion(384, 384, 768, 768)
        val tiles = QualityTiles.visible(1920, 1920, viewport)
        assertEquals(6, tiles.size)
        tiles.take(4).forEach {
            assertTrue(it.content.left >= 384 && it.content.right <= 768)
            assertTrue(it.content.top >= 384 && it.content.bottom <= 768)
        }
    }

    @Test
    fun `long webtoon only plans the visible portion`() {
        val tiles = QualityTiles.visible(1080, 800000, QualityRegion(0, 450000, 1080, 451200))
        assertTrue(tiles.size <= QualityTiles.MAX_VISIBLE + 2)
        assertTrue(tiles.all { it.input.top >= 449000 && it.input.bottom <= 452000 })
    }

    @Test
    fun `malformed or huge windows fail before allocating their grid`() {
        assertThrows(IllegalArgumentException::class.java) {
            QualityTiles.visible(Int.MAX_VALUE, Int.MAX_VALUE, QualityRegion(0, 0, Int.MAX_VALUE, Int.MAX_VALUE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QualityTiles.visible(100, 100, QualityRegion(0, 0, 101, 100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QualityTiles.visible(100, 100, QualityRegion(0, 0, 100, 100), sample = 3)
        }
    }

    @Test
    fun `sampling is bounded and rejects unknown geometry`() {
        assertEquals(1, QualityTiles.sample(1f, 1000, 1000))
        assertEquals(2, QualityTiles.sample(.25f, 1000, 1000))
        assertEquals(32, QualityTiles.sample(.0001f, 1000, 1000))
        assertThrows(IllegalArgumentException::class.java) { QualityTiles.sample(Float.NaN, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { QualityTiles.sample(0f, 1, 1) }
    }

    @Test
    fun `cache identity separates revisions regions models and treatments`() {
        val key = QualityTileKey("image-a", QualityRegion(0, 0, 192, 192), 1, QualityEngineId.ANIME4K, "shader-a")
        assertNotEquals(key, key.copy(imageSha256 = "image-b"))
        assertNotEquals(key, key.copy(region = QualityRegion(192, 0, 384, 192)))
        assertNotEquals(key, key.copy(sample = 2))
        assertNotEquals(key, key.copy(modelSha256 = "shader-b"))
        assertNotEquals(key, key.copy(pipelineVersion = key.pipelineVersion + 1))
        assertNotEquals(key, key.copy(outputScale = 1))
    }
}
