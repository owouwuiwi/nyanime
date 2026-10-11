package it.lorelore.nyanime.novel

import it.lorelore.nyanime.novel.ui.NovelCover
import it.lorelore.nyanime.novel.ui.NovelShelfEntry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelShelfEntryTest {
    private val hash = "a".repeat(64)
    private val book =
        NovelBook(
            NovelIdentity.local(hash),
            "A book",
            format = NovelFormat.EPUB,
            contentVersion = hash,
            importedAt = 0,
            modifiedAt = 0,
        )

    private fun entry(point: NovelPosition?) =
        NovelShelfEntry(
            NovelStore.ShelfItem(book, point),
            NovelCover(book.id, null, hash),
        )

    @Test
    fun `continue distinguishes a saved structural point from an unstarted or completed book`() {
        assertFalse(entry(null).inProgress)
        val point = NovelPosition(book.id, hash, "{}", 1, 0.0)
        assertTrue(entry(point).inProgress)
        assertTrue(entry(point.copy(totalProgression = .5)).inProgress)
        assertFalse(entry(point.copy(totalProgression = 1.0)).inProgress)
    }

    @Test
    fun `unknown total progression does not discard a saved structural point`() {
        assertTrue(entry(NovelPosition(book.id, hash, "{}", 1)).inProgress)
    }
}
