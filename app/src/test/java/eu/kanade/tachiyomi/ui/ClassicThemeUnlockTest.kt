package eu.kanade.tachiyomi.ui

import eu.kanade.domain.ui.ClassicThemeUnlock
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassicThemeUnlockTest {
    @Test
    fun onlyTheFifthConsecutiveActivationUnlocks() {
        val gesture = ClassicThemeUnlock()
        repeat(4) { assertFalse(gesture.tap(it * 400L)) }
        assertTrue(gesture.tap(1_600))
        assertFalse(gesture.tap(1_700))
    }

    @Test
    fun aTimeoutOrNonMonotonicClockStartsANewSequence() {
        val gesture = ClassicThemeUnlock()
        repeat(4) { assertFalse(gesture.tap(it * 100L)) }
        assertFalse(gesture.tap(2_301))
        assertFalse(gesture.tap(1_000))
        repeat(3) { assertFalse(gesture.tap(1_100L + it * 100)) }
        assertTrue(gesture.tap(1_500))
    }

    @Test
    fun leavingTheScreenBackgroundingAndLongPressResetPartialTaps() {
        val gesture = ClassicThemeUnlock()
        repeat(4) { gesture.tap(it * 100L) }
        gesture.reset()
        repeat(4) { assertFalse(gesture.tap(500L + it * 100)) }
        assertTrue(gesture.tap(900))
    }
}
