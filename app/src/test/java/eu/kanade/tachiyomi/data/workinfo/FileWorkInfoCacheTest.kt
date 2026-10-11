package eu.kanade.tachiyomi.data.workinfo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileWorkInfoCacheTest {
    @TempDir lateinit var directory: File

    @Test fun `atomic cache entries survive reopening and ignore corrupt files`() = runBlocking {
        val cache = FileWorkInfoCache(directory, Json)
        val entry = WorkCacheEntry(100, """{"value":"complete"}""")
        cache.write("catalogue:123:it", entry)
        assertEquals(entry, FileWorkInfoCache(directory, Json).read("catalogue:123:it"))
        val updated = entry.copy(savedAt = 200, payload = """{"value":"updated"}""")
        cache.write("catalogue:123:it", updated)
        assertEquals(updated, cache.read("catalogue:123:it"))
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
        directory.listFiles()!!.single().writeText("not a complete JSON document")
        assertNull(cache.read("catalogue:123:it"))
    }

    @Test fun `future timestamps are not treated as fresh after a clock correction`() {
        assertTrue(WorkCacheEntry(100, "{}").fresh(100))
        assertEquals(false, WorkCacheEntry(101, "{}").fresh(100))
        assertEquals(false, WorkCacheEntry(100, "{}").fresh(100 + 24 * 60 * 60 * 1000L))
    }
}
