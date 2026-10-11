package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SleepTimerDurationTest {
    @Test
    fun `countdown displays minutes and hours without wrapping to the next day`() {
        assertEquals("00:00", formatSleepTimerRemaining(-1))
        assertEquals("00:01", formatSleepTimerRemaining(1))
        assertEquals("29:02", formatSleepTimerRemaining(1742))
        assertEquals("1:00:00", formatSleepTimerRemaining(3600))
        assertEquals("23:59:00", formatSleepTimerRemaining(86340))
        assertEquals("24:00:00", formatSleepTimerRemaining(86400))
    }

    @Test
    fun `custom durations cover the previous picker range without accepting zero`() {
        assertEquals(60, sleepTimerDurationSeconds("1"))
        assertEquals(1800, sleepTimerDurationSeconds("30"))
        assertEquals(86340, sleepTimerDurationSeconds("1439"))
        listOf("", "0", "-1", "1440", "99999999999", "abc", "1.5").forEach {
            assertNull(sleepTimerDurationSeconds(it), it)
        }
    }
}
