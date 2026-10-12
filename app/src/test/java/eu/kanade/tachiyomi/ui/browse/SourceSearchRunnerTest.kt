package eu.kanade.tachiyomi.ui.browse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceSearchRunnerTest {
    @Test fun oldNonCooperativeRequestCannotPublishOverNewQuery() = runTest {
        val gate = CompletableDeferred<Unit>()
        val values = mutableListOf<String>()
        val runner = SourceSearchRunner<String>(backgroundScope)
        runner.submit("source", {
            withContext(NonCancellable) {
                gate.await()
                "old"
            }
        }) { values += it.getOrThrow() }
        runCurrent()
        runner.reset()
        runner.submit("source", { "new" }) { values += it.getOrThrow() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), values)
    }

    @Test fun retriesReplaceOnlyTheirSourceAndKeepConcurrentResults() = runTest {
        val runner = SourceSearchRunner<Int>(backgroundScope)
        val results = mutableMapOf<Int, Result<String>>()
        runner.submit(1, { error("offline") }) { results[1] = it }
        runner.submit(2, { "kept" }) { results[2] = it }
        runCurrent()
        assertTrue(results[1]!!.isFailure)
        runner.submit(1, { "retried" }) { results[1] = it }
        runCurrent()
        assertEquals("retried", results[1]!!.getOrThrow())
        assertEquals("kept", results[2]!!.getOrThrow())
    }

    @Test fun limitsSuspendingRequestsAndTimeoutDoesNotCancelOtherSources() = runTest {
        var running = 0
        var maximum = 0
        val results = mutableListOf<Result<Int>>()
        val runner = SourceSearchRunner<Int>(this, concurrency = 2, timeoutMillis = 100)
        repeat(6) { index ->
            runner.submit(index, {
                running++
                maximum = maxOf(maximum, running)
                try {
                    delay(if (index == 0) 200 else 20)
                    index
                } finally {
                    running--
                }
            }) { results += it }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(2, maximum)
        assertEquals(6, results.size)
        assertEquals(1, results.count { it.isFailure })
    }
}
