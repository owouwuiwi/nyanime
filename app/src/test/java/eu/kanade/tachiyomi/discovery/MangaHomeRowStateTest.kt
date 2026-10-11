package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomePage
import eu.kanade.tachiyomi.ui.discovery.manga.MangaHomeRowState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.manga.model.Manga

class MangaHomeRowStateTest {
    @Test
    fun `failed refresh retains the last page and remains retryable`() {
        val previous = MangaHomePage(listOf(item(1)), true)
        val row = MangaHomeRowState(previous, 2).complete(null, 1, "Connection unavailable")
        assertEquals(previous, row.page)
        assertEquals(2, row.loadedPages)
        assertEquals("Connection unavailable", row.error)
        assertFalse(row.loading)
    }

    @Test
    fun `initial failure is never represented as a successful empty page`() {
        val row = MangaHomeRowState().complete(null, 1, "Request failed")
        assertNull(row.page)
        assertEquals("Request failed", row.error)
        assertFalse(row.loading)
        val recovered = row.complete(MangaHomePage(emptyList(), false), 1)
        assertEquals(emptyList<MangaHomeItem>(), recovered.page!!.items)
        assertNull(recovered.error)
    }

    @Test
    fun `pagination keeps previous items and accepts enriched replacements without losing partial errors`() {
        val row = MangaHomeRowState(MangaHomePage(listOf(item(1)), true), 1)
            .complete(MangaHomePage(listOf(item(2)), true), 2, "One provider unavailable")
        val enriched = row.complete(MangaHomePage(listOf(item(2).copy(sourceTitle = "Enriched")), true), 2, row.error)
        assertEquals(listOf(1L, 2L), enriched.page!!.items.map { it.manga.id })
        assertEquals("Enriched", enriched.page.items.last().sourceTitle)
        assertEquals("One provider unavailable", enriched.error)
    }

    private fun item(id: Long) = MangaHomeItem(Manga.create().copy(id = id, source = 42, url = "/item/$id"))
}
