package eu.kanade.tachiyomi.ui.player.session

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackPointerTrackerTest {
    @Test
    fun `moving the host does not subtract its translation from the finger movement`() {
        val tracker = PlaybackPointerTracker()
        tracker.sample(mapOf(1L to Offset(180f, 200f)))
        repeat(8) { step ->
            val hostOrigin = Offset(160f + step * 10f, 190f + step * 12f)
            val windowFinger = Offset(190f + step * 10f, 212f + step * 12f)
            val local = windowFinger - hostOrigin
            assertEquals(Offset(10f, 12f), tracker.sample(mapOf(1L to local + hostOrigin)).pan)
        }
    }

    @Test
    fun `adding or lifting a finger does not invent a translation or a zoom`() {
        val tracker = PlaybackPointerTracker()
        tracker.sample(mapOf(1L to Offset(100f, 200f)))
        assertEquals(PlaybackPointerDelta(), tracker.sample(mapOf(1L to Offset(100f, 200f), 2L to Offset(300f, 200f))))
        assertEquals(PlaybackPointerDelta(), tracker.sample(mapOf(1L to Offset(100f, 200f))))
    }

    @Test
    fun `pinch preserves its centroid and measures only common fingers`() {
        val tracker = PlaybackPointerTracker()
        tracker.sample(mapOf(1L to Offset(100f, 200f), 2L to Offset(300f, 200f)))
        val sample = tracker.sample(mapOf(1L to Offset(50f, 220f), 2L to Offset(350f, 220f)))
        assertEquals(Offset(0f, 20f), sample.pan)
        assertEquals(1.5f, sample.zoom)
    }
}
