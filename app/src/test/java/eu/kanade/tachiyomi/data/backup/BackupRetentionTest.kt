package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupRetention
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupRetentionTest {
    @Test fun retainsVerifiedFileEvenWhenClockMovesBackwards() {
        assertEquals(
            listOf("oldest"),
            BackupRetention.obsolete(listOf("future", "newer", "older", "oldest", "verified"), "verified"),
        )
    }

    @Test fun smallArchiveIsPreservedAndRepeatedFailuresAreRateLimited() {
        assertTrue(BackupRetention.obsolete(listOf("new", "old"), "new").isEmpty())
        assertEquals(listOf(1, 3, 6), (1..7).filter(BackupRetention::shouldNotify))
    }
}
