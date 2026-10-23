package it.lorelore.nyanime.novel

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NovelArchiveTest {
    private val id = NovelIdentity.local("a".repeat(64))
    private val book =
        NovelBook(
            id,
            "Own book",
            format = NovelFormat.EPUB,
            contentVersion = "a".repeat(64),
            originalFile = "books/$id/local/original.epub",
            publicationFile = "books/$id/local/original.epub",
            importedAt = 0,
            modifiedAt = 1,
        )

    @Test
    fun `portable metadata cannot inject a private file location`() {
        val encoded = NovelJson.encodeToString(book)
        assertFalse(encoded.contains("originalFile"))
        assertFalse(encoded.contains("publicationFile"))
        assertFalse(encoded.contains("books/"))
        val payload = NovelJson.parseToJsonElement(encoded).jsonObject
        val record = NovelRecord("book", id, id, 1, payload)
        assertEquals(
            record,
            NovelArchive.decode(NovelArchive(records = listOf(record)).encode()).records.single(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(
                payload =
                JsonObject(payload + ("originalFile" to JsonPrimitive("books/$id/other.epub"))),
            )
        }
    }

    @Test
    fun `future optional records and book fields survive portable round trips`() {
        val payload = NovelJson.parseToJsonElement(NovelJson.encodeToString(book)).jsonObject
        val records =
            listOf(
                NovelRecord(
                    "book",
                    id,
                    id,
                    1,
                    JsonObject(payload + ("futureOptionalMetadata" to JsonPrimitive("preserved"))),
                ),
                NovelRecord(
                    "future-feature",
                    "key",
                    id,
                    2,
                    JsonObject(mapOf("value" to JsonPrimitive(true))),
                ),
            )
        assertEquals(records, NovelArchive.decode(NovelArchive(records = records).encode()).records)
    }

    @Test
    fun `wrong identities duplicate records and unsupported versions are rejected`() {
        val payload = NovelJson.parseToJsonElement(NovelJson.encodeToString(book))
        val record = NovelRecord("book", id, id, 1, payload)
        assertThrows(IllegalArgumentException::class.java) { record.copy(key = "another") }
        assertThrows(IllegalArgumentException::class.java) {
            NovelArchive(records = listOf(record, record))
        }
        assertThrows(IllegalArgumentException::class.java) { NovelArchive(version = 2) }
        assertThrows(IllegalArgumentException::class.java) {
            NovelArchive.decode("[".repeat(65) + "]".repeat(65))
        }
    }
}
