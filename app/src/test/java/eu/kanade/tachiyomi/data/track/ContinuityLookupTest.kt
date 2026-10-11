package eu.kanade.tachiyomi.data.track

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContinuityLookupTest {
    private data class Choice(val id: Long, val copy: String? = null, val chapter: Int? = null)

    @Test
    fun `a copy opens before slow chapter metadata and keeps its original position`() = runTest {
        val snapshots = mutableListOf<Pair<List<Choice>, Set<Long>>>()
        val progress = ContinuityProgress(listOf(Choice(2), Choice(1)), Choice::id) { choices, pending ->
            snapshots += choices to pending
        }
        val metadata = CompletableDeferred<Int>()
        progress.emit()
        // Metadata is fetched before the atomic update, never while holding the lock.
        val realEnrichment = launch {
            val chapter = metadata.await()
            progress.update(2) { it.copy(chapter = chapter) }
        }
        progress.update(2, lookupFinished = true) { it.copy(copy = "verified") }
        assertEquals("verified", snapshots.last().first.first().copy)
        assertEquals(setOf(1L), snapshots.last().second)
        assertNull(snapshots.last().first.first().chapter)
        metadata.complete(7)
        realEnrichment.join()
        assertEquals(listOf(2L, 1L), progress.result().map { it.id })
        assertEquals(Choice(2, "verified", 7), progress.result().first())
        assertNull(snapshots[0].first.first().copy)
    }

    @Test
    fun `concurrent updates preserve both copies and metadata`() = runTest {
        val progress = ContinuityProgress(listOf(Choice(1)), Choice::id) { _, _ -> }
        listOf(
            async { progress.update(1, lookupFinished = true) { it.copy(copy = "verified") } },
            async { progress.update(1) { it.copy(chapter = 12) } },
        ).awaitAll()
        assertEquals(Choice(1, "verified", 12), progress.result().single())
    }

    @Test
    fun `cancelled source lookups do not report a missing copy`() = runTest {
        var returned = false
        val lookup = launch {
            ContinuityRequests.source { CompletableDeferred<Unit>().await() }
            returned = true
        }
        runCurrent()
        lookup.cancelAndJoin()
        assertFalse(returned)
        assertNull(ContinuityRequests.optional { error("Source unavailable") })
        var cancelled = false
        try {
            ContinuityRequests.optional { throw CancellationException("Navigation changed") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `both directions share at most three source requests and release permits after timeout`() = runTest {
        var active = 0
        var maximum = 0
        (1..8).map {
            async {
                ContinuityRequests.source {
                    active++
                    maximum = maxOf(maximum, active)
                    try {
                        delay(21_000)
                    } finally {
                        active--
                    }
                }
            }
        }.awaitAll()
        assertEquals(3, maximum)
        assertEquals(0, active)
        assertEquals("ready", ContinuityRequests.source { "ready" })
    }
}
