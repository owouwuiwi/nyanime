package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import nyanime.tv.api.TvCacheEntry
import nyanime.tv.api.TvCapabilities
import nyanime.tv.api.TvCatalogue
import nyanime.tv.api.TvCatalogueRequest
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvGuide
import nyanime.tv.api.TvGuideRequest
import nyanime.tv.api.TvPlayback
import nyanime.tv.api.TvSource
import nyanime.tv.api.TvSourceCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class TvSearchSessionTest {
    private class Cache : TvSourceCache {
        private val values = mutableMapOf<String, TvCacheEntry>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, entry: TvCacheEntry) {
            values[key] = entry
        }
    }
    private class Source(override val id: Long, private val latency: Long = 0) : TvSource {
        override val name = "Test source"
        override val language = "all"
        override val capabilities = TvCapabilities(mapOf("en" to "TV"))
        var calls = 0
        var cancelled = false
        override suspend fun catalogue(request: TvCatalogueRequest): TvCatalogue {
            calls++
            try {
                delay(latency)
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            }
            return TvCatalogue(listOf(TvChannel(TvChannelRef(id, "feed"), "Café Channel")))
        }
        override suspend fun programmes(request: TvGuideRequest) = TvGuide(emptyList())
        override suspend fun resolve(channel: TvChannelRef): TvPlayback = error("Search cannot resolve streams")
    }

    @Test fun `empty exploration is cache-only and nonempty results arrive progressively`() = runTest {
        val fast = Source(42)
        val slow = Source(43, 5_000)
        val registry = TvRegistry().apply {
            replace(listOf(TvRegistration(fast, Cache()), TvRegistration(slow, Cache())))
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = TvRepository(registry, TvPreferences(InMemoryPreferenceStore()), dispatcher)
        val search = TvSearchSession(repository, Semaphore(5), dispatcher)
        val requests = mapOf(42L to TvCatalogueRequest(), 43L to TvCatalogueRequest())
        val empty = search.search("", requests, false).toList()
        assertEquals(0, fast.calls + slow.calls)
        assertTrue(empty.last().values.all { it.channels.isEmpty() })
        val results = search.search("cafe", requests, false).toList()
        assertTrue(results.any { it[42]?.channels?.isNotEmpty() == true && it[43]?.channels.orEmpty().isEmpty() })
        assertEquals(2, results.last().values.sumOf { it.channels.size })
        val cached = search.search("", requests, false).toList()
        assertEquals(2, cached.last().values.sumOf { it.channels.size })
        assertEquals(2, fast.calls + slow.calls)
    }

    @Test fun `closing a query cancels the underlying source and cannot emit late results`() = runTest {
        val source = Source(42, 60_000)
        val registry = TvRegistry().apply { replace(listOf(TvRegistration(source, Cache()))) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val search =
            TvSearchSession(
                TvRepository(registry, TvPreferences(InMemoryPreferenceStore()), dispatcher),
                Semaphore(5),
                dispatcher,
            )
        val results = mutableListOf<Map<Long, TvSearchPage>>()
        val job = launch { search.search("channel", mapOf(42L to TvCatalogueRequest()), false).toList(results) }
        testScheduler.runCurrent()
        job.cancel()
        testScheduler.runCurrent()
        assertTrue(source.cancelled)
        assertFalse(results.any { it.values.any { page -> page.channels.isNotEmpty() } })
    }
}
