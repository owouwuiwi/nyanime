package it.lorelore.nyanime.backup.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RemoteBackupStatusTest {
    @Test fun `successful automatic backup clears an older manual failure`() {
        assertNull(latestRemoteBackupError(200, listOf(RemoteBackupFailure(100, "network"))))
    }

    @Test fun `latest failure remains visible without erasing the last verified success`() {
        val outcomes = listOf(RemoteBackupFailure(250, "space"), RemoteBackupFailure(300, "authorization"))
        assertEquals("authorization", latestRemoteBackupError(200, outcomes))
        assertNull(latestRemoteBackupError(400, outcomes))
    }
}
