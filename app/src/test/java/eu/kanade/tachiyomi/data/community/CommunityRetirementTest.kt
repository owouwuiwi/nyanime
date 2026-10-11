package eu.kanade.tachiyomi.data.community

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class CommunityRetirementTest {
    @TempDir lateinit var root: File
    private val noBackup get() = File(root, "no_backup")
    private fun database(name: String) = File(root, "databases/$name")
    private fun write(file: File) {
        file.parentFile.mkdirs()
        file.writeText("private fixture")
    }

    @Test
    fun `cleanup removes only retired stores including atomic and sqlite sidecars and is repeatable`() {
        for (name in listOf("community.identity", "personal-sync.identity")) {
            listOf("", ".bak", ".new").forEach { write(File(noBackup, name + it)) }
        }
        for (name in listOf("community-v1.db", "personal-sync-v1.db")) {
            listOf("", "-wal", "-shm", "-journal").forEach { write(database(name + it)) }
        }
        write(File(noBackup, "community-image-drafts/image.tmp"))
        val retained = listOf(
            File(noBackup, "rooms/session"),
            File(noBackup, "ultra/tasks.json"),
            File(root, "downloads/episode.mp4"),
            database("anime.db"),
            database("manga.db"),
        )
        retained.forEach(::write)
        val aliases = mutableSetOf("nyanime.community.local.v1", "nyanime.personal-sync.local.v1", "room-key")
        repeat(2) {
            assertTrue(
                CommunityRetirement.clean(noBackup, ::database, { aliases.remove(it) }, true, true).isEmpty(),
            )
        }
        assertEquals(setOf("room-key"), aliases)
        assertEquals(retained.map { it.canonicalFile }.toSet(), root.walkTopDown().filter { it.isFile }.toSet())
        retained.forEach { assertEquals("private fixture", it.readText()) }
    }

    @Test
    fun `failed key cleanup is reported and retried without leaving old encrypted stores behind`() {
        write(File(noBackup, "personal-sync.identity"))
        write(database("personal-sync-v1.db"))
        write(File(noBackup, "community.identity"))
        val failures = CommunityRetirement.clean(
            noBackup,
            ::database,
            { throw IOException("Keystore temporarily unavailable") },
            false,
            true,
        )
        assertEquals(listOf("nyanime.personal-sync.local.v1"), failures)
        assertFalse(File(noBackup, "personal-sync.identity").exists())
        assertFalse(database("personal-sync-v1.db").exists())
        assertTrue(File(noBackup, "community.identity").exists())
        val removed = mutableListOf<String>()
        assertTrue(CommunityRetirement.clean(noBackup, ::database, { removed.add(it) }, false, true).isEmpty())
        assertEquals(listOf("nyanime.personal-sync.local.v1"), removed)
    }
}
