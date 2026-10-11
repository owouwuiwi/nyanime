package it.lorelore.nyanime.cloud.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncProjectionPassTest {
    @Test fun fiftyAndFortyThreeRowsAreOnePassRatherThanAlternatingTotals() {
        val pass = SyncProjectionPass()
        assertTrue(pass.begin("revision", 93, 0))
        var progress: SyncWorkProgress? = null
        repeat(50) { progress = pass.checked("key-$it", 1, false) }
        assertEquals(50L, progress?.completed)
        assertEquals(93L, progress?.total)
        assertTrue(pass.begin("revision", 93, 1000))
        repeat(43) { progress = pass.checked("rest-$it", 2, false) }
        assertEquals(93L, progress?.completed)
        assertEquals(93L, progress?.total)
        assertFalse(requireNotNull(pass.finish(2000, 2)).active)
    }

    @Test fun unchangedUnresolvedRowsDoNotRestartTheIndicatorEveryTick() {
        val pass = completed()
        assertFalse(pass.begin("revision", 1, 1000))
        assertTrue(pass.begin("revision", 1, 30_001))
        assertNull(pass.checked("still-missing", 30, false))
        assertNull(pass.finish(30_002, 30))
    }

    @Test fun newRemoteDataAndExplicitSourceChangesDoNotWaitForTheRetry() {
        val pass = completed()
        assertTrue(pass.begin("new-revision", 1, 2))
        assertEquals(1L, pass.checked("new", 1, false)?.completed)
        pass.finish(3, 1)
        assertTrue(pass.begin("new-revision", 1, 4, force = true))
        assertEquals(1L, pass.checked("extension-now-installed", 1, true)?.completed)
    }

    @Test fun pausePreservesTheCursorAndCompletedRows() {
        val pass = SyncProjectionPass()
        pass.begin("revision", 2, 0)
        pass.checked("first", 1, false)
        assertTrue(pass.begin("revision", 2, 1000))
        assertEquals("first", pass.cursor)
        assertEquals(2L, pass.checked("second", 2, false)?.completed)
    }

    @Test fun aQuietRetryThatActuallyAppliesDataReportsTheCompletedWork() {
        val pass = completed()
        pass.begin("revision", 1, 30_001)
        assertEquals(1L, pass.checked("now-available", 30, true)?.completed)
        assertFalse(requireNotNull(pass.finish(30_002, 30)).active)
    }

    @Test fun emptyQueuesNeverCreateSpinningWork() {
        assertFalse(SyncProjectionPass().begin("revision", 0, 0))
    }

    private fun completed(): SyncProjectionPass = SyncProjectionPass().apply {
        begin("revision", 1, 0)
        checked("missing", 1, false)
        finish(1, 1)
    }
}
