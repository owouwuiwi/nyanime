package it.lorelore.nyanime.novel

import it.lorelore.nyanime.novel.source.NovelArtworkCache
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NovelArtworkCacheTest {
    @TempDir lateinit var directory: File

    @Test
    fun `search artwork survives reopening without a library record or Coil memory`() =
        runBlocking {
            val key = NovelArtworkCache.key(21, "opaque:work", "opaque:cover")
            val bytes = byteArrayOf(1, 2, 3, 4)
            NovelArtworkCache(directory).write(key, bytes)
            assertArrayEquals(bytes, NovelArtworkCache(directory).read(key))
            assertNotEquals(key, NovelArtworkCache.key(22, "opaque:work", "opaque:cover"))
            assertNotEquals(key, NovelArtworkCache.key(21, "opaque:other", "opaque:cover"))
            assertTrue(
                directory.listFiles()!!.all {
                    it.name.matches(Regex("[a-f0-9]{64}-[a-f0-9]{64}\\.image"))
                },
            )
        }

    @Test
    fun `corrupt artwork is rejected and unrelated files are preserved`() = runBlocking {
        val cache = NovelArtworkCache(directory)
        val key = NovelArtworkCache.key(21, "work", "cover")
        cache.write(key, byteArrayOf(1, 2, 3))
        val own = directory.listFiles()!!.single()
        val foreign = File(directory, "unrelated.txt").apply { writeText("untouched") }
        own.writeBytes(byteArrayOf(3, 2, 1))
        assertNull(cache.read(key))
        assertFalse(own.exists())
        assertEquals("untouched", foreign.readText())
    }

    @Test
    fun `artwork is bounded by bytes and entries and replaces its previous revision`() =
        runBlocking {
            var time = 100L
            val cache =
                NovelArtworkCache(directory, byteLimit = 8, entryLimit = 2, clock = { ++time })
            val first = NovelArtworkCache.key(1, "one", "cover")
            val second = NovelArtworkCache.key(1, "two", "cover")
            val third = NovelArtworkCache.key(1, "three", "cover")
            cache.write(first, byteArrayOf(1, 1, 1, 1))
            cache.write(second, byteArrayOf(2, 2, 2, 2))
            assertNotNull(cache.read(first))
            cache.write(third, byteArrayOf(3, 3, 3, 3))
            assertNull(cache.read(second))
            cache.write(first, byteArrayOf(4, 4))
            assertArrayEquals(byteArrayOf(4, 4), cache.read(first))
            assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 8)
            assertTrue(directory.listFiles()!!.size <= 2)
        }

    @Test
    fun `interrupted artwork writes are recovered without removing foreign files`() = runBlocking {
        val stale =
            File(directory, "pending-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").apply {
                writeText("incomplete")
            }
        val foreign = File(directory, "pending-user.txt").apply { writeText("untouched") }
        assertNull(NovelArtworkCache(directory).read(NovelArtworkCache.key(1, "book", "cover")))
        assertFalse(stale.exists())
        assertEquals("untouched", foreign.readText())
    }
}
