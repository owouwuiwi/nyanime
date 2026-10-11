package eu.kanade.tachiyomi.data.cleanup

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import java.io.File
import java.io.IOException

class RetiredProfileDataTest {
    @TempDir lateinit var root: File
    private val noBackup get() = File(root, "no_backup")
    private fun database(name: String) = File(root, "databases/$name")
    private fun write(file: File) {
        file.parentFile.mkdirs()
        file.writeText("private fixture")
    }

    @Test fun `cleanup removes retired stores and keys without touching rooms cloud or local progress`() {
        for (name in listOf("community.identity", "personal-sync.identity")) {
            listOf("", ".bak", ".new").forEach { write(File(noBackup, name + it)) }
        }
        for (name in listOf("community-v1.db", "personal-sync-v1.db")) {
            listOf("", "-wal", "-shm", "-journal").forEach { write(database(name + it)) }
        }
        write(File(noBackup, "community-image-drafts/image.tmp"))
        val retained = listOf(
            File(noBackup, "rooms/session"),
            File(noBackup, "cloud/account.session"),
            File(noBackup, "cloud-sync.identity"),
            File(root, "downloads/episode.mp4"),
            database("anime.db"),
            database("manga.db"),
        )
        retained.forEach(::write)
        val aliases =
            mutableSetOf("nyanime.community.local.v1", "nyanime.personal-sync.local.v1", "room-key", "cloud-key")
        repeat(2) {
            assertTrue(RetiredProfileData.clean(noBackup, ::database) { aliases.remove(it) }.isEmpty())
        }
        assertEquals(setOf("room-key", "cloud-key"), aliases)
        assertEquals(retained.map { it.canonicalFile }.toSet(), root.walkTopDown().filter { it.isFile }.toSet())
        retained.forEach { assertEquals("private fixture", it.readText()) }
    }

    @Test fun `a temporary keystore failure is retryable while encrypted stores are removed`() {
        write(File(noBackup, "personal-sync.identity"))
        write(database("personal-sync-v1.db"))
        val failures = RetiredProfileData.clean(noBackup, ::database) { throw IOException("Key unavailable") }
        assertEquals(listOf("nyanime.personal-sync.local.v1", "nyanime.community.local.v1"), failures)
        assertFalse(File(noBackup, "personal-sync.identity").exists())
        assertFalse(database("personal-sync-v1.db").exists())
        val removed = mutableListOf<String>()
        assertTrue(RetiredProfileData.clean(noBackup, ::database) { removed.add(it) }.isEmpty())
        assertEquals(listOf("nyanime.personal-sync.local.v1", "nyanime.community.local.v1"), removed)
    }

    @Test fun `retired opt ins cannot be exported or restored as portable preferences`() {
        for (key in listOf("personal_sync_enabled", Preference.appStateKey("personal_sync_enabled"))) {
            assertTrue(BackupPreferencePolicy.isRetired(key))
            assertFalse(BackupPreferencePolicy.isPortable(key))
        }
    }
}
