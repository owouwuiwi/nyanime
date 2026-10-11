package it.lorelore.nyanime.cloud.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncCheckpointScheduleTest {
    @Test fun aLargeBurstDoesNotCreateRepeatedFullCopies() {
        for (pending in listOf(257L, 10_000L, 100_000L)) {
            assertFalse(SyncCheckpointSchedule.due(200_001, 200_000, pending))
        }
    }

    @Test fun dailyDueRequiresRealPendingChanges() {
        assertTrue(SyncCheckpointSchedule.due(300_000, 200_000, 1))
        assertFalse(SyncCheckpointSchedule.due(300_000, 200_000, 0))
    }

    @Test fun aPeerCopyAndClockRegressionDoNotTriggerAnotherCopy() {
        assertFalse(SyncCheckpointSchedule.due(300_000, 299_999, 500))
        assertFalse(SyncCheckpointSchedule.due(199_000, 200_000, 500))
    }
}
