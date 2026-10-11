package eu.kanade.tachiyomi.data.workinfo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkInfoRepositoryTest {
    private val reference = WorkReference(WorkProvider.ANILIST, "123", WorkKind.ANIME)
    private val information = WorkInformation(WorkSummary(reference, "A fictional title", year = 2000, format = "TV"))
    private val identity = WorkIdentity("video:7:8:/title/1", information.work.title, WorkKind.VIDEO)
    private class MemoryCache : WorkInfoCache {
        val values = mutableMapOf<String, WorkCacheEntry>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, entry: WorkCacheEntry) {
            values[key] = entry
        }
    }
    private class MutableClock(var now: Long = 1_000_000) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }
    private inner class Provider : WorkInformationProvider {
        override val provider = WorkProvider.ANILIST
        var calls = 0
        var searches = 0
        var wait: CompletableDeferred<Unit>? = null
        var fail = false
        var searchResult: ((WorkIdentity) -> List<WorkSummary>)? = null
        var candidates = listOf(information.work)
        override suspend fun search(identity: WorkIdentity, language: String): List<WorkSummary> {
            searches++
            if (fail) throw IOException("Test outage")
            return searchResult?.invoke(identity) ?: candidates
        }
        override suspend fun information(reference: WorkReference, language: String): WorkInformation {
            calls++
            wait?.await()
            if (fail) throw IOException("Test outage")
            return information
        }
        override suspend fun person(reference: WorkPersonReference, language: String) = error("Unexpected request")
        override suspend fun works(
            reference: WorkPersonReference,
            page: Int,
            language: String,
        ) = error("Unexpected request")
        override suspend fun credits(
            reference: WorkReference,
            cast: Boolean,
            page: Int,
            language: String,
        ) = error("Unexpected request")
    }

    @Test fun `no catalogue calls before consent or after disabling`() = runTest {
        val provider = Provider()
        val repo =
            WorkInfoRepository(listOf(provider), MemoryCache(), Json, {
                false
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        assertNull(repo.resolve(identity, null, "it", true, true).reference)
        assertTrue(runCatching { repo.information(reference, "it", true, true) }.exceptionOrNull() is IOException)
        assertEquals(0, provider.calls + provider.searches)
    }

    @Test fun `fresh cache and offline stale cache avoid all network calls`() = runTest {
        val provider = Provider()
        val clock = MutableClock()
        val cache = MemoryCache()
        val repo =
            WorkInfoRepository(listOf(provider), cache, Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        assertEquals(information, repo.information(reference, "it", true, true))
        assertEquals(information, repo.information(reference, "it", true, true))
        clock.now += 2 * 24 * 60 * 60 * 1000L
        assertEquals(information, repo.information(reference, "it", false, true))
        assertEquals(1, provider.calls)
        repo.information(reference, "it", true, true)
        assertEquals(2, provider.calls)
    }

    @Test fun `concurrent readers share a request and cancelling one does not cancel the other`() = runTest {
        val provider = Provider().also { it.wait = CompletableDeferred() }
        val repo =
            WorkInfoRepository(listOf(provider), MemoryCache(), Json, {
                true
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        val first = async { repo.information(reference, "it", true, true) }
        val second = async { repo.information(reference, "it", true, true) }
        runCurrent()
        assertEquals(1, provider.calls)
        first.cancelAndJoin()
        provider.wait!!.complete(Unit)
        assertEquals(information, second.await())
        assertEquals(1, provider.calls)
    }

    @Test fun `last reader cancellation stops unfinished request and cannot write cache`() = runTest {
        val provider = Provider().also { it.wait = CompletableDeferred() }
        val cache = MemoryCache()
        val repo =
            WorkInfoRepository(listOf(provider), cache, Json, {
                true
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        val first = async { repo.information(reference, "it", true, true) }
        runCurrent()
        first.cancelAndJoin()
        provider.wait!!.complete(Unit)
        runCurrent()
        assertTrue(cache.values.isEmpty())
        repo.information(reference, "it", true, true)
        assertEquals(2, provider.calls)
    }

    @Test fun `incognito requests and automatic associations do not persist`() = runTest {
        val provider = Provider()
        val cache = MemoryCache()
        val repo =
            WorkInfoRepository(listOf(provider), cache, Json, {
                true
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        repo.information(reference, "it", true, false)
        repo.rememberResolution(identity, information, false)
        assertTrue(cache.values.isEmpty())
    }

    @Test fun `failed refresh retains old readable cache without claiming success`() = runTest {
        val provider = Provider()
        val cache = MemoryCache()
        val clock = MutableClock()
        val repo =
            WorkInfoRepository(listOf(provider), cache, Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        repo.information(reference, "it", true, true)
        clock.now += 2 * 24 * 60 * 60 * 1000L
        provider.fail = true
        assertTrue(runCatching { repo.information(reference, "it", true, true) }.isFailure)
        assertEquals(information, repo.cachedInformation(reference, "it"))
    }

    @Test fun `automatic reference is recoverable offline but an explicit removal wins`() = runTest {
        val provider = Provider()
        val cache = MemoryCache()
        val repo =
            WorkInfoRepository(listOf(provider), cache, Json, {
                true
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        repo.rememberResolution(identity, information, true)
        assertEquals(reference, repo.resolve(identity, null, "it", false, true).reference)
        val removed = repo.resolve(identity, WorkBinding(null), "it", true, true)
        assertTrue(removed.excluded)
        assertNull(removed.reference)
        assertEquals(0, provider.searches)
    }

    @Test fun `conflicting verified IDs never resolve through a partial search result`() = runTest {
        val provider = Provider()
        val repo =
            WorkInfoRepository(listOf(provider), MemoryCache(), Json, {
                true
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        val input = identity.copy(references = listOf(reference, reference.copy(id = "456")))
        val result = repo.resolve(input, null, "it", true, true)
        assertNull(result.reference)
        assertEquals(listOf(information.work), result.candidates)
        assertFalse(result.partialFailure)
    }

    @Test fun `disabled consent during a request prevents cache writes and delivery`() = runTest {
        val provider = Provider().also { it.wait = CompletableDeferred() }
        var enabled = true
        val cache = MemoryCache()
        val repo =
            WorkInfoRepository(listOf(provider), cache, Json, {
                enabled
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        val request = async { runCatching { repo.information(reference, "it", true, true) } }
        runCurrent()
        enabled = false
        provider.wait!!.complete(Unit)
        assertTrue(request.await().isFailure)
        assertTrue(cache.values.isEmpty())
    }

    @Test fun `verified direct reference avoids title search`() = runTest {
        val provider = Provider()
        val repo =
            WorkInfoRepository(listOf(provider), MemoryCache(), Json, {
                true
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(
            reference,
            repo.resolve(identity.copy(references = listOf(reference)), null, "it", true, true).reference,
        )
        assertEquals(0, provider.searches)
    }

    @Test fun `a name only result is suggested instead of attached`() = runTest {
        val provider = Provider()
        val repo =
            WorkInfoRepository(listOf(provider), MemoryCache(), Json, {
                true
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        val result = repo.resolve(identity, null, "it", true, true)
        assertNull(result.reference)
        assertNotNull(result.candidates.single())
    }

    @Test fun `complete credits already cached with the title can be paged while offline`() = runTest {
        val cache = MemoryCache()
        val provider = Provider()
        val repo = WorkInfoRepository(
            listOf(provider),
            cache,
            Json,
            { true },
            scope = this,
            workDispatcher = StandardTestDispatcher(testScheduler),
        )
        repo.information(reference, "it", true, true)
        val key = cache.values.keys.single()
        val credits = (1..24).map { WorkCredit("Person $it", "CAST") }
        cache.values[key] = cache.values.getValue(key).copy(
            payload = Json.encodeToString(information.copy(cast = credits)),
        )
        assertEquals(20, repo.credits(reference, true, 1, "it", false, true).credits.size)
        val second = repo.credits(reference, true, 2, "it", false, true)
        assertEquals(credits.takeLast(4), second.credits)
        assertFalse(second.hasNext)
        assertEquals(1, provider.calls)
    }

    @Test fun `alias recovery is bounded and does not turn a name into a verified association`() = runTest {
        val provider = Provider().also {
            it.searchResult = { query -> if (query.title == "Alternative") listOf(information.work) else emptyList() }
        }
        val repo = WorkInfoRepository(
            listOf(provider),
            MemoryCache(),
            Json,
            { true },
            scope = this,
            workDispatcher = StandardTestDispatcher(testScheduler),
        )
        val result = repo.resolve(identity.copy(aliases = listOf("Alternative", "Unused")), null, "it", true, true)
        assertEquals(2, provider.searches)
        assertEquals(listOf(information.work), result.candidates)
        assertNull(result.reference)
    }

    @Test fun `failed alias lookup retains candidates already returned by the first query`() = runTest {
        val provider = Provider().also {
            it.searchResult = { query ->
                if (query.title == "Alternative") throw IOException("Test outage")
                listOf(information.work)
            }
        }
        val repo = WorkInfoRepository(
            listOf(provider),
            MemoryCache(),
            Json,
            { true },
            scope = this,
            workDispatcher = StandardTestDispatcher(testScheduler),
        )
        val result = repo.resolve(identity.copy(aliases = listOf("Alternative")), null, "it", true, true)
        assertTrue(result.partialFailure)
        assertEquals(listOf(information.work), result.candidates)
        assertNull(result.reference)
    }

    @Test fun `a damaged payload is fetched again instead of trapping the screen in an error`() = runTest {
        val provider = Provider()
        val cache = MemoryCache()
        val repo =
            WorkInfoRepository(listOf(provider), cache, Json, {
                true
            }, scope = this, workDispatcher = StandardTestDispatcher(testScheduler))
        repo.information(reference, "it", true, true)
        val key = cache.values.keys.single()
        cache.values[key] = cache.values.getValue(key).copy(payload = "{}")
        assertEquals(information, repo.information(reference, "it", true, true))
        assertEquals(2, provider.calls)
    }
}
