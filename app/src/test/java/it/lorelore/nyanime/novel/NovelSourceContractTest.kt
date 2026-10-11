package it.lorelore.nyanime.novel

import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelContent
import it.lorelore.nyanime.source.novel.NovelEntry
import it.lorelore.nyanime.source.novel.NovelResourceRequest
import it.lorelore.nyanime.source.novel.NovelSearchPage
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NovelSourceContractTest {
    @Test
    fun `opaque cursors and source references survive without guessed chapter numbers`() {
        val request =
            NovelSearchRequest("A title", "cursor:not-a-page", mapOf("genre" to "fiction"))
        assertEquals("cursor:not-a-page", request.cursor)
        val chapter = NovelChapter("/chapter/special", "Special chapter")
        assertEquals(null, chapter.number)
        assertEquals(null, chapter.publishedAt)
        val answer = NovelSearchPage(listOf(NovelEntry("/exact/book", "A title")), "after:second")
        assertEquals("after:second", answer.nextCursor)
    }

    @Test
    fun `unbounded metadata and invalid provider numbers are rejected at the boundary`() {
        assertThrows(IllegalArgumentException::class.java) { NovelSearchRequest("x".repeat(2049)) }
        assertThrows(IllegalArgumentException::class.java) {
            NovelSearchRequest("query", "x".repeat(8193))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelSearchPage(List(1001) { NovelEntry("/book", "Book") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelChapter("/chapter", "Chapter", Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelChapter("/chapter", "Chapter", publishedAt = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelContent("/chapter", "Chapter", "x".repeat(4 * 1024 * 1024 + 1))
        }
    }

    @Test
    fun `resource headers cannot inject a second request line`() {
        assertThrows(IllegalArgumentException::class.java) {
            NovelResourceRequest(
                "https://fictional.invalid/image",
                mapOf("X-Test" to "safe\r\nInjected: unsafe"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelResourceRequest("https://fictional.invalid/image", mapOf("Bad Header" to "value"))
        }
        val valid =
            NovelResourceRequest("https://fictional.invalid/image", mapOf("Accept" to "image/*"))
        assertEquals("image/*", valid.headers["Accept"])
    }
}
