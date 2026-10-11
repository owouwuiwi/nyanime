package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerHoldSpeedTest {
    @Test
    fun releaseRestoresTheSpeedSelectedBeforeThisPress() {
        var speed = 1.25
        val writes = mutableListOf<Double>()
        val hold = PlayerHoldSpeed({ true }, { speed }) {
            speed = it
            writes.add(it)
        }
        assertTrue(hold.start(2.0))
        assertEquals(2.0, speed)
        hold.finish()
        assertEquals(1.25, speed)

        speed = 1.75
        assertTrue(hold.start(2.0))
        hold.finish()
        assertEquals(listOf(2.0, 1.25, 2.0, 1.75), writes)
    }

    @Test
    fun repeatedStartCannotReplaceTheOriginalSpeedWithTheTemporaryOverride() {
        var speed = 1.5
        val hold = PlayerHoldSpeed({ true }, { speed }) { speed = it }
        assertTrue(hold.start(1.25))
        assertFalse(hold.start(1.5))
        assertEquals(1.5, hold.originalSpeed)
        hold.finish()
        assertEquals(1.5, speed)
    }

    @Test
    fun cancellationFollowedByReleaseRestoresOnlyOnce() {
        val writes = mutableListOf<Double>()
        val hold = PlayerHoldSpeed({ true }, { 1.0 }, writes::add)
        hold.start(2.0)
        hold.finish()
        hold.finish()
        assertNull(hold.originalSpeed)
        assertEquals(listOf(2.0, 1.0), writes)
    }

    @Test
    fun closingPlayerNeverReadsOrWritesReleasedNativeState() {
        var available = false
        var speed = 1.0
        val hold = PlayerHoldSpeed(
            { available },
            {
                check(available)
                speed
            },
            {
                check(available)
                speed = it
            },
        )
        assertFalse(hold.start(2.0))
        available = true
        assertTrue(hold.start(2.0))
        available = false
        hold.finish()
        assertNull(hold.originalSpeed)
        assertEquals(2.0, speed)

        // A later native player has its own speed, never the previous press's snapshot.
        available = true
        speed = 1.25
        hold.finish()
        assertEquals(1.25, speed)
    }

    @Test
    fun ordinaryTapNeverChangesSpeed() {
        val hold = PlayerHoldSpeed({ error("Must not access native player") }, { error("Must not read") }) {
            error("Must not write")
        }
        hold.finish()
    }

    @Test
    fun invalidNativeSpeedsCannotBecomeARestoreTarget() {
        for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            val hold = PlayerHoldSpeed({ true }, { speed }) { error("Must not write") }
            assertFalse(hold.start(2.0))
            hold.finish()
        }
    }

    @Test
    fun selectedSpeedIsTemporaryEvenWhenItIsLowerThanTheNormalSpeed() {
        var speed = 1.75
        val hold = PlayerHoldSpeed({ true }, { speed }) { speed = it }

        assertTrue(hold.start(1.25))
        assertEquals(1.25, speed)
        hold.finish()
        assertEquals(1.75, speed)

        assertTrue(hold.start(1.5))
        assertEquals(1.5, speed)
        hold.finish()
        assertEquals(1.75, speed)
    }

    @Test
    fun invalidSelectedSpeedDoesNotChangePlayback() {
        val hold = PlayerHoldSpeed({ true }, { 1.0 }) { error("Must not write") }
        for (target in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertFalse(hold.start(target))
            assertNull(hold.originalSpeed)
        }
    }
}
