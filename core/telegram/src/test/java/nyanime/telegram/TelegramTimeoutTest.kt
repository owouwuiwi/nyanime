package nyanime.telegram

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramTimeoutTest {
    @Test fun stalledRequestRemainsRetryableWithoutStoppingTheNextAttempt() = runTest {
        val failed = runCatching {
            withTelegramTimeout(20) {
                delay(100)
                "late"
            }
        }.exceptionOrNull()
        val failure = assertInstanceOf(TelegramException::class.java, failed)
        assertEquals(408, failure.code)
        assertTrue(TelegramErrors.retryable(failure))
        assertTrue(currentCoroutineContext().isActive)
        assertEquals("confirmed", withTelegramTimeout(20) { "confirmed" })
    }

    @Test fun callerDeadlineIsNeverReclassifiedAsANetworkFailure() = runTest {
        val failed = runCatching {
            withTimeout(10) {
                withTelegramTimeout(100) {
                    delay(1000)
                    Unit
                }
            }
        }.exceptionOrNull()
        assertInstanceOf(TimeoutCancellationException::class.java, failed)
    }

    @Test fun protocolRejectionsKeepTheirOriginalMeaning() = runTest {
        val rejection = TelegramException(TelegramFailure.ARCHIVE_MISSING, code = 404)
        val failed = runCatching { withTelegramTimeout(20) { throw rejection } }.exceptionOrNull()
        assertSame(rejection, failed)
    }
}
