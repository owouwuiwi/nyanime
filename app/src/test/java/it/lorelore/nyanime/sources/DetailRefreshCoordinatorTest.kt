package it.lorelore.nyanime.sources

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailRefreshCoordinatorTest {
    private class Storage : DetailFreshnessStorage {
        val records = mutableMapOf<String, DetailRefreshStamp>()
        override fun read(key: String) = records[key]
        override fun write(key: String, stamp: DetailRefreshStamp) {
            records[key] = stamp
        }
    }

    @Test fun opensShareARequestAndFreshnessSurvivesProcessRestart() = runTest {
        val storage = Storage()
        val coordinator = DetailRefreshCoordinator(storage) { 1_000_000L }
        var requests = 0
        List(10) {
            async {
                coordinator.refresh("entry", "source", "bytes-a") {
                    requests++
                    true
                }
            }
        }.awaitAll()
        assertEquals(1, requests)
        DetailRefreshCoordinator(storage) { 1_000_001L }.refresh("entry", "source", "bytes-a") {
            requests++
            true
        }
        assertEquals(1, requests)
        DetailRefreshCoordinator(storage) { 1_000_002L }.refresh("entry", "source", "bytes-b") {
            requests++
            true
        }
        assertEquals(2, requests) // Same advertised version, different loaded bytes.
    }

    @Test fun failedQueriesBackOffButANewSourceCanRetryImmediately() = runTest {
        val storage = Storage()
        var now = 1_000_000L
        val coordinator = DetailRefreshCoordinator(storage) { now }
        var requests = 0
        coordinator.refresh("entry", "source", "a") {
            requests++
            false
        }
        now += 59_999
        coordinator.refresh("entry", "source", "a") {
            requests++
            true
        }
        assertEquals(1, requests)
        coordinator.refresh("entry", "source", "b") {
            requests++
            true
        }
        assertEquals(2, requests)
        assertTrue(DetailRefreshCoordinator.due(storage.read("entry"), "b", now - 1)) // Clock moved backwards.
    }

    @Test fun cancellationAndSourceReplacementNeverWriteSuccessOrFailure() = runTest {
        val storage = Storage()
        val coordinator = DetailRefreshCoordinator(storage)
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            coordinator.refresh("entry", "source", "a") {
                waiting.await()
                true
            }
        }
        runCurrent()
        job.cancelAndJoin()
        assertNull(storage.read("entry"))
        var current = true
        try {
            coordinator.refresh("entry", "source", "a", current = { current }) {
                current = false
                true
            }
        } catch (_: SourceReplacedException) { }
        assertNull(storage.read("entry"))
    }

    @Test fun incognitoDoesNotPersistAndExplicitRefreshBypassesFreshness() = runTest {
        val storage = Storage()
        val coordinator = DetailRefreshCoordinator(storage)
        coordinator.refresh("entry", "source", "a", persistent = false) { true }
        assertTrue(storage.records.isEmpty())
        coordinator.refresh("entry", "source", "a") { true }
        var requests = 0
        assertFalse(
            coordinator.refresh("entry", "source", "a") {
                requests++
                true
            },
        )
        assertTrue(
            coordinator.refresh("entry", "source", "a", force = true) {
                requests++
                true
            },
        )
        assertEquals(1, requests)
    }

    @Test fun aSourceHasAtMostTwoConcurrentDetailRequests() = runTest {
        val coordinator = DetailRefreshCoordinator(Storage())
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val jobs = List(8) { id ->
            async {
                coordinator.refresh("entry-$id", "one-source", "a") {
                    active++
                    peak = maxOf(peak, active)
                    release.await()
                    active--
                    true
                }
            }
        }
        runCurrent()
        assertEquals(2, active)
        release.complete(Unit)
        jobs.awaitAll()
        assertEquals(2, peak)
    }
}
