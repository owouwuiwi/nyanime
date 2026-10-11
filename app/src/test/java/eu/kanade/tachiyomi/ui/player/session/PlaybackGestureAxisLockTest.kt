package eu.kanade.tachiyomi.ui.player.session

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackGestureAxisLockTest {
    @Test
    fun `horizontal seek cannot turn into downward dismissal after drifting vertically`() {
        val gesture = PlaybackGestureAxisLock(12f)
        assertEquals(PlaybackGestureAxis.Horizontal, gesture.sample(Offset(25f, 3f)))
        assertEquals(PlaybackGestureAxis.Horizontal, gesture.sample(Offset(25f, 500f)))
        assertEquals(PlaybackGestureAxis.Horizontal, gesture.sample(Offset(-40f, -600f)))
    }

    @Test
    fun `vertical presentation drag keeps ownership when the finger later moves sideways`() {
        for (direction in listOf(-1, 1)) {
            val gesture = PlaybackGestureAxisLock(12f)
            assertEquals(PlaybackGestureAxis.Vertical, gesture.sample(Offset(3f, direction * 25f)))
            assertEquals(PlaybackGestureAxis.Vertical, gesture.sample(Offset(500f, direction * 25f)))
        }
    }

    @Test
    fun `initial jitter does not choose the direction and diagonal ties favor the seek`() {
        val gesture = PlaybackGestureAxisLock(12f)
        assertEquals(PlaybackGestureAxis.Undecided, gesture.sample(Offset(4f, 8f)))
        assertEquals(PlaybackGestureAxis.Undecided, gesture.sample(Offset(12f, -12f)))
        assertEquals(PlaybackGestureAxis.Horizontal, gesture.sample(Offset(-15f, 15f)))
    }

    @Test
    fun `a released or cancelled sequence does not lock the next gesture`() {
        val previous = PlaybackGestureAxisLock(12f)
        assertEquals(PlaybackGestureAxis.Horizontal, previous.sample(Offset(-30f, 0f)))
        val next = PlaybackGestureAxisLock(12f)
        assertEquals(PlaybackGestureAxis.Vertical, next.sample(Offset(0f, 30f)))
    }
}
