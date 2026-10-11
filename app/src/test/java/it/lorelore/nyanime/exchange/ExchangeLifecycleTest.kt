package it.lorelore.nyanime.exchange

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class ExchangeLifecycleTest {
    @Test fun cancelledTransferStillReturnsToFinishTheAndroidService() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val steps = CopyOnWriteArrayList<String>()
        val operation = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                finishExchangeSession(close = {
                    delay(20)
                    steps.add("native closed")
                }, finished = { steps.add("service stopped") })
            }
        }
        started.await()
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertEquals(listOf("native closed", "service stopped"), steps)
    }

    @Test fun nativeCloseFailureCannotSkipStoppingTheService() = runBlocking {
        val steps = CopyOnWriteArrayList<String>()
        val result = runCatching {
            finishExchangeSession(close = {
                steps.add("native close attempted")
                error("synthetic close failure")
            }, finished = { steps.add("service stopped") })
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("native close attempted", "service stopped"), steps)
    }
}
