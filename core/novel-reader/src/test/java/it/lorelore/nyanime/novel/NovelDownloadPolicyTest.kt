package it.lorelore.nyanime.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CancellationException

class NovelDownloadPolicyTest {
    @TempDir lateinit var root: File
    private val origin = NovelOrigin(301, "fixture:work")
    private val book =
        NovelBook(
            NovelIdentity.remote(origin.sourceId, origin.reference),
            "Synthetic",
            format = NovelFormat.ONLINE,
            contentVersion = NovelOnlineIdentity.edition(origin),
            origin = origin,
            importedAt = 0,
            modifiedAt = 0,
        )
    private val chapters =
        listOf(NovelOnlineChapter("opaque-one", "One"), NovelOnlineChapter("opaque-two", "Two"))
    private val doc =
        NovelOnlineDocument(bookId = book.id, origin = origin, chapters = chapters, updatedAt = 0)

    @Test
    fun `plans use exact source identity and structural chapter references`() {
        assertEquals(
            chapters[0].href,
            NovelDownloadPlan(book = book, document = doc, selected = listOf(chapters[0].href))
                .selected
                .single(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPlan(book = book, document = doc, selected = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPlan(
                book = book,
                document = doc,
                selected = listOf("https://outside.invalid/one"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPlan(
                book = book,
                document = doc,
                selected = listOf(chapters[0].href, chapters[0].href),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPlan(
                book = book.copy(contentVersion = "other"),
                document = doc,
                selected = listOf(chapters[0].href),
            )
        }
    }

    @Test
    fun `download images are exact sanitized references and deduplicated`() {
        val ref = NovelOnlineIdentity.resourceHref("opaque-image")
        val html = "<p>Read</p><img src='../$ref'><img src='../$ref'>".toByteArray()
        assertEquals(listOf(ref), NovelDownloadPolicy.images(html))
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPolicy.images("<img src='https://unlisted.invalid/a'>".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPolicy.images("<img src='../assets/../../outside'>".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPolicy.images("<img src='data:image/png;base64,aaa'>".toByteArray())
        }
    }

    @Test
    fun `only owned content names can leave the download root`() {
        val name = NovelDownloadPolicy.objectName(chapters[0].href, "e".repeat(64))
        assertEquals(
            File(root, "${book.id}/$name").canonicalFile,
            NovelDownloadPolicy.owned(root, book.id, name),
        )
        assertFalse(name.contains("opaque"))
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPolicy.owned(root, "../other", name)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPolicy.owned(root, book.id, "../../outside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelDownloadPolicy.objectName("chapters/unknown.xhtml", "f".repeat(64))
        }
    }

    @Test
    fun `stream verification is cancellable and checks actual bytes`() {
        val file = File(root, "sample").apply { writeText("Original bytes") }
        val hash = NovelDownloadPolicy.digest(file)
        assertEquals(NovelOnlineIdentity.digest(file.readBytes()), hash)
        file.appendText("changed")
        assertNotEquals(hash, NovelDownloadPolicy.digest(file))
        assertThrows(CancellationException::class.java) {
            NovelDownloadPolicy.digest(file) { throw CancellationException() }
        }
        file.writeBytes(byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { NovelDownloadPolicy.digest(file) }
    }
}
