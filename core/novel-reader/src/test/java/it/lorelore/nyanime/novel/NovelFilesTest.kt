package it.lorelore.nyanime.novel

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class NovelFilesTest {
    @TempDir lateinit var directory: File

    private fun epub(text: String): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                TextPublicationCompiler.compile(
                    StringReader(text),
                    output,
                    "A title",
                    "fixture-v1",
                    { "Part $it" },
                )
            }
            .toByteArray()

    @Test
    fun `large text survives chapter boundaries and produces deterministic valid EPUB`() {
        val text =
            "x".repeat(TextPublicationCompiler.CHAPTER_CHARS - 1) +
                "🙂\n<>&\"'\n" +
                "a\n    b\n".repeat(25000)
        val bytes = epub(text)
        assertArrayEquals(bytes, epub(text))
        val file = File(directory, "test.epub").apply { writeBytes(bytes) }
        NovelImportFiles(directory).inspectEpub(file)
        val recovered = StringBuilder()
        ZipFile(file).use { zip ->
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
            for (
            entry in
            zip.entries()
                .toList()
                .filter { it.name.startsWith("EPUB/text/") }
                .sortedBy { it.name }
            ) {
                val document =
                    zip.getInputStream(entry).use { factory.newDocumentBuilder().parse(it) }
                recovered.append(
                    document
                        .getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "p")
                        .item(0)
                        .textContent,
                )
            }
        }
        assertEquals(text, recovered.toString())
    }

    @Test
    fun `cancelled and rejected copies close inputs and preserve existing documents`() {
        val pending = File(directory, "pending")
        val bytes = epub("A readable page")
        var closed = false
        val input =
            object : ByteArrayInputStream(bytes) {
                override fun close() {
                    closed = true
                }
            }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            NovelImportFiles(pending)
                .copy(input, { if (++checks == 2) throw CancellationException() })
        }
        assertTrue(closed)
        assertTrue(pending.listFiles().orEmpty().isEmpty())
        val existing = File(directory, "existing").apply { writeText("preserve") }
        assertThrows(IllegalStateException::class.java) {
            NovelImportFiles(existing).copy(ByteArrayInputStream(bytes))
        }
        assertEquals("preserve", existing.readText())
    }

    @Test
    fun `import verifies actual hash and blocks archive traversal without extracting`() {
        val bytes = epub("A readable page")
        val files = NovelImportFiles(File(directory, "pending"))
        files.copy(ByteArrayInputStream(bytes)).use {
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { n ->
                    "%02x".format(n)
                },
                it.sha256,
            )
            assertTrue(files.inspectEpub(it.file).entries.contains("EPUB/package.opf"))
        }
        val malicious = File(directory, "malicious.epub")
        ZipOutputStream(malicious.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { files.inspectEpub(malicious) }
        assertFalse(File(directory, "outside").exists())
        listOf("../outside", "inside/%2e%2e/outside", "inside%5c..%5coutside", "C%3a/outside")
            .forEach {
                assertFalse(files.safeArchivePath(it))
            }
    }
}
