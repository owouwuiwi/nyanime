package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.ui.reader.spread.SpreadMode
import eu.kanade.tachiyomi.ui.reader.spread.SpreadRecord
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpreadBackupTest {
    @Test fun `older backups leave paired reading disabled`() {
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(isLegacy = false))
        assertTrue(ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).readerSpreads.isEmpty())
    }

    @Test fun `optional reader settings survive binary backup without local ids or images`() {
        val record = SpreadRecord(91, "/series/7", SpreadMode.PAIRS, 1, mapOf("/chapter/2" to 3), 10)
        val original = Backup(isLegacy = false, readerSpreads = listOf(record))
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), original)
        assertEquals(original, ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes))
    }
}
