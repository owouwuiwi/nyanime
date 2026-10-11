package eu.kanade.tachiyomi.ui.entries.anime

import eu.kanade.tachiyomi.data.library.anime.AnimeForegroundRefreshGate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimeForegroundRefreshGateTest {
    @Test
    fun `opening a cached title checks it once and keeps a successful list fresh briefly`() {
        val id = 901L
        assertTrue(AnimeForegroundRefreshGate.tryBegin(id, 1_000L))
        assertFalse(AnimeForegroundRefreshGate.tryBegin(id, 1_001L))
        AnimeForegroundRefreshGate.finish(id, 2_000L, successful = true)
        assertFalse(AnimeForegroundRefreshGate.tryBegin(id, 2_000L + 9 * 60_000L))
        assertTrue(AnimeForegroundRefreshGate.tryBegin(id, 2_000L + 10 * 60_000L))
    }

    @Test
    fun `a failed source can be retried after a minute without waiting ten minutes`() {
        val id = 902L
        assertTrue(AnimeForegroundRefreshGate.tryBegin(id, 1_000L))
        AnimeForegroundRefreshGate.finish(id, 1_001L, successful = false)
        assertFalse(AnimeForegroundRefreshGate.tryBegin(id, 60_999L))
        assertTrue(AnimeForegroundRefreshGate.tryBegin(id, 61_000L))
        AnimeForegroundRefreshGate.finish(id, 61_001L, successful = true)
        assertTrue(AnimeForegroundRefreshGate.tryBegin(id, 661_001L))
        AnimeForegroundRefreshGate.finish(id, 661_002L, successful = false)
        assertFalse(AnimeForegroundRefreshGate.tryBegin(id, 721_000L))
        assertTrue(AnimeForegroundRefreshGate.tryBegin(id, 721_001L))
    }

    @Test
    fun `leaving a title before a check finishes permits an immediate new check`() {
        val id = 903L
        assertTrue(AnimeForegroundRefreshGate.tryBegin(id, 1_000L))
        AnimeForegroundRefreshGate.finish(id, 1_001L, successful = false, cancelled = true)
        assertTrue(AnimeForegroundRefreshGate.tryBegin(id, 1_002L))
    }
}
