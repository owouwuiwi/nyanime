package it.lorelore.nyanime.novel

import it.lorelore.nyanime.novel.ui.NovelPreviewLifetime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(5)
class NovelPreviewLifetimeTest {
    @Test
    fun cleanupWaitsForTheActualResourceOperation() = runBlocking {
        val lifetime = NovelPreviewLifetime()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = launch {
            lifetime.run {
                started.complete(Unit)
                finish.await()
            }
        }
        started.await()
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) { lifetime.drain() }
        assertFalse(cleanup.isCompleted)
        finish.complete(Unit)
        request.join()
        cleanup.await()
    }

    @Test
    fun closingRejectsLateRequestsEvenAfterAllWorkHasFinished() = runBlocking {
        val lifetime = NovelPreviewLifetime()
        lifetime.drain()
        assertTrue(runCatching { lifetime.run {} }.exceptionOrNull() is IllegalStateException)
        lifetime.drain()
    }

    @Test
    fun cancellationDoesNotSkipAResourceFinallyBlock() = runBlocking {
        val lifetime = NovelPreviewLifetime()
        val started = CompletableDeferred<Unit>()
        val finallyStarted = CompletableDeferred<Unit>()
        val finishFinally = CompletableDeferred<Unit>()
        val request = launch {
            lifetime.run {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        finallyStarted.complete(Unit)
                        finishFinally.await()
                    }
                }
            }
        }
        started.await()
        request.cancel()
        finallyStarted.await()
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) { lifetime.drain() }
        assertFalse(cleanup.isCompleted)
        finishFinally.complete(Unit)
        request.join()
        cleanup.await()
    }
}
