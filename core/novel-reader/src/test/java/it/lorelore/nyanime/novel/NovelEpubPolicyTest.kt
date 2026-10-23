package it.lorelore.nyanime.novel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NovelEpubPolicyTest {
    @TempDir lateinit var root: File

    private fun book(
        href: String = "chapters/first.xhtml",
        metadata: String = "",
        navigation: String = "<ncx><navMap/></ncx>",
        encryption: String? = null,
    ): File {
        val entries =
            linkedMapOf(
                "META-INF/container.xml" to
                    "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
                "OPS/book.opf" to
                    "<package><metadata>$metadata</metadata><manifest>" +
                    "<item id='first' href='$href' media-type='application/xhtml+xml'/>" +
                    "<item id='nav' href='../toc.ncx' media-type='application/x-dtbncx+xml'/>" +
                    "</manifest><spine><itemref idref='first'/></spine></package>",
                "OPS/chapters/first.xhtml" to "<html><body>Readable</body></html>",
                "toc.ncx" to navigation,
            )
        if (encryption != null) entries["META-INF/encryption.xml"] = encryption
        return File(root, "${java.util.UUID.randomUUID()}.epub").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, body) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }

    @Test
    fun `EPUB2 public doctypes and navigation parent paths remain supported`() {
        val file =
            book(
                navigation =
                "<!DOCTYPE ncx PUBLIC '-//NISO//DTD ncx 2005-1//EN' 'http://external.invalid/ncx.dtd'><ncx><navMap/></ncx>",
            )
        assertFalse(NovelEpubPolicy.inspect(file).unsupportedEncryption)
    }

    @Test
    fun `remote and encoded escaping spine references are rejected before toolkit parsing`() {
        listOf(
            "https://remote.invalid/book.xhtml",
            "../../../outside.xhtml",
            "%2E%2E/%2E%2E/outside.xhtml",
        )
            .forEach {
                assertThrows(Exception::class.java) { NovelEpubPolicy.inspect(book(href = it)) }
            }
    }

    @Test
    fun `metadata byte and structure budgets are independent`() {
        assertThrows(IllegalArgumentException::class.java) {
            NovelEpubPolicy.inspect(book(metadata = "x".repeat(2 * 1024 * 1024)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelEpubPolicy.inspect(book(navigation = "<node>".repeat(100) + "</node>".repeat(100)))
        }
    }

    @Test
    fun `font obfuscation is distinguished from unsupported content encryption`() {
        fun encrypted(algorithm: String) =
            "<encryption><EncryptedData><EncryptionMethod Algorithm='$algorithm'/></EncryptedData></encryption>"
        assertFalse(
            NovelEpubPolicy.inspect(
                book(encryption = encrypted("http://www.idpf.org/2008/embedding")),
            )
                .unsupportedEncryption,
        )
        assertTrue(
            NovelEpubPolicy.inspect(
                book(encryption = encrypted("urn:unavailable:content-encryption")),
            )
                .unsupportedEncryption,
        )
        assertTrue(
            NovelEpubPolicy.inspect(book(encryption = "<encryption/>")).unsupportedEncryption,
        )
    }
}
