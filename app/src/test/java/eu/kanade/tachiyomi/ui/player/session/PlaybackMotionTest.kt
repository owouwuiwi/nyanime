package eu.kanade.tachiyomi.ui.player.session

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackMotionTest {
    private val page = PlaybackFrame(PlaybackRect(0f, 24f, 360f, 203f), 1f, 1f)
    private val mini = PlaybackFrame(PlaybackRect(152f, 580f, 196f, 110f), 0f, 0f)
    private fun clock() = object : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test
    fun `interrupting and reversing preserves current geometry and visibility`() = runTest {
        val motion = PlaybackMotion(page)
        val frames = clock()
        val first = launch(frames) { motion.moveTo(mini, 240) }
        advanceTimeBy(112)
        runCurrent()
        val interrupted = motion.current
        assertTrue(interrupted.rect.width in 197f..359f)
        assertTrue(interrupted.page in 0.01f..0.99f)
        first.cancel()
        first.join()
        val reverse = launch(frames) { motion.moveTo(page, 240) }
        runCurrent()
        assertEquals(interrupted, motion.current)
        reverse.join()
        assertEquals(page, motion.current)
    }

    @Test
    fun `settling a gesture starts at the last finger position`() = runTest {
        val motion = PlaybackMotion(page)
        val dragged = page.interpolate(mini, 0.43f)
        motion.follow(dragged)
        val settle = launch(clock()) { motion.moveTo(mini, 240) }
        runCurrent()
        assertEquals(dragged, motion.current)
        settle.join()
        assertEquals(mini, motion.current)
    }

    @Test
    fun `reduced motion reaches all target values together`() = runTest {
        val motion = PlaybackMotion(page)
        motion.follow(page.interpolate(mini, 0.6f))
        motion.moveTo(mini, 0)
        assertEquals(mini, motion.current)
    }
}
