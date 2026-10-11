package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackLoadMonitorTest {
    @Test
    fun cachedMediaRecoversAnIntentionalRoomPauseWithoutAFakeSourceTimeout() = runTest {
        var timeouts = 0
        val monitor = PlaybackLoadMonitor(backgroundScope, { timeouts++ }, 1000)
        monitor.begin()
        assertFalse(monitor.recoverPausedCache(true, false, 10.0, 100.0))
        monitor.restarted(false)
        monitor.buffering(true)
        runCurrent()
        assertFalse(monitor.recoverPausedCache(true, true, 10.0, 100.0))
        assertFalse(monitor.recoverPausedCache(true, null, 10.0, 100.0))
        assertFalse(monitor.recoverPausedCache(true, false, 0.5, 100.0))
        assertTrue(monitor.recoverPausedCache(true, false, 3.0, 100.0))
        advanceTimeBy(1500)
        runCurrent()
        assertEquals(0, timeouts)
        assertTrue(monitor.state.value.canSaveProgress)
    }

    @Test
    fun `cache and seek are independent and cannot hide initial loading`() = runTest {
        val monitor = PlaybackLoadMonitor(backgroundScope, {})
        monitor.begin()
        monitor.buffering(false)
        monitor.seeking(false)
        assertTrue(monitor.state.value.loading)
        monitor.restarted(true)
        monitor.seeking(false)
        assertTrue(monitor.state.value.loading)
        monitor.buffering(false)
        assertFalse(monitor.state.value.loading)
        monitor.seeking(true)
        monitor.buffering(false)
        assertTrue(monitor.state.value.loading)
    }

    @Test
    fun `restart ends seek but never hides cache wait`() = runTest {
        val monitor = PlaybackLoadMonitor(backgroundScope, {})
        monitor.begin()
        monitor.seeking(true)
        monitor.restarted(false)
        assertTrue(monitor.state.value.canSaveProgress)
        monitor.buffering(true)
        assertFalse(monitor.state.value.canSaveProgress)
    }

    @Test
    fun `error terminates loading and ignores late native properties`() = runTest {
        val monitor = PlaybackLoadMonitor(backgroundScope, {})
        monitor.begin()
        monitor.fail(PlaybackFailure.Format)
        monitor.buffering(true)
        monitor.restarted(false)
        assertFalse(monitor.state.value.loading)
        assertFalse(monitor.state.value.canSaveProgress)
        assertEquals(PlaybackFailure.Format, monitor.state.value.failure)
        monitor.begin()
        assertTrue(monitor.state.value.loading)
        assertEquals(null, monitor.state.value.failure)
    }

    @Test
    fun `repeated events do not postpone the timeout`() = runTest {
        var timeouts = 0
        val monitor = PlaybackLoadMonitor(backgroundScope, { timeouts++ }, 1000)
        monitor.begin()
        runCurrent()
        advanceTimeBy(900)
        monitor.buffering(true)
        monitor.seeking(false)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, timeouts)
        assertEquals(PlaybackFailure.Timeout, monitor.state.value.failure)
        advanceTimeBy(2000)
        assertEquals(1, timeouts)
    }

    @Test
    fun `new media and disposal cancel the previous timeout`() = runTest {
        var timeouts = 0
        val monitor = PlaybackLoadMonitor(backgroundScope, { timeouts++ }, 1000)
        monitor.begin()
        runCurrent()
        advanceTimeBy(900)
        monitor.begin()
        runCurrent()
        advanceTimeBy(200)
        assertEquals(0, timeouts)
        monitor.cancel()
        advanceTimeBy(2000)
        assertEquals(0, timeouts)
        assertFalse(monitor.state.value.loading)
    }

    @Test
    fun `native errors have safe categories without exposing URLs`() {
        assertEquals(PlaybackFailure.Format, PlaybackFailure.fromNative("unrecognized file format"))
        assertEquals(PlaybackFailure.Network, PlaybackFailure.fromNative("HTTP error 403"))
        assertEquals(PlaybackFailure.Unknown, PlaybackFailure.fromNative("Something else"))
    }
}
