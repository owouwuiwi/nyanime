package eu.kanade.tachiyomi.ui.reader.spread

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpreadGroupingTest {
    private fun pairs(count: Int, offset: Int) = SpreadGrouping.group((1..count).toList(), offset).map { it.pages }

    @Test fun `zero offset pairs all original pages`() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), pairs(5, 0))
    }

    @Test fun `offset preserves leading singles and pairs the remainder`() {
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5)), pairs(5, 1))
        assertEquals(listOf(listOf(1), listOf(2), listOf(3), listOf(4, 5)), pairs(5, 3))
    }

    @Test fun `empty one-page and excessive offsets remain valid`() {
        assertTrue(pairs(0, 0).isEmpty())
        assertEquals(listOf(listOf(1)), pairs(1, 0))
        assertEquals(listOf(listOf(1), listOf(2)), pairs(2, 20))
        assertEquals(pairs(4, 0), pairs(4, -4))
    }

    @Test fun `wide pages never get paired and no originals disappear`() {
        val input = (1..8).toList()
        val groups = SpreadGrouping.group(input, 0) { it == 2 || it == 5 }
        assertEquals(
            listOf(listOf(1), listOf(2), listOf(3, 4), listOf(5), listOf(6, 7), listOf(8)),
            groups.map {
                it.pages
            },
        )
        assertEquals(input, groups.flatMap { it.pages })
    }

    @Test fun `rtl reverses presentation without changing logical progress`() {
        val spread = SpreadGrouping.group(listOf(12, 13), 0).single()
        assertEquals(listOf(13, 12), spread.pages.reversed())
        assertEquals(13, SpreadGrouping.progress(spread) { true })
    }

    @Test fun `all short-chapter geometries preserve every original exactly once`() {
        val originals = (0 until 5).toList()
        for (mask in 0 until 32) {
            val standalone: (Int) -> Boolean = { mask and (1 shl it) != 0 }
            for (offset in 0..6) {
                val grouped = SpreadGrouping.group(originals, offset, standalone)
                assertEquals(originals, grouped.flatMap { it.pages }, "mask=$mask, offset=$offset")
                grouped.filter { it.pages.size == 2 }.forEach { pair ->
                    assertTrue(pair.pages.first() >= offset)
                    assertFalse(pair.pages.any(standalone))
                }
            }
        }
    }

    @Test fun `unavailable partner cannot complete the chapter`() {
        val spread = Spread(listOf(27, 28))
        assertEquals(27, SpreadGrouping.progress(spread) { it == 27 })
        assertNull(SpreadGrouping.progress(spread) { it == 28 })
        assertEquals(28, SpreadGrouping.progress(spread) { true })
    }

    @Test fun `geometry identifies panoramas and long strips without overflow`() {
        assertTrue(SpreadGrouping.standalone(2000, 1200))
        assertTrue(SpreadGrouping.standalone(1000, 4000))
        assertFalse(SpreadGrouping.standalone(1200, 1800))
        assertFalse(SpreadGrouping.standalone(0, 0))
        assertFalse(SpreadGrouping.standalone(Int.MAX_VALUE / 2, Int.MAX_VALUE))
    }

    @Test fun `portable settings preserve chapter overrides`() {
        val record = SpreadRecord(42, "/title/example", SpreadMode.VERTICAL, 1, mapOf("/chapter/a" to 3), 10)
        val restored = Json.decodeFromString<SpreadRecord>(Json.encodeToString(record))
        assertEquals(record, restored)
        assertEquals(3, restored.offsetFor("/chapter/a"))
        assertEquals(1, restored.offsetFor("/chapter/b"))
        assertTrue(restored.valid())
        assertFalse(restored.copy(offset = -1).valid())
        assertFalse(restored.copy(chapterOffsets = mapOf("" to 1)).valid())
    }
}
