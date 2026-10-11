package eu.kanade.tachiyomi.data.download.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadQueuePersistenceTest {
    @Test fun bothQueuesSurviveLegacyMigrationAndOverlappingNumericIds() {
        val stored = mutableMapOf<String, Any>(
            "1" to "episode:1",
            "2" to "chapter:1",
            "3" to "malformed",
            "anime:7" to "episode:7",
        )
        for ((prefix, marker) in listOf("anime" to "episode:", "manga" to "chapter:")) {
            val migration = queueMigration(stored, prefix) { it.takeIf { it.startsWith(marker) }?.substringAfter(':') }
            stored.putAll(migration.upserts)
            migration.removals.forEach(stored::remove)
        }
        assertEquals(
            mapOf(
                "anime:1" to "episode:1",
                "manga:1" to "chapter:1",
                "anime:7" to "episode:7",
                "3" to "malformed",
            ),
            stored,
        )
        stored.keys.removeAll { it.startsWith("anime:") }
        assertEquals("chapter:1", stored["manga:1"])
    }

    @Test fun interruptedMigrationNeverOverwritesNewerNamespacedEntry() {
        val entries = mapOf("1" to "old", "anime:1" to "new")
        val migration = queueMigration(entries, "anime") { "1" }
        assertTrue(migration.upserts.isEmpty())
        assertEquals(setOf("1"), migration.removals)
        assertEquals(QueueMigration(emptyMap(), emptySet()), queueMigration(mapOf("anime:1" to "new"), "anime") { "1" })
    }
}
