package eu.kanade.tachiyomi.data.download.anime.ultra

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UltraWorkMonitorTest {
    @Test
    fun `opening the player cancels pending remux without waiting for its completion`() = runTest {
        var cancelled = false
        val result = runCatching {
            UltraWorkMonitor.run(
                check = { check(testScheduler.currentTime < 1000) { "Player active" } },
            ) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        assertEquals("Player active", result.exceptionOrNull()?.message)
        assertTrue(cancelled)
        assertEquals(1000L, testScheduler.currentTime)
    }

    @Test
    fun `normal completion returns the verified result and failed admission never starts work`() = runTest {
        assertEquals(
            42,
            UltraWorkMonitor.run(check = {}) {
                delay(100)
                42
            },
        )
        var started = false
        assertTrue(
            runCatching {
                UltraWorkMonitor.run(check = { error("Cooling") }) { started = true }
            }.isFailure,
        )
        assertEquals(false, started)
    }
}
