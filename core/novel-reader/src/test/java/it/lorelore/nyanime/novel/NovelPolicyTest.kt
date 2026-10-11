package it.lorelore.nyanime.novel

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class NovelPolicyTest {
    @Test
    fun `namespaced active content and network hints are stripped without breaking XHTML entities`() {
        val xml =
            "<!DOCTYPE html SYSTEM 'https://outside.invalid/dtd'>" +
                "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:s='http://www.w3.org/2000/svg'>" +
                "<head><link rel='dns-prefetch' href='https://outside.invalid'/></head>" +
                "<body><s:svg><s:script>bad()</s:script></s:svg><p>A&nbsp;B&amp;C</p></body></html>"
        val bytes = NovelHtmlPolicy.sanitize(xml.toByteArray(), true)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(
            !text.contains("DOCTYPE") &&
                !text.contains("s:script") &&
                !text.contains("dns-prefetch"),
        )
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        assertEquals(
            "A\u00a0B&C",
            document
                .getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "p")
                .item(0)
                .textContent,
        )
    }

    @Test
    fun `book scripts are removed while trusted navigator origin remains allowed`() {
        val html =
            "<html><head><script>bad()</script>" +
                "<meta http-equiv='refresh' content='0;url=https://outside.invalid'></head>" +
                "<body onload='bad()'><p>Readable text</p><a href='java\nscript:bad()'>Link</a>" +
                "<iframe srcdoc='<script>bad()</script>'></iframe></body></html>"
        val safe =
            Jsoup.parse(
                NovelHtmlPolicy.sanitize(html.toByteArray(), false).toString(Charsets.UTF_8),
            )
        assertTrue(
            safe.select("script,iframe,[onload],[srcdoc],meta[http-equiv=refresh]").isEmpty(),
        )
        assertEquals("", safe.selectFirst("a")!!.attr("href"))
        assertEquals("Readable text", safe.selectFirst("p")!!.text())
        val policy = safe.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        assertTrue(policy.contains("script-src https:;"))
        assertTrue(policy.contains("connect-src 'none'"))
    }

    @Test
    fun `legacy encoding is converted consistently and structural overload is rejected`() {
        val html = "<html><head><meta charset='ISO-8859-1'></head><body>café</body></html>"
        val safe =
            NovelHtmlPolicy.sanitize(html.toByteArray(Charsets.ISO_8859_1), false)
                .toString(Charsets.UTF_8)
        assertEquals("café", Jsoup.parse(safe).body().text())
        assertThrows(IllegalArgumentException::class.java) {
            NovelHtmlPolicy.sanitize("<b/>".repeat(32001).toByteArray(), false)
        }
    }

    @Test
    fun `text encoding distinguishes UTF32 from UTF16 and handles an incomplete UTF8 probe`() {
        val utf32 =
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) +
                "Ciao".toByteArray(java.nio.charset.Charset.forName("UTF-32LE"))
        assertEquals("UTF-32LE", NovelTextEncoding.detect(utf32).charset.name())
        assertEquals(4, NovelTextEncoding.detect(utf32).bomBytes)
        assertEquals(
            Charsets.UTF_16LE,
            NovelTextEncoding.detect("A long plain text".toByteArray(Charsets.UTF_16LE)).charset,
        )
        assertEquals(
            Charsets.UTF_8,
            NovelTextEncoding.detect(
                byteArrayOf(65, 0xE2.toByte(), 0x82.toByte()),
                complete = false,
            )
                .charset,
        )
        assertEquals(
            "windows-1252",
            NovelTextEncoding.detect(
                "café".toByteArray(java.nio.charset.Charset.forName("windows-1252")),
            )
                .charset
                .name(),
        )
    }

    @Test
    fun `vector illustrations stay vectors while active content is removed`() {
        val vector =
            "<?xml-stylesheet href='https://outside.invalid/style'?>" +
                "<svg xmlns='http://www.w3.org/2000/svg' onload='bad()'>" +
                "<script>bad()</script><set attributeName='href' to='javascript:bad()'/>" +
                "<path d='M0 0 L10 10'/><text>Illustration</text></svg>"
        val result =
            NovelHtmlPolicy.sanitize(vector.toByteArray(), true, vector = true)
                .toString(Charsets.UTF_8)
        assertTrue(
            !result.contains("bad()") &&
                !result.contains("xml-stylesheet") &&
                !result.contains("<html"),
        )
        val document = Jsoup.parse(result, "", org.jsoup.parser.Parser.xmlParser())
        assertEquals(1, document.select("path").size)
        assertEquals("Illustration", document.selectFirst("text")!!.text())
    }

    @Test
    fun `search treats syntax as text and preserves original accent and emoji offsets`() {
        assertEquals(
            "\"cafe\"* AND \"OR\"* AND \"sole\"*",
            NovelSearchQuery("cafe OR \"sole\"*").expression,
        )
        val text = "🙂 Prima del caffè: café e poi ciao"
        assertEquals("café", text.substring(NovelSearchQuery("cafe").range(text)!!))
        assertEquals(
            "sole",
            "unsole sole".substring(NovelSearchQuery("sole").range("unsole sole")!!),
        )
        val combining = "🙂 unsole e cafe\u0301"
        assertEquals("cafe\u0301", combining.substring(NovelSearchQuery("cafe").range(combining)!!))
        assertNull(NovelSearchQuery("non esiste").range(text))
        assertTrue(NovelSearchQuery("%%%%_").terms.isEmpty())
        assertEquals("%東京\\_100\\%%", NovelSearchQuery("東京_100%").expression)
    }
}
