package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackSeekFeedbackTest {
    @Test
    fun `old progress cannot move a released thumb back before seek acknowledgement`() = runTest {
        val feedback = PlaybackSeekFeedback(backgroundScope)
        feedback.observe(25f)
        feedback.request(480f)
        feedback.observe(26f)
        feedback.observe(27f)
        assertEquals(480f, feedback.position.value)
        feedback.observe(480f)
        feedback.observe(481f)
        assertEquals(481f, feedback.position.value)
    }

    @Test
    fun `rewinding respects the requested position instead of a maximum reached second`() = runTest {
        val feedback = PlaybackSeekFeedback(backgroundScope)
        feedback.observe(900f)
        feedback.request(30f)
        feedback.observe(901f)
        assertEquals(30f, feedback.position.value)
        feedback.observe(30f)
        assertEquals(30f, feedback.position.value)
    }

    @Test
    fun `a newer request supersedes an unfinished seek and its deadline`() = runTest {
        val feedback = PlaybackSeekFeedback(backgroundScope, 1_000)
        feedback.observe(10f)
        feedback.request(100f)
        advanceTimeBy(700)
        feedback.request(300f)
        feedback.observe(100f)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(300f, feedback.position.value)
        feedback.observe(300f)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(300f, feedback.position.value)
    }

    @Test
    fun `native completion accepts the actual keyframe only after a native seek started`() = runTest {
        val feedback = PlaybackSeekFeedback(backgroundScope)
        feedback.observe(10f)
        feedback.request(300f)
        feedback.nativeFinished(10f)
        assertEquals(300f, feedback.position.value)
        feedback.nativeStarted()
        feedback.nativeFinished(297f)
        assertEquals(297f, feedback.position.value)
    }

    @Test
    fun `a failed seek expires to actual progress even while paused`() = runTest {
        val feedback = PlaybackSeekFeedback(backgroundScope, 1_000)
        feedback.observe(10f)
        feedback.request(300f)
        feedback.observe(11f)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(11f, feedback.position.value)
    }

    @Test
    fun `changing media cancels the old preview and timeout`() = runTest {
        val feedback = PlaybackSeekFeedback(backgroundScope, 1_000)
        feedback.observe(10f)
        feedback.request(300f)
        feedback.reset()
        feedback.observe(5f)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(5f, feedback.position.value)
    }

    @Test
    fun `invalid native samples and requests never put invalid coordinates on the timeline`() = runTest {
        val feedback = PlaybackSeekFeedback(backgroundScope)
        feedback.observe(10f)
        feedback.request(Float.NaN)
        feedback.observe(Float.POSITIVE_INFINITY)
        feedback.observe(-1f)
        assertEquals(10f, feedback.position.value)
    }
}
