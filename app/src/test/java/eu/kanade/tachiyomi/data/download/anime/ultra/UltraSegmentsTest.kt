package eu.kanade.tachiyomi.data.download.anime.ultra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale

class UltraSegmentsTest {
    @TempDir lateinit var directory: File
    private fun segments() = UltraSegments(directory, listOf(0, 2_002_000, 4_004_000, 4_500_000))
    private fun temporary() = File(directory, "video.mp4").apply { writeBytes(ByteArray(64) { it.toByte() }) }

    @Test
    fun `variable rate and reordered samples split at real presentation timestamps`() {
        val samples = listOf(0L, 2_035_366L, 2_002_000L, -1L, 4_004_000L, 4_004_000L, 5_000_000L)
        assertEquals(listOf(0L, 2_002_000L, 4_004_000L, 4_500_000L), UltraSegments.plan(samples, 4_500_000))
        assertEquals(listOf(0L, 800_000L), UltraSegments.plan(emptyList(), 800_000))
    }

    @Test
    fun `interrupted and unmarked clips never count as progress`() {
        val segments = segments()
        temporary()
        segments.file(0).writeBytes(byteArrayOf(1, 2))
        assertEquals(0, segments.completed())
        segments.commit(0, temporary())
        assertEquals(1, segments().completed())
        assertFalse(File(directory, "video.mp4").exists())
        // Process death between video rename and receipt publication.
        segments.file(1).writeBytes(byteArrayOf(3, 4))
        assertEquals(1, segments().completed())
    }

    @Test
    fun `missing or changed file invalidates the checkpoint prefix`() {
        val segments = segments()
        segments.commit(0, temporary())
        segments.commit(1, temporary())
        segments.file(0).appendBytes(byteArrayOf(9))
        assertEquals(0, segments().completed())
        assertThrows(IllegalArgumentException::class.java) { segments.commit(2, temporary()) }
        assertThrows(IllegalStateException::class.java) { segments.concatFile() }
    }

    @Test
    fun `concat preserves fractional source timing independent of phone locale`() {
        val segments = segments()
        repeat(3) { segments.commit(it, temporary()) }
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            val concat = segments.concatFile().readText()
            assertTrue(concat.contains("duration 2.002000"))
            assertTrue(concat.contains("duration 0.496000"))
            assertFalse(concat.contains(directory.absolutePath))
            assertEquals(3, segments().completed())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
