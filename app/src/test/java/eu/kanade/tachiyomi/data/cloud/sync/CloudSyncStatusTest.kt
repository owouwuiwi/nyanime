package eu.kanade.tachiyomi.data.cloud.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CloudSyncStatusTest {
    @Test fun knownUnresolvedDependenciesAndManualEmptyChecksDoNotFlashTheStatusCard() {
        listOf(CloudSyncPhase.CURRENT, CloudSyncPhase.WAITING_CONTENT).forEach { phase ->
            val status = CloudSyncStatus(phase = phase, projections = 1, lastVerified = 100)
            assertEquals(phase, status.receiving(explicit = false, captured = 0))
            assertEquals(phase, status.receiving(explicit = true, captured = 0))
            assertEquals(CloudSyncPhase.RECEIVING, status.receiving(explicit = false, captured = 1))
        }
    }

    @Test fun periodicEmptyChecksDoNotFlashOrHideUnresolvedFailures() {
        listOf(
            CloudSyncPhase.CURRENT,
            CloudSyncPhase.NETWORK,
            CloudSyncPhase.RATE_LIMIT,
            CloudSyncPhase.ERROR,
        ).forEach {
            assertEquals(it, CloudSyncStatus(phase = it).receiving(explicit = false, captured = 0))
        }
    }

    @Test fun manualChecksAndPendingWorkRemainVisible() {
        val current = CloudSyncStatus(phase = CloudSyncPhase.CURRENT)
        assertEquals(CloudSyncPhase.RECEIVING, current.receiving(explicit = true, captured = 0))
        assertEquals(CloudSyncPhase.RECEIVING, current.receiving(explicit = false, captured = 1))
        assertEquals(CloudSyncPhase.RECEIVING, current.copy(pending = 1).receiving(false, 0))
        assertEquals(CloudSyncPhase.RECEIVING, current.copy(projections = 1).receiving(false, 0))
    }

    @Test fun firstConnectionStillShowsTheInitialDownload() {
        assertEquals(
            CloudSyncPhase.RECEIVING,
            CloudSyncStatus(phase = CloudSyncPhase.CONNECTING).receiving(false, 0),
        )
    }
}
