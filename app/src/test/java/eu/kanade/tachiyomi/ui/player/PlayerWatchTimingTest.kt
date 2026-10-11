package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerWatchTimingTest {
    @Test
    fun openingOrClosingNeverQueriesNativeProperties() {
        val timing = PlayerWatchTiming.read(available = false) { error("Native player is not available") }
        assertEquals(0.0, timing.position)
        assertEquals(0.0, timing.duration)
        assertFalse(timing.ready)
    }

    @Test
    fun missingPositionIsNotTreatedAsTheFirstFrame() {
        val timing = PlayerWatchTiming.read(true) { if (it == "duration") 1400.0 else null }
        assertEquals(1400.0, timing.duration)
        assertEquals(0.0, timing.position)
        assertFalse(timing.ready)
        val firstFrame = PlayerWatchTiming.read(true) { if (it == "duration") 1400.0 else 0.0 }
        assertTrue(firstFrame.ready)
    }

    @Test
    fun missingDurationDuringTeardownIsNotUnboxed() {
        val timing = PlayerWatchTiming.read(true) { if (it == "duration") null else 25.0 }
        assertEquals(0.0, timing.duration)
        assertEquals(25.0, timing.position)
        assertFalse(timing.ready)
    }

    @Test
    fun nullAndFailedNativeReadsBothRemainUnavailable() {
        assertFalse(PlayerWatchTiming.read(true) { null }.ready)
        val failed = PlayerWatchTiming.read(true) { throw IllegalStateException("Player has been released") }
        assertEquals(0.0, failed.position)
        assertEquals(0.0, failed.duration)
        assertFalse(failed.ready)
    }

    @Test
    fun nonFiniteAndOutOfRangeTimingsCannotEnterTheRoom() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 86_401.0)) {
            val timing = PlayerWatchTiming.read(true) { invalid }
            assertFalse(timing.ready)
            assertEquals(0.0, timing.position)
            assertEquals(0.0, timing.duration)
        }
        assertFalse(PlayerWatchTiming.read(true) { 0.0 }.ready)
    }

    @Test
    fun successiveEpisodesDoNotInheritOldNativeTiming() {
        var values: Map<String, Double?> = mapOf("duration" to 1400.0, "time-pos" to 125.0)
        fun sample() = PlayerWatchTiming.read(true) { values[it] }
        assertTrue(sample().ready)
        values = emptyMap()
        assertFalse(sample().ready)
        assertEquals(0.0, sample().position)
        values = mapOf("duration" to 1300.0, "time-pos" to 0.0)
        assertTrue(sample().ready)
        assertEquals(1300.0, sample().duration)
        assertEquals(0.0, sample().position)
    }
}
