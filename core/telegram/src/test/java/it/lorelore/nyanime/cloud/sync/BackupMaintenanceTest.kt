package it.lorelore.nyanime.cloud.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import nyanime.telegram.CloudCopy
import nyanime.telegram.CloudManifest
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.UUID

class BackupMaintenanceTest {
    private class Port : BackupMaintenancePort {
        val values = mutableMapOf<String, String>()
        val device = UUID.randomUUID().toString()
        val copies = (1L..25L).map { message ->
            CloudCopy(
                CloudManifest(
                    backupId = UUID.randomUUID().toString(),
                    deviceId = device,
                    deviceName = "Test device",
                    sha256 = "a".repeat(64),
                    bytes = 100,
                    appVersion = "1",
                    createdAt = 1,
                ),
                12,
                -100,
                message,
                message,
                message,
            )
        }.toMutableList()
        val deleted = mutableListOf<Long>()
        var failure: Exception? = null
        var idle = true
        var inventories = 0
        override fun state(key: String) = values[key]
        override fun state(key: String, value: String) {
            values[key] = value
        }
        override suspend fun serverTime() = 2_000_000L
        override suspend fun inventory(): List<CloudCopy> {
            inventories++
            return copies.toList()
        }
        override suspend fun deleteVerified(copy: CloudCopy): Boolean {
            failure?.let { throw it }
            if (!idle) return false
            deleted += copy.messageId
            copies.removeAll { it.messageId == copy.messageId }
            return true
        }
    }

    @Test fun failedRetentionRetriesEvenWhenThereIsNoNewBackup() = runTest {
        val p = Port()
        p.failure = IllegalStateException("Network unavailable")
        val now = 2_000_000_000L
        BackupMaintenance(p).run(12, -100, 10, 0, now)
        assertEquals("true", p.values["pending"])
        assertTrue(p.deleted.isEmpty())
        p.failure = null
        BackupMaintenance(p).run(12, -100, 10, 0, now + 1)
        assertEquals(1, p.inventories)
        BackupMaintenance(p).run(12, -100, 10, 0, now + 15 * 60_000)
        assertEquals(10, p.deleted.size)
        assertEquals("true", p.values["pending"])
        BackupMaintenance(p).run(12, -100, 10, 0, now + 30 * 60_000)
        assertEquals(15, p.deleted.size)
        assertEquals("false", p.values["pending"])
        assertEquals(10, p.copies.size)
        assertTrue(p.copies.any { it.messageId == 25L })
    }

    @Test fun pinsAndTheNewestCopyOfEachDeviceRemainProtected() = runTest {
        val p = Port()
        p.copies[0] = p.copies[0].copy(pinned = true)
        val other = p.copies[1].copy(manifest = p.copies[1].manifest.copy(deviceId = UUID.randomUUID().toString()))
        p.copies[1] = other
        repeat(3) { n -> BackupMaintenance(p).run(12, -100, 1, 0, 2_000_000_000L + n * 15 * 60_000L) }
        assertTrue(p.copies.any { it.messageId == 1L && it.pinned })
        assertTrue(p.copies.contains(other))
        assertTrue(p.copies.any { it.messageId == 25L })
    }

    @Test fun aMixedAccountInventoryCannotDeleteAnything() = runTest {
        val p = Port()
        p.copies[0] = p.copies[0].copy(accountId = 99)
        BackupMaintenance(p).run(12, -100, 1, 0, 2_000_000_000L)
        assertTrue(p.deleted.isEmpty())
        assertEquals("true", p.values["pending"])
    }

    @Test fun cancellationKeepsTheRetryDurableAndPropagates() = runTest {
        val p = Port()
        p.failure = CancellationException("Interrupted")
        try {
            BackupMaintenance(p).run(12, -100, 1, 0, 2_000_000_000L)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("true", p.values["pending"])
    }

    @Test fun serverWaitAndActivePlaybackDoNotTriggerRepeatedDeletionAttempts() = runTest {
        val p = Port()
        p.failure = TelegramException(TelegramFailure.RATE_LIMIT, retryAfterSeconds = 7200)
        BackupMaintenance(p).run(12, -100, 1, 0, 2_000_000_000L)
        assertEquals("2007200000", p.values["retry"])
        p.failure = null
        p.idle = false
        BackupMaintenance(p).run(12, -100, 1, 0, 2_000_000_000L + 7200_000)
        assertTrue(p.deleted.isEmpty())
        assertEquals("true", p.values["pending"])
    }
}
