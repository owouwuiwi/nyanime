package it.lorelore.nyanime.novel

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelOnlinePolicyTest {
    private val one = NovelOnlineChapter("opaque/one", "First chapter")
    private val two = NovelOnlineChapter("opaque/two", "Second chapter")

    @Test
    fun `chapter references survive reorder rename and appended chapters`() {
        assertEquals(one.href, one.copy(title = "A new label", number = 99.0).href)
        assertNotEquals(one.href, two.href)
        val origin = NovelOrigin(13, "book")
        assertNotEquals(
            NovelOnlineIdentity.edition(origin),
            NovelOnlineIdentity.edition(origin.copy(sourceId = 14)),
        )
        val doc =
            NovelOnlineDocument(
                bookId = NovelIdentity.remote(13, "book"),
                origin = origin,
                chapters = listOf(two, one),
                updatedAt = 0,
            )
        assertEquals(listOf(two.href, one.href), doc.chapters.map { it.href })
        assertThrows(IllegalArgumentException::class.java) { doc.copy(chapters = listOf(one, one)) }
    }

    @Test
    fun `only explicitly declared images and exact chapter links become navigable`() {
        val response =
            NovelOnlineBody(
                """<p>Readable</p><img src="cover-ref"><img src="https://unlisted.invalid/tracker">
            <a href="opaque/two#detail">Next</a><a href="https://unlisted.invalid/">External</a>
            <img srcset="https://unlisted.invalid/one 2x" src="unused"><div style="background:url(https://unlisted.invalid/two)"></div>""",
                html = true,
                resources = mapOf("cover-ref" to "source-image", "not-used" to "extra"),
            )
        val prepared = NovelOnlineHtml.prepare(one, response, listOf(one, two))
        val html = Jsoup.parse(prepared.bytes.toString(Charsets.UTF_8))
        assertEquals(
            "../" + NovelOnlineIdentity.resourceHref("source-image"),
            html.selectFirst("img")!!.attr("src"),
        )
        assertEquals(
            mapOf(NovelOnlineIdentity.resourceHref("source-image") to "source-image"),
            prepared.resources,
        )
        assertEquals("../${two.href}#detail", html.selectFirst("a")!!.attr("href"))
        assertEquals(1, html.select("[src]").size)
        assertEquals(1, html.select("[href]").size)
        assertTrue(html.select("[srcset],[style]").isEmpty())
    }

    @Test
    fun `scripts forms events and publication owned styles cannot reach the reader`() {
        val response =
            NovelOnlineBody(
                """<html><head><script>steal()</script><base href="https://outside.invalid/"></head>
            <body onload="steal()"><script src="https://outside.invalid/code"></script><iframe srcdoc="danger"></iframe>
            <form action="https://outside.invalid/"><input type="password"><button>Send</button></form><p>Keep this</p></body></html>""",
                true,
            )
        val html =
            Jsoup.parse(
                NovelOnlineHtml.prepare(one, response, listOf(one)).bytes.toString(Charsets.UTF_8),
            )
        assertTrue(html.select("script,iframe,base,form,input,button,[onload]").isEmpty())
        assertEquals("Keep this", html.body().text())
        assertTrue(html.select("meta[http-equiv=Content-Security-Policy]").isNotEmpty())
    }

    @Test
    fun `plain text retains Unicode whitespace and literal markup`() {
        val text = "Città e 日本語\n\n<chapter>literal & safe</chapter>"
        val prepared = NovelOnlineHtml.prepare(one, NovelOnlineBody(text, false), listOf(one))
        val html = Jsoup.parse(prepared.bytes.toString(Charsets.UTF_8))
        assertTrue(html.select("chapter").isEmpty())
        assertEquals(text, html.selectFirst(".nyanime-plain-text")!!.wholeText())
        assertThrows(IllegalArgumentException::class.java) {
            NovelOnlineHtml.prepare(
                one,
                NovelOnlineBody("<script>only code</script>", true),
                listOf(one),
            )
        }
    }
}
