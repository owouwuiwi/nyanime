package it.lorelore.nyanime.novel

import java.io.OutputStream
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds bounded reflowable resources; it never reads an entire TXT into memory. */
object TextPublicationCompiler {
    const val CHAPTER_CHARS = 48 * 1024
    const val MAX_CHAPTERS = 4096

    data class Chapter(val href: String, val title: String, val start: Long, val length: Int)

    data class Result(val chapters: List<Chapter>, val characterCount: Long)

    fun compile(
        input: Reader,
        output: OutputStream,
        title: String,
        identifier: String,
        partTitle: (Int) -> String,
        checkpoint: () -> Unit = {},
    ): Result {
        require(title.isNotBlank() && title.length <= 2048)
        require(identifier.isNotBlank() && identifier.length <= 256)
        val chapters = mutableListOf<Chapter>()
        var offset = 0L
        ZipOutputStream(output).use { zip ->
            val mime = "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    time = 0
                    size = mime.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(mime) }.value
                },
            )
            zip.write(mime)
            zip.closeEntry()
            zip.text(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """
                    .trimIndent(),
            )
            val buffer = CharArray(8192)
            val pending = StringBuilder(CHAPTER_CHARS + buffer.size)
            fun emit(end: Int) {
                checkpoint()
                check(chapters.size < MAX_CHAPTERS) { "Text publication exceeds the chapter limit" }
                val content = pending.substring(0, end)
                pending.delete(0, end)
                val number = chapters.size + 1
                val name = "text/${number.toString().padStart(5, '0')}.xhtml"
                val label = partTitle(number).take(256)
                chapters += Chapter(name, label, offset, content.length)
                offset += content.length
                zip.text(
                    "EPUB/$name",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>" +
                        "<title>${xml(label)}</title><meta charset=\"UTF-8\"/>" +
                        "<style>body{overflow-wrap:anywhere}p{white-space:pre-wrap}</style>" +
                        "</head><body><p>${xml(content)}</p></body></html>",
                )
            }
            while (true) {
                checkpoint()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                pending.append(buffer, 0, read)
                while (pending.length > CHAPTER_CHARS) {
                    var end = pending.lastIndexOf("\n", CHAPTER_CHARS)
                    if (end < CHAPTER_CHARS * 3 / 4) end = CHAPTER_CHARS else end++
                    if (end > 0 && pending[end - 1].isHighSurrogate()) end--
                    emit(end)
                }
            }
            if (pending.isNotEmpty()) emit(pending.length)
            require(chapters.isNotEmpty()) { "Empty text publication" }
            val manifest =
                chapters
                    .mapIndexed { index, chapter ->
                        "<item id=\"c$index\" href=\"${chapter.href}\" media-type=\"application/xhtml+xml\"/>"
                    }
                    .joinToString("")
            val spine = chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
            zip.text(
                "EPUB/package.opf",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                    "<dc:identifier id=\"id\">${xml(identifier)}</dc:identifier><dc:title>${xml(title)}</dc:title>" +
                    "<dc:language>und</dc:language><meta property=\"dcterms:modified\">2000-01-01T00:00:00Z</meta>" +
                    "</metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" properties=\"nav\" " +
                    "media-type=\"application/xhtml+xml\"/>$manifest</manifest><spine>$spine</spine></package>",
            )
            val contents =
                chapters.joinToString("") { "<li><a href=\"${it.href}\">${xml(it.title)}</a></li>" }
            zip.text(
                "EPUB/nav.xhtml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">" +
                    "<head><title>${xml(title)}</title></head><body>" +
                    "<nav epub:type=\"toc\"><ol>$contents</ol></nav></body></html>",
            )
            checkpoint()
        }
        return Result(chapters, offset)
    }

    private fun ZipOutputStream.text(name: String, content: String) {
        putNextEntry(ZipEntry(name).apply { time = 0 })
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun xml(value: String): String =
        buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val codePoint = Character.codePointAt(value, index)
                index += Character.charCount(codePoint)
                when (codePoint) {
                    '&'.code -> append("&amp;")
                    '<'.code -> append("&lt;")
                    '>'.code -> append("&gt;")
                    '"'.code -> append("&quot;")
                    '\''.code -> append("&apos;")
                    else -> {
                        val valid =
                            codePoint in 0x20..0xD7FF ||
                                codePoint in 0xE000..0xFFFD ||
                                codePoint in 0x10000..0x10FFFF ||
                                codePoint in listOf(9, 10, 13)
                        if (valid) appendCodePoint(codePoint) else append('\uFFFD')
                    }
                }
            }
        }
}

/** A bounded probe; uncertain legacy encodings remain an explicit user correction. */
object NovelTextEncoding {
    data class Guess(val charset: Charset, val bomBytes: Int, val certain: Boolean)

    fun detect(prefix: ByteArray, complete: Boolean = prefix.size < 64 * 1024): Guess {
        require(prefix.size <= 64 * 1024)
        if (
            prefix.size >= 3 &&
            prefix.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        ) {
            return Guess(Charsets.UTF_8, 3, true)
        }
        // UTF-32 LE begins with the UTF-16 LE BOM: test the longer signature first.
        if (
            prefix.size >= 4 &&
            prefix.take(4) == listOf(0xFF.toByte(), 0xFE.toByte(), 0.toByte(), 0.toByte())
        ) {
            return Guess(Charset.forName("UTF-32LE"), 4, true)
        }
        if (
            prefix.size >= 4 &&
            prefix.take(4) == listOf(0.toByte(), 0.toByte(), 0xFE.toByte(), 0xFF.toByte())
        ) {
            return Guess(Charset.forName("UTF-32BE"), 4, true)
        }
        if (prefix.size >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte()) {
            return Guess(Charsets.UTF_16LE, 2, true)
        }
        if (prefix.size >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte()) {
            return Guess(Charsets.UTF_16BE, 2, true)
        }
        // A bounded, conservative hint for unmarked UTF-16 with mostly ASCII text.
        if (prefix.size >= 16) {
            val pairs = prefix.size / 2
            val even = (0 until pairs).count { prefix[it * 2] == 0.toByte() }
            val odd = (0 until pairs).count { prefix[it * 2 + 1] == 0.toByte() }
            if (odd >= pairs * .8 && even == 0) return Guess(Charsets.UTF_16LE, 0, false)
            if (even >= pairs * .8 && odd == 0) return Guess(Charsets.UTF_16BE, 0, false)
        }
        val validUtf8 = runCatching {
            val decoder =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            // The bounded prefix can end in the middle of a multibyte code point.
            decoder
                .decode(
                    java.nio.ByteBuffer.wrap(prefix),
                    java.nio.CharBuffer.allocate(prefix.size),
                    complete,
                )
                .throwExceptionIfError()
        }
            .isSuccess
        return if (validUtf8) {
            Guess(Charsets.UTF_8, 0, false)
        } else {
            Guess(Charset.forName("windows-1252"), 0, false)
        }
    }

    private fun java.nio.charset.CoderResult.throwExceptionIfError() {
        if (isError) throwException()
    }
}
