package it.lorelore.nyanime.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NovelChapterGroupsTest {
    @Test
    fun `declared labels group without parsing or sorting names and numbers`() {
        val chapters =
            listOf(
                NovelOnlineChapter("one", "Part 9", group = "Volume B"),
                NovelOnlineChapter("two", "Part 2", group = "Volume A"),
                NovelOnlineChapter("three", "Part 1", group = "Volume B"),
                NovelOnlineChapter("four", "Other", group = null),
            )
        val grouped = NovelChapterGroups.of(chapters) { it.group }
        assertEquals(listOf("Volume B", "Volume A", null), grouped.map { it.label })
        assertEquals(listOf("one", "three"), grouped.first().entries.map { it.reference })
        assertEquals(listOf("one", "two", "three", "four"), chapters.map { it.reference })
        assertEquals(chapters.first().href, grouped.first().entries.first().href)
        assertEquals("Volume A", NovelChapterGroups.initial(grouped) { it.reference == "two" })
        assertEquals("Volume B", NovelChapterGroups.initial(grouped) { false })
    }

    @Test
    fun `blank labels remain ordinary ungrouped chapters and empty lists stay empty`() {
        val groups = NovelChapterGroups.of(listOf(null, "", "  ", " Volume ")) { it }
        assertEquals(listOf(null, "Volume"), groups.map { it.label })
        assertEquals(3, groups.first().entries.size)
        assertNull(
            NovelChapterGroups.initial(
                emptyList<String>().let { NovelChapterGroups.of(it) { null } },
            ) {
                true
            },
        )
    }
}
