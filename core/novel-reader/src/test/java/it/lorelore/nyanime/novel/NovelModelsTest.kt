package it.lorelore.nyanime.novel

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelModelsTest {
    private val id = NovelIdentity.local("a".repeat(64))

    @Test
    fun `identity uses actual content or exact source reference and never the title`() {
        assertEquals(id, NovelIdentity.local("a".repeat(64)))
        assertNotEquals(id, NovelIdentity.local("b".repeat(64)))
        assertNotEquals(NovelIdentity.remote(1, "/entry"), NovelIdentity.remote(2, "/entry"))
        assertNotEquals(NovelIdentity.remote(-1, "/entry"), NovelIdentity.remote(1, "/entry"))
    }

    @Test
    fun `unknown optional fields remain readable and nulls explicitly clear known fields`() {
        val book =
            NovelBook(
                id,
                "Title",
                format = NovelFormat.EPUB,
                contentVersion = "v1",
                importedAt = 0,
                modifiedAt = 0,
            )
        val json = NovelJson.encodeToString(book)
        assertEquals(
            book,
            NovelJson.decodeFromString<NovelBook>(
                json.dropLast(1) + ",\"futureOptionalMetadata\":true}",
            ),
        )
        assertTrue(json.contains("\"origin\":null"))
    }

    @Test
    fun `structural positions and appearance reject invalid or unsafe data`() {
        val position = NovelPosition(id, "v1", "{\"href\":\"chapter.xhtml\"}", 0, .75)
        assertEquals(
            position,
            NovelJson.decodeFromString<NovelPosition>(NovelJson.encodeToString(position)),
        )
        assertThrows(IllegalArgumentException::class.java) { position.copy(totalProgression = 1.1) }
        assertThrows(IllegalArgumentException::class.java) { position.copy(locatorJson = "[]") }
        assertThrows(IllegalArgumentException::class.java) {
            NovelAppearance(fontSize = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelAppearance(margins = Double.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelBook(
                id,
                "Title",
                format = NovelFormat.EPUB,
                contentVersion = "v1",
                originalFile = "../other",
                importedAt = 0,
                modifiedAt = 0,
            )
        }
    }
}
