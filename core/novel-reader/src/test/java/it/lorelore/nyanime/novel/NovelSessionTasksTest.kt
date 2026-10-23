package it.lorelore.nyanime.novel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@Timeout(5)
class NovelSessionTasksTest {
    @Test
    fun acceptedWritesDrainInOrderDespiteAnEarlierFailure() = runBlocking {
        val failures = CopyOnWriteArrayList<Exception>()
        val values = CopyOnWriteArrayList<Int>()
        val tasks = NovelSessionTasks { failures.add(it) }
        tasks.commit { values += 1 }
        tasks.commit { throw IOException("Fixture write failed") }
        tasks.commit { values += 2 }
        tasks.drain()
        assertEquals(listOf(1, 2), values.toList())
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IOException)
    }

    @Test
    fun cleanupWaitsForReleaseAndForAcceptedWrites() = runBlocking {
        val writerStarted = CompletableDeferred<Unit>()
        val releaseStarted = CompletableDeferred<Unit>()
        val allowWriter = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        val tasks = NovelSessionTasks { throw AssertionError(it) }
        tasks.commit {
            writerStarted.complete(Unit)
            allowWriter.await()
        }
        tasks.release {
            releaseStarted.complete(Unit)
            allowRelease.await()
        }
        writerStarted.await()
        releaseStarted.await()
        val drained = async(start = CoroutineStart.UNDISPATCHED) { tasks.drain() }
        allowWriter.complete(Unit)
        // Joining the real writer is not enough while a publication still owns resources.
        assertFalse(drained.isCompleted)
        allowRelease.complete(Unit)
        drained.await()
    }

    @Test
    fun aClosedSessionRejectsNewWorkAndMayBeDrainedAgain() = runBlocking {
        val tasks = NovelSessionTasks { throw AssertionError(it) }
        tasks.drain()
        assertTrue(runCatching { tasks.commit {} }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { tasks.release {} }.exceptionOrNull() is IllegalStateException)
        tasks.drain()
    }
}
