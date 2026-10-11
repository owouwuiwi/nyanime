package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlayerPlaybackCompletionTest {
    @Test
    fun `duplicate eof never extends countdown and advances only once`() = runTest {
        val played = mutableListOf<Long>()
        val completion = PlayerPlaybackCompletion(backgroundScope, { testScheduler.currentTime }, { true }, played::add)
        completion.onEnded(8, true)
        advanceTimeBy(4000)
        runCurrent()
        assertEquals(6, completion.prompt.value?.secondsRemaining)
        completion.onEnded(8, true)
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(listOf(8L), played)
        assertNull(completion.prompt.value)
        completion.onEnded(8, true)
        advanceTimeBy(20_000)
        assertEquals(listOf(8L), played)
    }

    @Test
    fun `play now and scheduled advance cannot both start an episode`() = runTest {
        val played = mutableListOf<Long>()
        val completion = PlayerPlaybackCompletion(backgroundScope, { testScheduler.currentTime }, { true }, played::add)
        completion.onEnded(8, true)
        advanceTimeBy(9999)
        completion.playNow()
        completion.playNow()
        advanceTimeBy(10_000)
        assertEquals(listOf(8L), played)
    }

    @Test
    fun `cancel consumes eof until playback actually restarts`() = runTest {
        val played = mutableListOf<Long>()
        val completion = PlayerPlaybackCompletion(backgroundScope, { testScheduler.currentTime }, { true }, played::add)
        completion.onEnded(8, true)
        completion.cancel()
        completion.onEnded(8, true)
        advanceTimeBy(20_000)
        assertEquals(emptyList<Long>(), played)
        assertNull(completion.prompt.value)
        completion.playbackRestarted()
        completion.onEnded(9, true)
        completion.playNow()
        assertEquals(listOf(9L), played)
    }

    @Test
    fun `last episode and disabled autoplay have no prompt or work`() = runTest {
        var played = 0
        val completion = PlayerPlaybackCompletion(backgroundScope, { testScheduler.currentTime }, { true }) { played++ }
        completion.onEnded(null, true)
        assertNull(completion.prompt.value)
        completion.playbackRestarted()
        completion.onEnded(8, false)
        assertNull(completion.prompt.value)
        advanceTimeBy(20_000)
        assertEquals(0, played)
    }

    @Test
    fun `eligibility is rechecked for both deadline and immediate play`() = runTest {
        var allowed = true
        var played = 0
        val completion =
            PlayerPlaybackCompletion(backgroundScope, { testScheduler.currentTime }, { allowed }) { played++ }
        completion.onEnded(8, true)
        allowed = false
        completion.playNow()
        assertNull(completion.prompt.value)
        allowed = true
        completion.playbackRestarted()
        completion.onEnded(8, true)
        advanceTimeBy(3000)
        allowed = false
        runCurrent()
        assertNull(completion.prompt.value)
        advanceTimeBy(20_000)
        assertEquals(0, played)
    }

    @Test
    fun `delayed callbacks use elapsed time and never prolong countdown`() = runTest {
        var clock = 0L
        var played = 0
        val completion = PlayerPlaybackCompletion(backgroundScope, { clock }, { true }) { played++ }
        completion.onEnded(8, true)
        runCurrent()
        clock = 15_000
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, played)
    }

    @Test
    fun `episode end timer stops before next episode prompt`() = runTest {
        var stopped = 0
        var played = 0
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) { stopped++ }
        val completion = PlayerPlaybackCompletion(
            backgroundScope,
            { testScheduler.currentTime },
            { timer.allowsAutoPlay(7) },
        ) { played++ }
        timer.stopAtEpisodeEnd(7)
        timer.onEpisodeEnded(7)
        completion.onEnded(8, true)
        advanceTimeBy(20_000)
        assertEquals(1, stopped)
        assertEquals(0, played)
        assertNull(completion.prompt.value)
    }

    @Test
    fun `sleep deadline wins even when its coroutine has not run yet`() = runTest {
        var clock = 0L
        var stopped = 0
        var played = 0
        val timer = PlayerSleepTimer(backgroundScope, { clock }) { stopped++ }
        val completion = PlayerPlaybackCompletion(backgroundScope, { clock }, { timer.allowsAutoPlay(7) }) { played++ }
        timer.start(10)
        completion.onEnded(8, true)
        // Neither scheduled job has executed. Both decisions must still honor the exact deadline.
        clock = 10_000
        completion.playNow()
        runCurrent()
        assertEquals(1, stopped)
        assertEquals(0, played)
        assertFalse(timer.allowsAutoPlay(7))
        assertNull(completion.prompt.value)
    }

    @Test
    fun `sleep expiration cancels an already visible countdown`() = runTest {
        var played = 0
        lateinit var completion: PlayerPlaybackCompletion
        val timer = PlayerSleepTimer(backgroundScope, { testScheduler.currentTime }) { completion.cancel() }
        completion = PlayerPlaybackCompletion(
            backgroundScope,
            { testScheduler.currentTime },
            { timer.allowsAutoPlay(7) },
        ) { played++ }
        timer.start(3)
        completion.onEnded(8, true)
        advanceTimeBy(3000)
        runCurrent()
        assertNull(completion.prompt.value)
        advanceTimeBy(20_000)
        assertEquals(0, played)
    }
}
