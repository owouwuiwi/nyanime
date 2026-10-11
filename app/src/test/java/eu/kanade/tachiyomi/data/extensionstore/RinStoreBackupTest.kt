package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RinStoreBackupTest {
    @Test fun `disabled state round trips without changing payload or publisher identity`() {
        val module = BackupExtension(
            "test.extension.manga",
            byteArrayOf(1, 2, 3),
            "rin",
            "a".repeat(64),
            "https://example.invalid/index.json",
            rinEnabled = false,
        )
        val bytes = ProtoBuf.encodeToByteArray(BackupExtension.serializer(), module)
        val restored = ProtoBuf.decodeFromByteArray(BackupExtension.serializer(), bytes)
        assertEquals(module, restored)
        assertEquals(module.hashCode(), restored.hashCode())
        assertFalse(restored.rinEnabled)
        assertFalse(module == module.copy(rinEnabled = true))
    }

    @Test fun `older APK and RIN backups default to enabled`() {
        val module = BackupExtension("test.extension.manga", byteArrayOf(1))
        val restored = ProtoBuf.decodeFromByteArray(
            BackupExtension.serializer(),
            ProtoBuf.encodeToByteArray(BackupExtension.serializer(), module),
        )
        assertTrue(restored.rinEnabled)
        assertEquals("apk", restored.format)
    }
}
