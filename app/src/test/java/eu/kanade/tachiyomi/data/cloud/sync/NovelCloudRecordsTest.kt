package eu.kanade.tachiyomi.data.cloud.sync

import it.lorelore.nyanime.novel.NovelBook
import it.lorelore.nyanime.novel.NovelFormat
import it.lorelore.nyanime.novel.NovelIdentity
import it.lorelore.nyanime.novel.NovelOrigin
import it.lorelore.nyanime.novel.NovelRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NovelCloudRecordsTest {
    private fun book(source: Long) =
        NovelBook(
            NovelIdentity.remote(source, "/exact-reference"),
            "Same title",
            format = NovelFormat.ONLINE,
            contentVersion = "revision-1",
            origin = NovelOrigin(source, "/exact-reference"),
            importedAt = 0,
            modifiedAt = 1,
        )

    private fun record(book: NovelBook) =
        NovelRecord("book", book.id, book.id, book.modifiedAt, Json.encodeToJsonElement(book))

    @Test
    fun `renaming a title keeps its cloud identity and different sources stay distinct`() {
        val first = book(1)
        val ref = NovelCloudRecords.ref(record(first), first)
        ref.validate()
        val renamed = first.copy(title = "An alternative title")
        assertEquals(ref, NovelCloudRecords.ref(record(renamed), renamed))
        val other = book(2)
        assertNotEquals(ref, NovelCloudRecords.ref(record(other), other))
        assertThrows(IllegalArgumentException::class.java) {
            NovelCloudRecords.ref(record(first), other)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(first.copy(origin = NovelOrigin(2, "/exact-reference")))
        }
    }
}
