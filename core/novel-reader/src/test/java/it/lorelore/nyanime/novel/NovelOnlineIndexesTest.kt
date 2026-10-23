package it.lorelore.nyanime.novel

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NovelOnlineIndexesTest {
    @TempDir lateinit var root: File

    private fun document(origin: NovelOrigin, vararg names: String) =
        NovelOnlineDocument(
            bookId = NovelIdentity.remote(origin.sourceId, origin.reference),
            origin = origin,
            chapters = names.map { NovelOnlineChapter(it, "Chapter $it") },
            updatedAt = 12,
        )

    @Test
    fun `chapter identities survive reordering and an index replacement is complete`() =
        runBlocking<Unit> {
            val indexes = NovelOnlineIndexes(root)
            val origin = NovelOrigin(131, "opaque:work")
            val first = document(origin, "a", "b")
            val path = indexes.write(first)
            assertEquals(first, indexes.read(origin))
            val second = document(origin, "b", "a", "new")
            assertEquals(path, indexes.write(second))
            assertEquals(first.chapters.first().href, indexes.read(origin)!!.chapters[1].href)
            assertEquals(second, indexes.read(origin))
            assertFalse(
                File(root, path).parentFile!!.listFiles()!!.any { it.extension == "pending" },
            )
        }

    @Test
    fun `an index from another work is rejected and oversized edits keep the previous version`() =
        runBlocking<Unit> {
            val indexes = NovelOnlineIndexes(root)
            val origin = NovelOrigin(131, "one")
            val first = document(origin, "chapter")
            val path = indexes.write(first)
            val other = document(NovelOrigin(131, "two"), "chapter")
            val otherPath = indexes.write(other)
            File(root, path).writeBytes(File(root, otherPath).readBytes())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { indexes.read(origin) }
            }
            indexes.write(first)
            val oversized =
                first.copy(
                    chapters = List(3000) { NovelOnlineChapter("x".repeat(8100) + it, "Chapter") },
                )
            // A valid but unreasonably large graph cannot replace the previous index.
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { indexes.write(oversized) }
            }
            assertEquals(first, indexes.read(origin))
        }
}
