package it.lorelore.nyanime.novel

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.Parser
import java.nio.charset.Charset
import java.util.Locale

/** Applied to publication resources before Readium injects its own trusted navigation bridge. */
object NovelHtmlPolicy {
    const val MAX_HTML_BYTES = 4 * 1024 * 1024

    // Chromium rejects the underscore in Readium3.3's virtual hostname as a CSP host source.
    // HTTPS permits the trusted injected bridge; LocalContainer and Readium's asset allowlist
    // still deny every external request and every publication-owned JavaScript resource.
    // This is deliberately not the authority for authorizing a resource.
    private const val POLICY =
        "default-src 'none'; script-src https:; " +
            "style-src 'self' https: 'unsafe-inline'; " +
            "img-src 'self' https: data: blob:; font-src 'self' https: data:; " +
            "connect-src 'none'; media-src 'none'; " +
            "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"

    fun sanitize(
        bytes: ByteArray,
        xml: Boolean,
        declaredCharset: Charset? = null,
        vector: Boolean = false,
    ): ByteArray {
        require(bytes.size <= MAX_HTML_BYTES) { "Chapter exceeds its rendering budget" }
        val charset = charset(bytes, declaredCharset)
        val source = bytes.toString(charset).removePrefix("\uFEFF")
        require(source.count { it == '<' } <= 32_000) { "Chapter exceeds its structural budget" }
        val document =
            if (xml) {
                Jsoup.parse(source, "", Parser.xmlParser())
            } else {
                Jsoup.parse(source)
            }
        document.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
        if (xml) document.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        document.childNodes().filterIsInstance<DocumentType>().forEach { it.remove() }
        val forbidden =
            setOf(
                "script",
                "iframe",
                "object",
                "embed",
                "base",
                "animate",
                "animatemotion",
                "animatetransform",
                "set",
            )
        val hints =
            setOf("preconnect", "dns-prefetch", "prefetch", "prerender", "preload", "modulepreload")
        document.allElements.toList().forEach { element ->
            val name = element.normalName().substringAfterLast(':').lowercase(Locale.ROOT)
            val refresh =
                name == "meta" &&
                    element.attr("http-equiv").lowercase(Locale.ROOT) in
                    setOf("refresh", "content-security-policy")
            val hint =
                name == "link" &&
                    element.attr("rel").lowercase(Locale.ROOT).split(Regex("\\s+")).any {
                        it in hints
                    }
            if (name in forbidden || refresh || hint) element.remove()
        }
        document.select("form").forEach { it.unwrap() }
        document.childNodes().filterIsInstance<XmlDeclaration>().forEach {
            if (it.name() == "xml") it.attr("encoding", "UTF-8") else it.remove()
        }
        document.select("meta[charset]").attr("charset", "UTF-8")
        document
            .select("meta[http-equiv=content-type]")
            .attr("content", "application/xhtml+xml; charset=UTF-8")
        for (element in document.allElements) {
            val remove =
                element
                    .attributes()
                    .asList()
                    .filter { attribute ->
                        val key = attribute.key.lowercase(Locale.ROOT)
                        val value =
                            attribute.value
                                .filterNot { it.isWhitespace() || it.code < 32 }
                                .lowercase(Locale.ROOT)
                        key.substringAfterLast(':').startsWith("on") ||
                            key == "srcdoc" ||
                            key in setOf("href", "src", "xlink:href", "action", "formaction") &&
                            listOf("javascript:", "vbscript:", "file:", "content:")
                                .any(value::startsWith)
                    }
                    .map { it.key }
            remove.forEach(element::removeAttr)
        }
        if (!vector) {
            var head = document.selectFirst("head")
            if (head == null) {
                val html = document.selectFirst("html") ?: document.appendElement("html")
                head = html.prependElement("head")
            }
            head
                .prependElement("meta")
                .attr("http-equiv", "Content-Security-Policy")
                .attr("content", POLICY)
        }
        val output = document.outerHtml().toByteArray(Charsets.UTF_8)
        require(output.size <= MAX_HTML_BYTES) { "Sanitized chapter exceeds its rendering budget" }
        return output
    }

    private fun charset(bytes: ByteArray, declared: Charset?): Charset {
        fun starts(vararg values: Int) =
            bytes.size >= values.size &&
                values.indices.all { bytes[it].toInt() and 255 == values[it] }
        return when {
            starts(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8
            starts(0xFF, 0xFE) || starts(0x3C, 0, 0x3F, 0) -> Charsets.UTF_16LE
            starts(0xFE, 0xFF) || starts(0, 0x3C, 0, 0x3F) -> Charsets.UTF_16BE
            declared != null -> declared
            else -> {
                val prefix =
                    bytes.copyOfRange(0, minOf(bytes.size, 512)).toString(Charsets.US_ASCII)
                val encoding =
                    Regex(
                        "(?:encoding|charset)\\s*=\\s*[\"']?([A-Za-z0-9_-]+)",
                        RegexOption.IGNORE_CASE,
                    )
                        .find(prefix)
                        ?.groupValues
                        ?.get(1)
                encoding?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            }
        }
    }
}
