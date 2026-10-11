package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackScrubCoordinatorTest {
    @Test
    fun `playing preview keeps its initial clock and resumes after one commit`() {
        var time = 0L
        val scrub = PlaybackScrubCoordinator { time }
        assertTrue(scrub.begin("session-1", 100f, 600f, false, 1f))
        scrub.update(240f)
        time = 2000
        assertEquals(102f, scrub.clockPosition())
        assertEquals(100f, scrub.state.value!!.origin)
        assertEquals(240f, scrub.state.value!!.target)
        assertEquals(PlaybackScrubCoordinator.Completion(240f, false), scrub.finish("session-1", true))
        assertNull(scrub.finish("session-1", true))
    }

    @Test
    fun `initially paused playback stays paused and shared clock does not advance`() {
        var time = 0L
        val scrub = PlaybackScrubCoordinator { time }
        scrub.begin("session-1", 80f, 600f, true, 2f)
        scrub.update(40f)
        time = 8000
        assertEquals(80f, scrub.clockPosition())
        assertEquals(PlaybackScrubCoordinator.Completion(40f, true), scrub.finish("session-1", true))
    }

    @Test
    fun `cancel never commits and restores original playback intent`() {
        val scrub = PlaybackScrubCoordinator()
        scrub.begin("session-1", 80f, 600f, false, 1f)
        scrub.update(400f)
        assertEquals(PlaybackScrubCoordinator.Completion(null, false), scrub.finish("session-1", false))
    }

    @Test
    fun `background cancellation cannot accidentally resume playback`() {
        val scrub = PlaybackScrubCoordinator()
        scrub.begin("session-1", 80f, 600f, false, 1f)
        assertEquals(PlaybackScrubCoordinator.Completion(null, true), scrub.finish("session-1", false, false))
    }

    @Test
    fun `another playback identity revokes both commit and restore`() {
        val scrub = PlaybackScrubCoordinator()
        scrub.begin("session-1", 80f, 600f, false, 1f)
        scrub.update(400f)
        assertNull(scrub.finish("session-2", true))
        assertFalse(scrub.active)
    }

    @Test
    fun `repeated begin preserves target and original play state`() {
        val scrub = PlaybackScrubCoordinator()
        scrub.begin("session-1", 80f, 600f, false, 1f)
        scrub.update(400f)
        assertTrue(scrub.begin("session-1", 80f, 600f, true, 1f))
        assertEquals(PlaybackScrubCoordinator.Completion(400f, false), scrub.finish("session-1", true))
    }

    @Test
    fun `next gesture starts from actual position and never a previous preview`() {
        val scrub = PlaybackScrubCoordinator()
        scrub.begin("session-1", 80f, 600f, false, 1f)
        scrub.update(400f)
        scrub.finish("session-1", false)
        scrub.begin("session-1", 85f, 600f, false, 1f)
        assertEquals(85f, scrub.state.value!!.target)
        assertEquals(PlaybackScrubCoordinator.Completion(null, false), scrub.finish("session-1", true))
    }

    @Test
    fun `bounds and direction reversals are independent of the paused native position`() {
        val scrub = PlaybackScrubCoordinator()
        scrub.begin("session-1", 0f, 600f, false, 1f)
        assertEquals(0f, scrub.update(-100f))
        assertEquals(600f, scrub.update(800f))
        assertEquals(120f, scrub.update(120f))
        assertNull(scrub.update(Float.NaN))
        assertEquals(120f, scrub.state.value!!.target)
    }

    @Test
    fun `invalid or unknown duration does not start a hold`() {
        val scrub = PlaybackScrubCoordinator()
        for (duration in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(scrub.begin("session-1", 10f, duration, false, 1f))
        }
        assertFalse(scrub.begin("session-1", Float.NaN, 600f, false, 1f))
        assertFalse(scrub.active)
    }

    @Test
    fun `latest remote playback intent wins without leaking the preview target`() {
        var time = 0L
        val scrub = PlaybackScrubCoordinator { time }
        scrub.begin("session-1", 80f, 600f, false, 2f)
        scrub.update(400f)
        time = 3000
        scrub.playbackIntent(true)
        time = 8000
        assertEquals(86f, scrub.clockPosition())
        assertEquals(400f, scrub.state.value!!.target)
        assertEquals(PlaybackScrubCoordinator.Completion(400f, true), scrub.finish("session-1", true))
    }

    @Test
    fun `a remote resume restarts the shared clock from its pause anchor`() {
        var time = 0L
        val scrub = PlaybackScrubCoordinator { time }
        scrub.begin("session-1", 80f, 600f, true, 1f)
        time = 8000
        scrub.playbackIntent(false)
        time = 10000
        assertEquals(82f, scrub.clockPosition())
        assertEquals(PlaybackScrubCoordinator.Completion(null, false), scrub.finish("session-1", false))
    }
}
