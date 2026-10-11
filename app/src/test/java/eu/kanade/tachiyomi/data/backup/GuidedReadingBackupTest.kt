package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.protobuf.ProtoBuf
import nyanime.panels.GuidedLocation
import nyanime.panels.GuidedPageRecord
import nyanime.panels.GuidedPanelOrder
import nyanime.panels.PanelDirection
import nyanime.panels.PanelPageRef
import nyanime.panels.PanelRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidedReadingBackupTest {
    @Test fun oldBackupHasNoGuidedState() {
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(isLegacy = false))
        assertTrue(ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).guidedReading.isEmpty())
    }

    @Test fun additionalGuidedFieldsRoundTripWithoutImagesOrLocalDatabaseIds() {
        val record = GuidedPageRecord(
            PanelPageRef(91, "/series/7", "/chapter/2", 3),
            "b".repeat(64),
            corrections = listOf(PanelRect(.1f, .1f, .4f, .5f), PanelRect(.6f, .1f, .9f, .5f)),
            location = GuidedLocation(1, .7f, .3f, 3f, focusZoom = 1.5f, focusedPanel = PanelRect(.6f, .1f, .9f, .5f)),
            changedAt = 10,
        )
        val original = Backup(isLegacy = false, guidedReading = listOf(record))
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), original)
        assertEquals(original, ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes))
    }

    @Test fun eachPanelOrderSurvivesTheExistingMangaBackupFlags() {
        for (direction in PanelDirection.entries) {
            val flags = GuidedPanelOrder.set(0x40L or 3L or 0x18L, direction)
            val title = BackupManga(91, "/series/7", viewer_flags = flags.toInt())
            val backup = Backup(isLegacy = false, backupManga = listOf(title))
            val restored = ProtoBuf.decodeFromByteArray(
                Backup.serializer(),
                ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
            )
            val manga = restored.backupManga.single().getMangaImpl()
            assertEquals(flags, manga.viewerFlags)
            assertEquals(direction, GuidedPanelOrder.stored(manga.viewerFlags))
        }
    }
}
