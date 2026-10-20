package it.lorelore.nyanime.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackingControlsTest {
    @Test fun `completing a record includes its known total and preserves its score`() {
        val before = TrackingValues(progress = 3.5, score = 8.2)
        val patch = TrackingControls.status(before, 20, completed = true, total = 12)
        assertEquals(setOf(TrackingField.STATUS, TrackingField.PROGRESS), patch.fields)
        assertEquals(before.copy(status = 20, progress = 12.0), patch.applyTo(before))
        assertEquals(before, patch.inverse(before, patch.applyTo(before)).applyTo(patch.applyTo(before)))
    }

    @Test fun `unknown totals never invent a completion progress`() {
        val patch = TrackingControls.status(TrackingValues(progress = 4.5), 20, completed = true, total = 0)
        assertEquals(setOf(TrackingField.STATUS), patch.fields)
        assertEquals(4.5, patch.values.progress)
    }

    @Test fun `first manual progress starts planned tracking while preserving repeat status`() {
        val planned = TrackingValues(status = 60)
        assertEquals(10L, TrackingControls.progress(planned, 1.0, 12, 10, 30, 20, 500).values.status)
        val repeating = planned.copy(status = 30)
        assertEquals(30L, TrackingControls.progress(repeating, 1.0, 12, 10, 30, 20, 500).values.status)
    }

    @Test fun `known completion sets a supported finish date without replacing an existing date`() {
        val patch = TrackingControls.progress(TrackingValues(), 12.0, 12, 10, 30, 20, 500)
        assertEquals(20L, patch.values.status)
        assertEquals(500L, patch.values.finishDate)
        assertTrue(TrackingField.FINISH in patch.fields)
        val existing = TrackingControls.progress(TrackingValues(finishDate = 300), 12.0, 12, 10, 30, 20, 500)
        assertEquals(300L, existing.values.finishDate)
        val unsupported = TrackingControls.progress(TrackingValues(), 12.0, 12, 10, 30, 20, null)
        assertFalse(TrackingField.FINISH in unsupported.fields)
    }
}
