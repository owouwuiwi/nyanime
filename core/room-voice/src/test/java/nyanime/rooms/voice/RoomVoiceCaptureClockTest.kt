package nyanime.rooms.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoomVoiceCaptureClockTest {
    private class Clock {
        var time = 0L
        val waits = mutableListOf<Long>()
        val capture = RoomVoiceCaptureClock(
            nowNanos = { time },
            waitNanos = {
                waits += it
                time += it
            },
        )
    }

    @Test
    fun `silence is paced and cannot run ahead of real time`() {
        val clock = Clock()
        repeat(1_000) {
            assertEquals((it + 1) * 10_000_000L, clock.capture.captureTimestamp(0, 0))
        }
        assertEquals(10_000_000_000L, clock.time)
        assertEquals(1_000, clock.waits.size)
    }

    @Test
    fun `hardware frames retain their timestamp without another delay`() {
        val clock = Clock()
        assertEquals(123L, clock.capture.captureTimestamp(960, 123))
        assertEquals(0L, clock.time)
        assertEquals(emptyList<Long>(), clock.waits)
    }

    @Test
    fun `a suspended recorder drops old silence instead of bursting`() {
        val clock = Clock()
        clock.capture.captureTimestamp(0, 0)
        clock.time += 90_000_000_000L
        val before = clock.time
        assertEquals(before + 10_000_000L, clock.capture.captureTimestamp(0, 0))
        assertEquals(10_000_000L, clock.waits.last())
    }

    @Test
    fun `mute after live capture starts a new silence clock`() {
        val clock = Clock()
        clock.capture.captureTimestamp(0, 0)
        clock.time += 1_000_000L
        clock.capture.captureTimestamp(960, clock.time)
        val before = clock.time
        assertEquals(before + 10_000_000L, clock.capture.captureTimestamp(0, 0))
    }

    @Test
    fun `spurious wakeups still wait for the audio deadline`() {
        var time = 0L
        var waits = 0
        val capture = RoomVoiceCaptureClock({ time }, {
            time += minOf(it, 1_000_000L)
            waits++
        })
        assertEquals(10_000_000L, capture.captureTimestamp(0, 0))
        assertEquals(10, waits)
    }
}
