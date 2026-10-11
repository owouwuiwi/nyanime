package eu.kanade.tachiyomi.data.download.anime.ultra

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UltraRemovalTest {
    @Test
    fun `confirmed batch finishes remaining episodes after the screen closes`() = runTest {
        val firstFile = CompletableDeferred<Unit>()
        val removed = mutableListOf<Int>()
        val job = launch {
            UltraRemoval.batch(listOf(1, 2, 3), remove = {
                if (it == 1) firstFile.await()
                removed += it
            }, onProgress = {})
        }
        runCurrent()
        job.cancel()
        firstFile.complete(Unit)
        job.join()
        assertEquals(listOf(1, 2, 3), removed)
    }

    @Test
    fun `batch continues after storage failure and retries only failed episodes`() = runTest {
        val progress = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        val failures = UltraRemoval.batch(listOf(1, 2, 3), remove = {
            if (it == 2) error("permission denied")
            removed += it
        }, onProgress = { progress += it })
        assertEquals(listOf(1, 3), removed)
        assertEquals(listOf(2), failures.map { it.first })
        assertEquals(listOf(1, 2, 3), progress)
        UltraRemoval.batch(failures.map { it.first }, remove = { removed += it }, onProgress = {})
        assertEquals(listOf(1, 3, 2), removed)
    }

    @Test
    fun `files are not touched until writer has stopped and success waits for cleanup`() = runTest {
        val writerStopped = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch {
            UltraRemoval.run(
                stopWriter = {
                    writerStopped.await()
                    events += "stopped"
                },
                removeFiles = { events += "files" },
                removeTemporary = { events += "temporary" },
                recordCompletion = { events += "complete" },
            )
        }
        runCurrent()
        assertTrue(events.isEmpty())
        writerStopped.complete(Unit)
        job.join()
        assertEquals(listOf("stopped", "files", "temporary", "complete"), events)
    }

    @Test
    fun `closing the screen cannot abandon a confirmed removal`() = runTest {
        val writerStopped = CompletableDeferred<Unit>()
        var complete = false
        val job = launch {
            UltraRemoval.run(
                stopWriter = { writerStopped.await() },
                removeFiles = {},
                removeTemporary = {},
                recordCompletion = { complete = true },
            )
        }
        runCurrent()
        job.cancel()
        writerStopped.complete(Unit)
        job.join()
        assertTrue(complete)
    }

    @Test
    fun `a writer that cannot stop preserves all files and the journal`() = runTest {
        var touched = false
        val result = runCatching {
            UltraRemoval.run(
                stopWriter = { error("busy") },
                removeFiles = { touched = true },
                removeTemporary = { touched = true },
                recordCompletion = { touched = true },
            )
        }
        assertEquals("busy", result.exceptionOrNull()?.message)
        assertEquals(false, touched)
    }

    @Test
    fun `storage failure is reported without recording a successful deletion`() = runTest {
        var completed = false
        val result = runCatching {
            UltraRemoval.run(
                stopWriter = {},
                removeFiles = { error("permission denied") },
                removeTemporary = {},
                recordCompletion = { completed = true },
            )
        }
        assertEquals("permission denied", result.exceptionOrNull()?.message)
        assertEquals(false, completed)
    }

    @Test
    fun `temporary cleanup failure remains retryable after video deletion`() = runTest {
        var completed = false
        val result = runCatching {
            UltraRemoval.run(
                stopWriter = {},
                removeFiles = {},
                removeTemporary = { error("temporary files busy") },
                recordCompletion = { completed = true },
            )
        }
        assertTrue(result.isFailure)
        assertEquals(false, completed)
    }
}
