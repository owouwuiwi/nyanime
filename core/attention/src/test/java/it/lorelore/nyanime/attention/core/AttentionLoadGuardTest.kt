package it.lorelore.nyanime.attention.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttentionLoadGuardTest {
    private val frame = AttentionPlayback("test-video", 0, true)

    @Test fun repeatedLossesReduceCameraRateThenSuspendIt() {
        val guard = AttentionLoadGuard()
        for (second in 0L..2) guard.sample(second * 1_000, frame.copy(renderDrops = second))
        assertEquals(500, guard.intervalMs)
        assertFalse(guard.suspended)
        guard.sample(3_000, frame.copy(renderDrops = 3))
        guard.sample(4_000, frame.copy(renderDrops = 4))
        assertTrue(guard.suspended)
    }

    @Test fun isolatedPeaksUnknownCountersAndBufferingNeverSuspend() {
        val guard = AttentionLoadGuard()
        for (second in 0L..40) {
            guard.sample(second * 1_000, frame.copy(renderDrops = second / 3))
        }
        assertFalse(guard.suspended)
        repeat(10) { guard.sample(50_000 + it * 1_000L, frame) }
        assertFalse(guard.suspended)
        repeat(10) { guard.sample(70_000 + it * 1_000L, frame.copy(renderDrops = it.toLong(), buffering = true)) }
        assertFalse(guard.suspended)
    }
}
