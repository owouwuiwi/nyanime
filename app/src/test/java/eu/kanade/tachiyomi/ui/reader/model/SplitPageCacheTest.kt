package eu.kanade.tachiyomi.ui.reader.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.items.chapter.model.Chapter

class SplitPageCacheTest {
    private fun chapter(id: Long): ReaderChapter {
        val chapter = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1, url = "/chapter-$id"))
        chapter.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0).also { it.chapter = chapter }))
        return chapter
    }

    @Test
    fun `two adjacent chapters can split the same index without mixing their images`() {
        val first = chapter(1)
        val second = chapter(2)
        val cache = SplitPageCache()
        val left = InsertPage(first.pages!!.first())
        val right = InsertPage(second.pages!!.first())
        cache.put(left)
        cache.put(right)
        assertEquals(listOf(left), cache.forChapter(first))
        assertEquals(listOf(right), cache.forChapter(second))
        cache.retainAdjacent(null, second)
        assertEquals(emptyList<InsertPage>(), cache.forChapter(first))
        assertEquals(listOf(right), cache.forChapter(second))
    }

    @Test
    fun `reloading a chapter does not reuse a split belonging to old page objects`() {
        val chapter = chapter(1)
        val cache = SplitPageCache()
        cache.put(InsertPage(chapter.pages!!.first()))
        chapter.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0).also { it.chapter = chapter }))
        assertEquals(emptyList<InsertPage>(), cache.forChapter(chapter))
    }
}
