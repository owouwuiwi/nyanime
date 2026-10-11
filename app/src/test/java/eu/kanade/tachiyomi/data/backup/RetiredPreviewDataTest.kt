package eu.kanade.tachiyomi.data.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RetiredPreviewDataTest {
    @TempDir lateinit var cache: File

    @Test fun `retirement removes only preview files and can run again after an older restore`() {
        val originals = File(cache, "custom-cover.jpg").apply { writeText("original") }
        val shared = File(cache, "image_cache").apply { mkdir() }
        File(shared, "cover").writeText("cached")
        val preview = File(cache, "title-artwork-v1").apply { mkdir() }
        File(preview, "poster").writeText("preview")
        val mapping = File(cache, "title-artwork-mappings-v3.json").apply { writeText("mapping") }
        val pending = File(cache, "title-artwork-mappings-v3.json.pending").apply { writeText("pending") }

        RetiredPreviewData.cleanArtworkCache(cache)
        assertFalse(preview.exists())
        assertFalse(mapping.exists())
        assertFalse(pending.exists())
        assertEquals("original", originals.readText())
        assertEquals("cached", File(shared, "cover").readText())

        mapping.writeText("restored")
        RetiredPreviewData.cleanArtworkCache(cache)
        assertFalse(mapping.exists())
        assertEquals("original", originals.readText())
    }
}
