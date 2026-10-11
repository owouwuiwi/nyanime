package it.lorelore.nyanime.novel

import it.lorelore.nyanime.novel.source.NovelSearchPager
import it.lorelore.nyanime.novel.source.NovelSourceRegistration
import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelContent
import it.lorelore.nyanime.source.novel.NovelDetails
import it.lorelore.nyanime.source.novel.NovelEntry
import it.lorelore.nyanime.source.novel.NovelSearchPage
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import it.lorelore.nyanime.source.novel.NovelSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class NovelSearchPagerTest {
    private class Source : NovelSource {
        override val id = 11L
        override val name = "Fictional"
        override val lang = "all"
        val requests = mutableListOf<NovelSearchRequest>()
        var fail = false

        override suspend fun search(request: NovelSearchRequest): NovelSearchPage {
            requests += request
            if (fail) throw IOException("Temporary")
            return NovelSearchPage(
                listOf(NovelEntry("book:" + request.query, "Work")),
                "next:" + request.query,
            )
        }

        override suspend fun details(reference: String) =
            NovelDetails(NovelEntry(reference, "Work"))

        override suspend fun chapters(reference: String) = emptyList<NovelChapter>()

        override suspend fun content(chapterReference: String) =
            NovelContent(chapterReference, "", "Text")
    }

    @Test
    fun `recovery queries retain independent opaque cursors and failed pages can retry`() =
        runBlocking<Unit> {
            val source = Source()
            val registration = NovelSourceRegistration(source, "fictional", "one")
            val pager = NovelSearchPager { registration }
            pager.fetch(11, "one", "original", 1)
            pager.fetch(11, "one", "alternative", 1)
            source.fail = true
            assertThrows(IOException::class.java) {
                runBlocking { pager.fetch(11, "one", "original", 2) }
            }
            source.fail = false
            assertEquals(null, pager.fetch(11, "one", "original", 2).nextCursor)
            assertEquals(null, pager.fetch(11, "one", "alternative", 2).nextCursor)
            assertEquals(
                listOf(null, null, "next:original", "next:original", "next:alternative"),
                source.requests.map { it.cursor },
            )
        }

    @Test
    fun `a revision change rejects its late page and does not advance the cursor`() {
        val source = Source()
        val registration = NovelSourceRegistration(source, "fictional", "one")
        var count = 0
        val pager = NovelSearchPager {
            registration.copy(revision = if (++count == 1) "one" else "two")
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { pager.fetch(11, "one", "query", 1) }
        }
        assertEquals(1, source.requests.size)
    }
}
