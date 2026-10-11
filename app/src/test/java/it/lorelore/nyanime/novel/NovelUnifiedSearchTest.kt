package it.lorelore.nyanime.novel

import eu.kanade.tachiyomi.ui.search.UnifiedSearchCards
import eu.kanade.tachiyomi.ui.search.UnifiedSearchEntry
import it.lorelore.nyanime.source.novel.NovelEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.search.SearchMedium

class NovelUnifiedSearchTest {
    @Test
    fun `novel results preserve source references without fabricating manga database ids`() {
        val entry =
            NovelEntry(
                "opaque:chapter-index",
                "Fictional work",
                alternativeTitles = listOf("Alternative"),
            )
        val novel = UnifiedSearchEntry.novel(31, entry, "novel", "Novels")
        assertEquals(SearchMedium.NOVEL, novel.medium)
        assertEquals(entry, novel.novel)
        assertEquals(entry.reference, novel.url)
        assertTrue(novel.targets.isEmpty())
        assertEquals(null, novel.manga)
        assertEquals(null, novel.anime)
        assertEquals(entry.alternativeTitles, novel.aliases)
    }

    @Test
    fun `a matching title never merges novels with manga or unverified other providers`() {
        val one = UnifiedSearchEntry.novel(31, NovelEntry("work", "Same title"), "novel", "Novels")
        val another =
            UnifiedSearchEntry.novel(32, NovelEntry("work", "Same title"), "novel", "Novels")
        val manga = one.copy(key = "manga:31:work", medium = SearchMedium.MANGA, novel = null)
        assertEquals(3, UnifiedSearchCards.merge(listOf(one, another, manga)).size)
        assertEquals(1, UnifiedSearchCards.merge(listOf(one, one)).size)
        assertNotEquals(one.key, another.key)
    }
}
