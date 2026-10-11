package eu.kanade.tachiyomi.data.recovery

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RecoveryBackupIntegrityTest {
    private fun backup(progress: Long = 82, secret: String = "fixture", categoryId: Long = 4) = Backup(
        backupAnime = listOf(
            BackupAnime(
                123,
                "/title/alpha",
                "Alpha",
                categories = listOf(categoryId),
                episodes = listOf(BackupEpisode("/episode/1", "First", lastSecondSeen = progress, bookmark = true)),
            ),
        ),
        backupManga = listOf(
            BackupManga(
                456,
                "/title/beta",
                "Beta",
                chapters = listOf(BackupChapter("/chapter/1", "First", lastPageRead = 7)),
            ),
        ),
        backupAnimeCategories = listOf(BackupCategory("Reading list", id = categoryId)),
        backupPreferences = listOf(BackupPreference("private_fixture_key", StringPreferenceValue(secret))),
    )

    @Test fun regeneratedCategoryIdsDoNotChangePortableMeaning() {
        RecoveryBackupIntegrity.verify(backup(), backup(categoryId = 20))
    }

    @Test fun lostPlaybackPositionIsDetected() {
        assertThrows(IllegalStateException::class.java) {
            RecoveryBackupIntegrity.verify(backup(), backup(progress = 0))
        }
    }

    @Test fun lostPrivateSettingIsDetected() {
        assertThrows(IllegalStateException::class.java) {
            RecoveryBackupIntegrity.verify(backup(), backup(secret = ""))
        }
    }

    @Test fun lostReadingPositionIsDetected() {
        val after = backup()
        after.backupManga[0].chapters = emptyList()
        assertThrows(IllegalStateException::class.java) { RecoveryBackupIntegrity.verify(backup(), after) }
    }

    @Test fun entriesWithEqualPathsOnDifferentSourcesStayDistinct() {
        val before = backup()
        val after = backup()
        after.backupAnime[0].source =
            900
        assertThrows(IllegalStateException::class.java) { RecoveryBackupIntegrity.verify(before, after) }
    }
}
