package eu.kanade.tachiyomi.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MergeLibraryTitlesTest {
    @Test
    fun `filter media once and interleave novel pages with stable ties across media`() {
        val media = listOf("a" to "unmatched", "c" to "anime", "c" to "manga", "g" to "comic")
        val novels = listOf("x" to "last", "c" to "novel1", "b" to "first", "c" to "novel2")
        val visited = mutableListOf<String>()
        val result = mergeLibraryTitles(media, novels) {
            visited += it
            it != "unmatched"
        }
        assertEquals(listOf("first", "anime", "manga", "novel1", "novel2", "comic", "last"), result)
        assertEquals(media.map { it.second }, visited)
    }

    @Test
    fun `every partial novel page remains in title order even with no matching media`() {
        val media = listOf("b" to 1, "e" to 2)
        val novels = listOf("z" to 0, "c" to 1, "á" to 2, "aa" to 3)
        for (size in 0..novels.size) {
            val page = novels.take(size)
            assertEquals(page.sortedBy { it.first }.map { it.second }, mergeLibraryTitles(media, page) { false })
        }
    }
}
