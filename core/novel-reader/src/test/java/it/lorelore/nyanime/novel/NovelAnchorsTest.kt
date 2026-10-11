package it.lorelore.nyanime.novel

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NovelAnchorsTest {
    private val origin = NovelOrigin(19, "work")
    private val book =
        NovelBook(
            NovelIdentity.remote(19, "work"),
            "Work",
            format = NovelFormat.ONLINE,
            contentVersion = NovelOnlineIdentity.edition(origin),
            origin = origin,
            importedAt = 0,
            modifiedAt = 0,
        )
    private val href = NovelOnlineChapter("chapter", "One").href
    private val note =
        NovelAnnotation(
            bookId = book.id,
            contentVersion = book.contentVersion,
            locatorJson = "{\"href\":\"$href\"}",
            kind = NovelAnnotationKind.NOTE,
            quote = "Original",
            chapterReference = href,
            resourceVersion = "a".repeat(64),
            createdAt = 0,
            modifiedAt = 0,
        )

    @Test
    fun `online anchors are only current after matching the exact saved chapter bytes`() {
        assertEquals(NovelAnchors.State.UNVERIFIED, NovelAnchors.state(book, note, emptyMap()))
        assertEquals(
            NovelAnchors.State.CURRENT,
            NovelAnchors.state(book, note, mapOf(href to "a".repeat(64))),
        )
        assertEquals(
            NovelAnchors.State.CHANGED,
            NovelAnchors.state(book, note, mapOf(href to "b".repeat(64))),
        )
        assertEquals(
            NovelAnchors.State.UNVERIFIED,
            NovelAnchors.state(
                book,
                note.copy(resourceVersion = null),
                mapOf(href to "a".repeat(64)),
            ),
        )
        assertEquals(
            NovelAnchors.State.CHANGED,
            NovelAnchors.state(book, note.copy(contentVersion = "older"), emptyMap()),
        )
        assertEquals("Original", note.quote)
    }

    @Test
    fun `chapter hashes roundtrip without making them mandatory for local or old records`() {
        assertEquals(
            note,
            NovelJson.decodeFromString<NovelAnnotation>(NovelJson.encodeToString(note)),
        )
        val legacy =
            NovelJson.encodeToString(note.copy(chapterReference = null, resourceVersion = null))
                .replace(",\"chapterReference\":null", "")
                .replace(",\"resourceVersion\":null", "")
        assertEquals(null, NovelJson.decodeFromString<NovelAnnotation>(legacy).resourceVersion)
        val local =
            book.copy(
                id = NovelIdentity.local("d".repeat(64)),
                format = NovelFormat.EPUB,
                origin = null,
            )
        assertEquals(
            NovelAnchors.State.CURRENT,
            NovelAnchors.state(
                local,
                note.copy(bookId = local.id, resourceVersion = null),
                emptyMap(),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            note.copy(resourceVersion = "not-a-hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            note.copy(chapterReference = NovelOnlineChapter("other", "Other").href)
        }
    }
}
