package eu.kanade.tachiyomi.data.track

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoTrackingRetryTest {
    @Test
    fun `temporary failure retries and preserves bindings from other services`() = runTest {
        val waits = mutableListOf<Long>()
        var calls = 0
        val result = AutoTrackingRetry.run(wait = { waits += it }) {
            if (calls++ == 0) AutoTrackingAttempt(linked = true, retryable = true) else AutoTrackingAttempt.Complete
        }
        assertTrue(result.linked)
        assertFalse(result.retryable)
        assertEquals(2, calls)
        assertEquals(listOf(15_000L), waits)
    }

    @Test
    fun `offline service has a finite retry window and remains pending`() = runTest {
        val waits = mutableListOf<Long>()
        var calls = 0
        val result = AutoTrackingRetry.run(wait = { waits += it }) {
            calls++
            AutoTrackingAttempt.Retry
        }
        assertTrue(result.retryable)
        assertEquals(3, calls)
        assertEquals(listOf(15_000L, 60_000L), waits)
    }

    @Test
    fun `ambiguity unsupported source and missing login do not keep retrying`() = runTest {
        var calls = 0
        val result = AutoTrackingRetry.run(wait = { error("Unexpected retry") }) {
            calls++
            AutoTrackingAttempt.Complete
        }
        assertFalse(result.linked)
        assertEquals(1, calls)
    }

    @Test
    fun `local timeout can recover while cancellation stops immediately`() = runTest {
        assertEquals(
            AutoTrackingAttempt.Retry,
            AutoTrackingRetry.safely {
                withTimeout(10) { delay(100) }
                AutoTrackingAttempt.Linked
            },
        )
        var cancelled = false
        try {
            AutoTrackingRetry.safely { throw CancellationException("Reader closed") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `mixed tracker results require retry without losing successful binding`() {
        assertEquals(
            AutoTrackingAttempt(linked = true, retryable = true),
            AutoTrackingAttempt.combine(listOf(AutoTrackingAttempt.Linked, AutoTrackingAttempt.Retry)),
        )
    }
}
