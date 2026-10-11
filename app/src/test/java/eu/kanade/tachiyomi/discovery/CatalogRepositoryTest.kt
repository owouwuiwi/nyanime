package eu.kanade.tachiyomi.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.discovery.CachedAnimeCatalogRepository
import tachiyomi.domain.discovery.AnimeCatalogCache
import tachiyomi.domain.discovery.AnimeCatalogRemote
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogCacheEntry
import tachiyomi.domain.discovery.CatalogCachePolicy
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogPage
import tachiyomi.domain.discovery.CatalogRequest
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CatalogRepositoryTest {
    private val now = 10_000_000L
    private val policy = CatalogCachePolicy(Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))
    private val title = CatalogAnime(CatalogId(value = 5), "Test anime")
    private val page = CatalogPage(listOf(title))
    private val request = CatalogRequest(CatalogFeed.TRENDING, date = "2026-09-08")
    private val json = Json { ignoreUnknownKeys = true }

    private class Cache : AnimeCatalogCache {
        val entries = ConcurrentHashMap<String, CatalogCacheEntry>()
        override suspend fun read(key: String) = entries[key]
        override suspend fun write(key: String, entry: CatalogCacheEntry, detail: Boolean) {
            entries[key] = entry
        }
    }

    private fun remote(calls: AtomicInteger, error: Exception? = null) = object : AnimeCatalogRemote {
        override suspend fun fetch(request: CatalogRequest): CatalogPage {
            calls.incrementAndGet()
            delay(30)
            if (error != null) throw error
            return page
        }
        override suspend fun details(id: CatalogId) = title
    }

    @Test
    fun `fresh cache avoids network and emits immediately`() = runBlocking {
        val cache = Cache()
        cache.entries[request.cacheKey] = CatalogCacheEntry(json.encodeToString(CatalogPage.serializer(), page), now)
        val calls = AtomicInteger()
        val states = CachedAnimeCatalogRepository(remote(calls), cache, json, policy).observe(request).toList()
        assertEquals(page, states.single().data)
        assertFalse(states.single().loading)
        assertEquals(0, calls.get())
    }

    @Test
    fun `outage keeps cached data and carries a typed reason to the home`() = runBlocking {
        val cache = Cache()
        cache.entries[request.cacheKey] = CatalogCacheEntry(json.encodeToString(CatalogPage.serializer(), page), 0)
        val outage = tachiyomi.domain.discovery.CatalogServiceUnavailableException("AniList unavailable")
        val result = CachedAnimeCatalogRepository(remote(AtomicInteger(), outage), cache, json, policy)
            .observe(request).toList().last()
        assertEquals(page, result.data)
        assertTrue(result.stale)
        assertEquals(tachiyomi.domain.discovery.CatalogFailureReason.SERVICE_UNAVAILABLE, result.failureReason)
    }

    @Test
    fun `expired cache remains visible on forbidden and rate limited responses`() = runBlocking {
        for (status in listOf(403, 429)) {
            val cache = Cache()
            cache.entries[request.cacheKey] = CatalogCacheEntry(json.encodeToString(CatalogPage.serializer(), page), 0)
            val states = CachedAnimeCatalogRepository(
                remote(AtomicInteger(), IOException("HTTP $status")),
                cache,
                json,
                policy,
            ).observe(request).toList()
            assertEquals(page, states.first().data)
            assertEquals(page, states.last().data)
            assertTrue(states.last().stale)
            assertEquals("HTTP $status", states.last().error)
            assertFalse(states.last().loading)
        }
    }

    @Test
    fun `offline never fetches even when cache is absent`() = runBlocking {
        val calls = AtomicInteger()
        val result = CachedAnimeCatalogRepository(
            remote(calls),
            Cache(),
            json,
            policy,
        ).observe(request, offline = true).toList().last()
        assertEquals(0, calls.get())
        assertNull(result.data)
        assertNotNull(result.error)
    }

    @Test
    fun `simultaneous requests share one successful fetch`() = runBlocking {
        val calls = AtomicInteger()
        val repository = CachedAnimeCatalogRepository(remote(calls), Cache(), json, policy)
        val results = List(6) { async { repository.observe(request).toList().last() } }.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(results.all { it.data == page && it.error == null })
    }

    @Test
    fun `cancellation is not converted to an error or cached`() {
        val cache = Cache()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                CachedAnimeCatalogRepository(
                    remote(AtomicInteger(), CancellationException()),
                    cache,
                    json,
                    policy,
                ).observe(request).toList()
            }
        }
        assertTrue(cache.entries.isEmpty())
    }

    @Test
    fun `corrupt cache recovers from server`() = runBlocking {
        val cache = Cache()
        cache.entries[request.cacheKey] = CatalogCacheEntry("invalid json", now)
        val result = CachedAnimeCatalogRepository(
            remote(AtomicInteger()),
            cache,
            json,
            policy,
        ).observe(request).toList().last()
        assertEquals(page, result.data)
        assertFalse(result.stale)
    }

    @Test
    fun `calendar and detail expiration and clock rollback are handled`() {
        assertEquals(900_000, policy.ttl(CatalogFeed.WEEK))
        assertEquals(1_800_000, policy.ttl(CatalogFeed.TOP))
        assertEquals(86_400_000, CatalogCachePolicy.DETAILS_TTL)
        assertFalse(policy.isFresh(CatalogCacheEntry("", now + 1), 100))
        assertFalse(policy.isFresh(CatalogCacheEntry("", now - 100), 100))
        assertTrue(policy.isFresh(CatalogCacheEntry("", now - 99), 100))
    }
}
