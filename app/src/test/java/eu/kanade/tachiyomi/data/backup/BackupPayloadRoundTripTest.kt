package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupHiddenResume
import eu.kanade.tachiyomi.data.backup.models.BackupHiddenResumeState
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BackupPayloadRoundTripTest {
    @Test fun retiredDocumentFieldDoesNotPreventRestoringExistingAnimeProgress() {
        val backup = Backup(
            isLegacy = false,
            backupAnime = listOf(
                BackupAnime(
                    source = 42,
                    url = "/show/one",
                    episodes = listOf(BackupEpisode(url = "/episode/one", name = "Episode", lastSecondSeen = 812_000)),
                ),
            ),
        )
        // Former optional field 510, wire type 2, containing an empty JSON object.
        val retiredField = byteArrayOf(0xF2.toByte(), 0x1F, 0x02, 0x7B, 0x7D)
        val restored = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup) + retiredField,
        )
        assertEquals(42L, restored.backupAnime.single().source)
        assertEquals(812_000L, restored.backupAnime.single().episodes.single().lastSecondSeen)
    }

    @Test fun olderPayloadWithoutNewOptionalFieldsStillDecodes() {
        val olderPayload = Backup(
            isLegacy = false,
            backupAnime = listOf(
                BackupAnime(
                    source = 42,
                    url = "/older/show",
                    episodes = listOf(BackupEpisode(url = "/older/episode", name = "Episode")),
                ),
            ),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), olderPayload),
        )

        assertEquals("/older/show", decoded.backupAnime.single().url)
        assertEquals(0L, decoded.backupAnime.single().episodes.single().endingDurationMs)
        assertNull(decoded.backupHiddenResume)
    }

    @Test fun progressAndSourceReferencesSurviveTheExistingBackupFormat() {
        val backup = Backup(
            isLegacy = false,
            backupAnime = listOf(
                BackupAnime(
                    source = 42,
                    url = "/show/one",
                    title = "Show",
                    backgroundUrl = "https://example.invalid/background.jpg",
                    episodes = listOf(
                        BackupEpisode(
                            url = "/show/one/episode/2",
                            name = "Episode 2",
                            lastSecondSeen = 812_000,
                            totalSeconds = 1_440_000,
                            endingStartMs = 1_200_000,
                            endingEndMs = 1_300_000,
                            endingDurationMs = 1_440_000,
                        ),
                    ),
                ),
            ),
            backupManga = listOf(
                BackupManga(
                    source = 73,
                    url = "/comic/one",
                    title = "Comic",
                    chapters = listOf(
                        BackupChapter(url = "/comic/one/chapter/3", name = "Chapter 3", lastPageRead = 17),
                    ),
                ),
            ),
            backupHiddenResume = BackupHiddenResumeState(listOf(BackupHiddenResume(42, "/show/one"))),
        )

        val restored = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
        )

        assertFalse(restored.isLegacy)
        assertEquals(42L, restored.backupAnime.single().source)
        assertEquals("https://example.invalid/background.jpg", restored.backupAnime.single().backgroundUrl)
        assertEquals(812_000L, restored.backupAnime.single().episodes.single().lastSecondSeen)
        assertEquals(1_440_000L, restored.backupAnime.single().episodes.single().totalSeconds)
        assertEquals(1_300_000L, restored.backupAnime.single().episodes.single().endingEndMs)
        assertEquals(73L, restored.backupManga.single().source)
        assertEquals(17L, restored.backupManga.single().chapters.single().lastPageRead)
        assertEquals("/show/one", restored.backupHiddenResume?.entries?.single()?.url)
    }
}
