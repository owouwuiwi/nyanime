package eu.kanade.presentation.entries.details

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DetailPartsTest {
    private fun part(id: Long, number: String = id.toString(), title: String = "Item $id") =
        DetailPart(id, number, title, "", completed = false, bookmarked = false, offline = false, fresh = false)

    private val entries = (1L..47L).map { part(it) }

    @Test
    fun `index keeps every ID including specials and duplicate numbers`() {
        val parts = entries + part(501, "2.5") + part(502, "—") + part(503, "2.5")
        val pages = (0 until DetailParts.groupCount(parts.size)).flatMap {
            DetailParts.visible(parts, DetailFilter.INDEX, "", it, null)
        }
        assertEquals(parts, pages)
        assertEquals(parts.size, pages.map { it.id }.toSet().size)
    }

    @Test
    fun `search covers all index pages and retains offline restrictions`() {
        assertEquals(listOf(47L), DetailParts.visible(entries, DetailFilter.INDEX, "47", 0, 1).map { it.id })
        assertEquals(emptyList<DetailPart>(), DetailParts.visible(entries, DetailFilter.OFFLINE, "47", 0, 1))
        val downloaded = entries.map { if (it.id == 47L) it.copy(offline = true) else it }
        assertEquals(listOf(47L), DetailParts.visible(downloaded, DetailFilter.OFFLINE, "47", 0, 1).map { it.id })
        assertEquals(
            listOf(501L),
            DetailParts.visible(
                entries + part(501, title = "A special chapter"),
                DetailFilter.INDEX,
                "SPECIAL",
                0,
                null,
            ).map {
                it.id
            },
        )
    }

    @Test
    fun `unread does not mean newly released`() {
        assertEquals(emptyList<DetailPart>(), DetailParts.visible(entries, DetailFilter.NEW, "", 0, null))
        val parts = entries.map {
            if (it.id == 2L ||
                it.id == 3L
            ) {
                it.copy(fresh = true, completed = it.id == 3L)
            } else {
                it
            }
        }
        assertEquals(listOf(2L), DetailParts.visible(parts, DetailFilter.NEW, "", 0, null).map { it.id })
    }

    @Test
    fun `default list is complete and preserves both native orderings`() {
        val large = (1L..1501L).map { part(it) }
        assertEquals(large, DetailParts.search(large, ""))
        assertEquals(large.reversed(), DetailParts.search(large.reversed(), "  "))
    }

    @Test
    fun `empty and shrinking lists clamp ranges safely`() {
        assertEquals(1, DetailParts.groupCount(0))
        assertEquals(emptyList<DetailPart>(), DetailParts.search(emptyList(), "123"))
        assertEquals(entries.take(20), DetailParts.visible(entries, DetailFilter.INDEX, "", -4, null))
        assertEquals(entries.drop(40), DetailParts.visible(entries, DetailFilter.INDEX, "", 500, null))
        assertEquals(2, DetailParts.groupFor(entries, 47))
    }

    @Test
    fun `selection across pages adds only explicitly visible IDs`() {
        val first = DetailParts.visible(entries, DetailFilter.INDEX, "", 0, null)
        val second = DetailParts.visible(entries, DetailFilter.INDEX, "", 1, null)
        val selected = DetailParts.selectVisible(DetailParts.selectVisible(emptySet(), first), second)
        assertEquals((1L..40L).toSet(), selected)
        assertFalse(47L in selected)
    }

    @Test
    fun `long press ranges use visible identities and tolerate missing anchors`() {
        val visible = listOf(part(300, "4.5"), part(200, "4.5"), part(100, "—"))
        assertEquals(setOf(300L, 200L, 100L), DetailParts.range(visible, 100, 300))
        assertEquals(setOf(200L), DetailParts.range(visible, 1, 200))
        assertEquals(emptySet<Long>(), DetailParts.range(visible, 1, 2))
    }

    @Test
    fun `from here preserves user sorting and focuses the actual resume item`() {
        val descending = entries.reversed()
        assertEquals(
            listOf(23L, 22L, 21L, 20L, 19L),
            DetailParts.visible(descending, DetailFilter.CONTINUE, "", 0, 23).map {
                it.id
            },
        )
    }

    @Test
    fun `offline and bookmarks do not depend on completion`() {
        val parts = listOf(part(1).copy(offline = true, completed = true), part(2).copy(bookmarked = true))
        assertEquals(listOf(1L), DetailParts.visible(parts, DetailFilter.OFFLINE, "", 0, null).map { it.id })
        assertEquals(listOf(2L), DetailParts.visible(parts, DetailFilter.BOOKMARKS, "", 0, null).map { it.id })
    }

    @Test
    fun `inline search reaches the final item and keeps fractional duplicates`() {
        val parts = entries + part(501, "2.5") + part(502, "2.5") + part(503, "—", "Bonus story")
        assertEquals(listOf(47L), DetailParts.search(parts, "47").map { it.id })
        assertEquals(listOf(501L, 502L), DetailParts.search(parts, "2.5").map { it.id })
        assertEquals(listOf(503L), DetailParts.search(parts, " BONUS ").map { it.id })
        assertEquals(emptyList<DetailPart>(), DetailParts.search(parts, "absent"))
    }

    @Test
    fun `jump targets use identities after search and native filtering`() {
        val filtered = entries.filterNot { it.id < 20 }
        assertEquals(DetailListLayout.FIRST_ROW + 3, DetailListLayout.rowIndex(filtered, 23))
        assertEquals(DetailListLayout.FIRST_ROW, DetailListLayout.rowIndex(DetailParts.search(filtered, "47"), 47))
        assertEquals(null, DetailListLayout.rowIndex(filtered, 3))
        assertEquals(null, DetailListLayout.rowIndex(filtered, null))
    }

    @Test
    fun `group shortcuts cover the full list without hiding later items`() {
        val large = (1L..1501L).map { part(it) }
        assertEquals(1L, DetailListLayout.groupTarget(large, 0))
        assertEquals(21L, DetailListLayout.groupTarget(large, 1))
        assertEquals(1501L, DetailListLayout.groupTarget(large, 75))
        assertEquals(1501L, DetailListLayout.groupTarget(large, 1000))
        assertEquals(1L, DetailListLayout.groupTarget(large, -1))
        assertEquals(null, DetailListLayout.groupTarget(emptyList(), 0))
        assertEquals(DetailListLayout.FIRST_ROW + 1500, DetailListLayout.rowIndex(large, 1501))
        assertEquals(1501, DetailParts.search(large, "").size)
    }

    @Test
    fun `season navigation accounts for columns and preserves fractional IDs`() {
        val parts = listOf(part(700, "1"), part(100, "1.5"), part(300, "2"))
        assertEquals(DetailListLayout.FIRST_ROW, DetailListLayout.rowIndex(parts, 100, 2))
        assertEquals(DetailListLayout.FIRST_ROW + 1, DetailListLayout.rowIndex(parts, 300, 2))
        assertEquals(DetailListLayout.FIRST_ROW + 2, DetailListLayout.rowIndex(parts, 300, 0))
    }

    @Test
    fun `short search reserves its viewport before rows are measured`() {
        val keys = listOf("part:1", "part:2")
        assertEquals(260, DetailListLayout.viewportFillHeight(500, 100, 20, 0, 60, keys, emptyMap()))
        assertEquals(240, DetailListLayout.viewportFillHeight(500, 100, 20, 0, 60, keys, mapOf("part:1" to 80)))
    }

    @Test
    fun `measured search rows use exactly the remaining viewport without extra scrolling`() {
        val keys = listOf("part:1", "part:2")
        assertEquals(
            160,
            DetailListLayout.viewportFillHeight(500, 100, 20, 0, 60, keys, mapOf("part:1" to 80, "part:2" to 140)),
        )
        assertEquals(
            0,
            DetailListLayout.viewportFillHeight(500, 100, 20, 0, 60, keys, mapOf("part:1" to 400, "part:2" to 140)),
        )
    }

    @Test
    fun `empty results retain the viewport and respect keyboard size and padding`() {
        val keys = listOf(DetailListLayout.EMPTY_ROW)
        val measured = mapOf(DetailListLayout.EMPTY_ROW to 100)
        assertEquals(250, DetailListLayout.viewportFillHeight(500, 100, 20, 30, 60, keys, measured))
        assertEquals(50, DetailListLayout.viewportFillHeight(300, 100, 20, 30, 60, keys, measured))
        assertEquals(0, DetailListLayout.viewportFillHeight(200, 100, 20, 30, 60, keys, measured))
    }

    @Test
    fun `keyboard padding and short result footer preserve total content height together`() {
        val rows = listOf("part:1")
        val measured = mapOf("part:1" to 100)
        for (padding in listOf(30, 130, 230)) {
            val footer = DetailListLayout.viewportFillHeight(500, 100, 20, padding, 60, rows, measured)
            assertEquals(500, 100 + 20 + padding + 100 + footer)
        }
    }

    @Test
    fun `long results and extreme metrics never create an overflowing footer`() {
        val keys = (1..1501).map { "part:$it" }
        assertEquals(0, DetailListLayout.viewportFillHeight(1000, 100, 0, 0, 60, keys, emptyMap()))
        assertEquals(0, DetailListLayout.viewportFillHeight(Int.MAX_VALUE, 100, 0, 0, Int.MAX_VALUE, keys, emptyMap()))
        assertEquals(0, DetailListLayout.viewportFillHeight(0, 100, 0, 0, 60, listOf("empty"), emptyMap()))
    }
}
