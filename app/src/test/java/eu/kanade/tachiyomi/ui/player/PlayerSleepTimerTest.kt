package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerSleepTimerTest {
    @Test
    fun `episode timer waits for the selected episode and expires once`() = runTest {
        var expired = 0
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) { expired++ }
        timer.stopAtEpisodeEnd(7)
        advanceTimeBy(7_200_000)
        assertEquals(0, expired)
        assertFalse(timer.onEpisodeEnded(6))
        assertEquals(7L, timer.endEpisodeId.value)
        assertTrue(timer.onEpisodeEnded(7))
        assertTrue(timer.onEpisodeEnded(7))
        assertEquals(1, expired)
        assertNull(timer.endEpisodeId.value)
        assertFalse(timer.allowsAutoPlay(7))
        timer.acknowledgeUserPlayback()
        assertTrue(timer.allowsAutoPlay(7))
    }

    @Test
    fun `quality reload preserves end mode but explicit different episode cancels it`() = runTest {
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) {}
        timer.stopAtEpisodeEnd(7)
        timer.onEpisodeChanged(7)
        assertEquals(7L, timer.endEpisodeId.value)
        timer.onEpisodeChanged(8)
        assertNull(timer.endEpisodeId.value)
        assertTrue(timer.allowsAutoPlay(8))
    }

    @Test
    fun `duration timer keeps its deadline across episode changes`() = runTest {
        var expired = 0
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) { expired++ }
        timer.start(60)
        advanceTimeBy(20_000)
        timer.onEpisodeChanged(8)
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(1, expired)
    }

    @Test
    fun `switching timer modes cancels the old trigger`() = runTest {
        var expired = 0
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) { expired++ }
        timer.start(10)
        timer.stopAtEpisodeEnd(7)
        advanceTimeBy(20_000)
        assertEquals(0, expired)
        timer.start(60)
        assertNull(timer.endEpisodeId.value)
        assertFalse(timer.onEpisodeEnded(7))
        timer.stopAtEpisodeEnd(7)
        timer.start(0)
        assertFalse(timer.onEpisodeEnded(7))
        advanceTimeBy(120_000)
        assertEquals(0, expired)
    }

    @Test
    fun `adding time preserves the exact deadline between ticks`() = runTest {
        var expired = 0
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) { expired++ }
        timer.start(10)
        advanceTimeBy(1250)
        timer.extend(900)
        assertEquals(909, timer.remainingTime.value)
        advanceTimeBy(908749)
        runCurrent()
        assertEquals(0, expired)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, expired)
    }

    @Test
    fun `add time never revives a cancelled or already due timer`() = runTest {
        var clock = 0L
        var expired = 0
        val timer = PlayerSleepTimer(backgroundScope, { clock }) { expired++ }
        timer.extend(900)
        assertEquals(0, timer.remainingTime.value)
        timer.start(10)
        runCurrent()
        clock = 10001L
        timer.extend(900)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, expired)
        assertEquals(0, timer.remainingTime.value)
        timer.start(60)
        timer.start(0)
        timer.extend(900)
        assertEquals(0, timer.remainingTime.value)
    }

    @Test
    fun `repeated additions are bounded without integer overflow`() = runTest {
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) {}
        timer.start(Int.MAX_VALUE)
        timer.extend(Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, timer.remainingTime.value)
        timer.extend(-1)
        assertEquals(Int.MAX_VALUE, timer.remainingTime.value)
    }

    @Test
    fun `timer expires exactly at its deadline and only once`() = runTest {
        var expired = 0
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) { expired++ }
        timer.start(30 * 60)
        assertEquals(1800, timer.remainingTime.value)
        advanceTimeBy(1_799_000)
        runCurrent()
        assertEquals(1, timer.remainingTime.value)
        assertEquals(0, expired)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(0, timer.remainingTime.value)
        assertEquals(1, expired)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(1, expired)
    }

    @Test
    fun `delayed callbacks catch up with elapsed time instead of extending the timer`() = runTest {
        var clock = 0L
        var expired = 0
        val timer = PlayerSleepTimer(backgroundScope, { clock }) { expired++ }
        timer.start(60)
        runCurrent()
        clock = 65_000L
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, expired)
        assertEquals(0, timer.remainingTime.value)
    }

    @Test
    fun `replacing or cancelling a timer cannot trigger its old deadline`() = runTest {
        var expired = 0
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) { expired++ }
        timer.start(10)
        advanceTimeBy(9000)
        timer.start(30)
        advanceTimeBy(1500)
        runCurrent()
        assertEquals(0, expired)
        timer.start(0)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, expired)
        assertEquals(0, timer.remainingTime.value)
    }

    @Test
    fun `large durations do not overflow and negative durations cancel`() = runTest {
        var expired = 0
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) { expired++ }
        timer.start(Int.MAX_VALUE)
        runCurrent()
        assertEquals(Int.MAX_VALUE, timer.remainingTime.value)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(Int.MAX_VALUE - 1, timer.remainingTime.value)
        timer.start(-1)
        runCurrent()
        assertEquals(0, timer.remainingTime.value)
        assertEquals(0, expired)
    }
}
