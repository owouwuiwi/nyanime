package nyanime.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CloudModelsTest {
    private val device = UUID.randomUUID().toString()
    private fun manifest(id: String = UUID.randomUUID().toString(), deviceId: String = device) = CloudManifest(
        backupId = id,
        deviceId = deviceId,
        deviceName = "Phone",
        sha256 = "a".repeat(64),
        bytes = 1024,
        appVersion = "1.0.0.0",
        createdAt = 1000,
    )

    @Test fun roundTripAndRejectUnrelatedDocuments() {
        val original = manifest()
        assertEquals(original, CloudManifest.parse(original.caption()))
        assertNull(CloudManifest.parse("A personal document"))
        assertNull(CloudManifest.parse(CloudManifest.PREFIX + "broken"))
        assertFalse(original.copy(sha256 = "invalid").valid())
        assertFalse(original.copy(version = 99).valid())
    }

    @Test fun retentionKeepsCopiesForEachDeviceAndProtectedCopies() {
        val other = UUID.randomUUID().toString()
        val copies = (1L..24L).map { n ->
            CloudCopy(manifest(deviceId = if (n % 2L == 0L) device else other), 1, 2, n, n, n, n == 1L)
        }
        val removed = CloudRetention.obsolete(copies, 10, 0, 100)
        assertEquals(listOf(2L, 3L, 4L), removed.map { it.messageId }.sorted())
        assertFalse(removed.any { it.pinned })
    }

    @Test fun protectedManifestsRequireCompleteKeyMetadataWithoutBreakingLegacyBackups() {
        val legacy = manifest()
        val space = UUID.randomUUID().toString()
        val encrypted = legacy.copy(version = 2, protectionSpace = space, protectionEpoch = 3)
        assertEquals(encrypted, CloudManifest.parse(encrypted.caption()))
        assertTrue(encrypted.protected)
        assertFalse(legacy.protected)
        assertFalse(legacy.copy(protectionSpace = space).valid())
        assertFalse(encrypted.copy(protectionEpoch = null).valid())
        assertFalse(encrypted.copy(protectionSpace = "invalid").valid())
        assertFalse(encrypted.copy(protectionEpoch = 0).valid())
    }

    @Test fun ageCannotRemoveLastCopyAndDuplicateEventsDoNotCountTwice() {
        val latest = CloudCopy(manifest(), 1, 2, 9, 4, 900)
        val old = CloudCopy(manifest(), 1, 2, 3, 3, 100)
        val removed = CloudRetention.obsolete(listOf(latest, latest, old), 10, 1, 900_000)
        assertEquals(listOf(old), removed)
        assertTrue(CloudRetention.obsolete(listOf(latest), 1, 1, 900_000).isEmpty())
    }
}
