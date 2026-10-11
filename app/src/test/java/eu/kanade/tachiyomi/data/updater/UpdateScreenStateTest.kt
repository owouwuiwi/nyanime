package eu.kanade.tachiyomi.data.updater

import androidx.work.WorkInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateScreenStateTest {
    @Test
    fun `success waits for validation and never offers installation for a removed or stale APK`() {
        assertEquals(UpdateScreenPhase.VERIFYING, updateScreenPhase(WorkInfo.State.SUCCEEDED, false, false))
        assertEquals(UpdateScreenPhase.READY, updateScreenPhase(WorkInfo.State.SUCCEEDED, true, true))
        assertEquals(UpdateScreenPhase.UNAVAILABLE, updateScreenPhase(WorkInfo.State.SUCCEEDED, true, false))
        assertTrue(UpdateScreenPhase.VERIFYING.busy)
        assertFalse(UpdateScreenPhase.UNAVAILABLE.busy)
    }

    @Test
    fun `active work blocks duplicate downloads regardless of installation preference`() {
        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)) {
            val phase = updateScreenPhase(state, true, true)
            assertTrue(phase.busy)
            assertTrue(phase.cancellable)
            assertFalse(phase == UpdateScreenPhase.READY)
        }
    }

    @Test
    fun `failure and cancellation can be retried without losing release information`() {
        assertEquals(UpdateScreenPhase.AVAILABLE, updateScreenPhase(null, false, false))
        assertEquals(UpdateScreenPhase.FAILED, updateScreenPhase(WorkInfo.State.FAILED, true, true))
        assertEquals(UpdateScreenPhase.CANCELLED, updateScreenPhase(WorkInfo.State.CANCELLED, true, true))
        assertFalse(UpdateScreenPhase.FAILED.busy)
        assertFalse(UpdateScreenPhase.CANCELLED.busy)
    }
}
