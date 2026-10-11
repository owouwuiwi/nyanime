package eu.kanade.tachiyomi.data.migration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationLeaseTest {
    @Test fun stoppedExporterAndRebootCannotLeaveAnOldLock() {
        assertTrue(MigrationLease.active(20_000, 60_000, 10_000, 50_000))
        assertFalse(MigrationLease.active(20_000, 60_000, 20_000, 60_000))
        assertFalse(MigrationLease.active(20_000, 60_000, 11_000, 500))
    }

    @Test fun clockCorrectionsDoNotExtendLease() {
        assertFalse(MigrationLease.active(20_000, 60_000, 1_000, 51_000))
        assertFalse(MigrationLease.active(20_000, 60_000, 21_000, 51_000))
        assertFalse(MigrationLease.active(20_000, 60_000, 11_000, 61_000))
    }
}
