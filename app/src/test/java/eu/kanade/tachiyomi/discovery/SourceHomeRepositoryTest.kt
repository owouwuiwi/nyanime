package eu.kanade.tachiyomi.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.discovery.CachedSourceHomeRepository
import tachiyomi.domain.discovery.SourceHomeAccess
import tachiyomi.domain.discovery.SourceHomeCache
import tachiyomi.domain.discovery.SourceHomeCacheEntry
import tachiyomi.domain.discovery.SourceHomeCacheKey
import tachiyomi.domain.discovery.SourceHomeGateway
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.SourceHomeSource
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class SourceHomeRepositoryTest {
    @Test fun filteredSearchAndFullPagesNeverReuseThePublicPreviewDiskCache() = runBlocking {
        val gateway = Gateway()
        val disk = Disk()
        val repository = CachedSourceHomeRepository(gateway, TestClock(), persistent = disk)
        val requests = listOf(
            SourceHomeRequest("search", filters = mapOf("Category" to listOf("Adventure"))),
            SourceHomeRequest("popular", browse = true),
            SourceHomeRequest("popular", filters = mapOf("Category" to listOf("Drama"))),
        )
        requests.forEach { repository.observe(gateway.currentAccess(), it).toList() }
        repository.observe(gateway.currentAccess(), requests.first()).toList()
        assertEquals(3, gateway.calls.get())
        assertEquals(0, disk.reads)
        assertEquals(0, disk.writes)
    }

    @Test fun datesRemainSeparateInMemoryAndAcrossProcessRestarts() = runBlocking {
        val gateway = Gateway()
        val disk = Disk()
        val clock = TestClock()
        val repository = CachedSourceHomeRepository(gateway, clock, persistent = disk)
        val today = SourceHomeRequest("schedule")
        val first = today.copy(date = "2026-09-12")
        val second = today.copy(date = "2026-09-13")
        for (query in listOf(today, first, second, first)) repository.observe(gateway.currentAccess(), query).toList()
        assertEquals(3, gateway.calls.get())
        assertEquals(
            setOf("schedule", "schedule:date:2026-09-12", "schedule:date:2026-09-13"),
            disk.entries.keys.map {
                it.section
            }.toSet(),
        )
        val restored = CachedSourceHomeRepository(gateway, clock, persistent = disk)
        restored.observe(gateway.currentAccess(), second).toList()
        assertEquals(3, gateway.calls.get())
    }

    private class Gateway : SourceHomeGateway {
        val access = MutableStateFlow(SourceHomeAccess(SourceHomeSource(42, "16.1", emptyList(), emptyList())))
        val calls = AtomicInteger()
        var error: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override fun observeAccess() = access
        override fun currentAccess() = access.value
        override suspend fun fetch(access: SourceHomeAccess, request: SourceHomeRequest): SourceHomePage {
            calls.incrementAndGet()
            delay(20)
            gate?.await()
            error?.let { throw it }
            return SourceHomePage(emptyList(), request.page == 1)
        }
    }

    private class TestClock(var now: Long = 1000) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val request = SourceHomeRequest("popular")

    private class Disk : SourceHomeCache {
        val entries = mutableMapOf<SourceHomeCacheKey, SourceHomeCacheEntry>()
        var reads = 0
        var writes = 0
        var failure: Exception? = null
        override suspend fun read(key: SourceHomeCacheKey): SourceHomeCacheEntry? {
            reads++
            failure?.let { throw it }
            return entries[key]
        }
        override suspend fun write(key: SourceHomeCacheKey, entry: SourceHomeCacheEntry) {
            writes++
            failure?.let { throw it }
            entries[key] = entry
        }
    }

    @Test
    fun freshPublicFeedSurvivesRepositoryRecreationWithoutNetwork() = runBlocking {
        val gateway = Gateway()
        val disk = Disk()
        val clock = TestClock()
        CachedSourceHomeRepository(gateway, clock, persistent = disk).observe(gateway.currentAccess(), request).toList()
        val states = CachedSourceHomeRepository(gateway, clock, persistent = disk)
            .observe(gateway.currentAccess(), request).toList()
        assertEquals(1, gateway.calls.get())
        assertEquals(1, disk.writes)
        assertEquals(1, states.size)
        assertFalse(states.single().loading)
        assertTrue(states.single().data!!.hasNextPage)
    }

    @Test
    fun restoredFeedRefreshesOnDemandAndSurvivesNetworkFailureAfterExpiration() = runBlocking {
        val gateway = Gateway()
        val disk = Disk()
        val clock = TestClock()
        CachedSourceHomeRepository(gateway, clock, persistent = disk).observe(gateway.currentAccess(), request).toList()
        CachedSourceHomeRepository(gateway, clock, persistent = disk)
            .observe(gateway.currentAccess(), request, refresh = true).toList()
        assertEquals(2, gateway.calls.get())
        clock.now += CachedSourceHomeRepository.TTL
        gateway.error = IOException("HTTP 429")
        val states = CachedSourceHomeRepository(gateway, clock, persistent = disk)
            .observe(gateway.currentAccess(), request).toList()
        assertTrue(states.first().stale)
        assertTrue(states.first().data != null)
        assertEquals(states.first().data, states.last().data)
        assertEquals("HTTP 429", states.last().error)
    }

    @Test
    fun privateSearchOfflineAndUnavailableModesNeverTouchPersistentStorage() = runBlocking {
        val gateway = Gateway()
        val disk = Disk()
        val repository = CachedSourceHomeRepository(gateway, persistent = disk)
        repository.observe(gateway.currentAccess(), request.copy(query = "secret")).toList()
        repository.observe(gateway.currentAccess(), request.copy(sectionId = SourceHomeRequest.SEARCH)).toList()
        gateway.access.value = gateway.currentAccess().copy(isPrivate = true)
        repository.observe(gateway.currentAccess(), request).toList()
        gateway.access.value = gateway.currentAccess().copy(isPrivate = false, offline = true)
        repository.observe(gateway.currentAccess(), request).toList()
        gateway.access.value = SourceHomeAccess()
        repository.observe(gateway.currentAccess(), request).toList()
        assertEquals(0, disk.reads)
        assertEquals(0, disk.writes)
        assertEquals(3, gateway.calls.get())
    }

    @Test
    fun diskFailureCannotHideSuccessfulNetworkResultsButCancellationPropagates(): Unit = runBlocking {
        val gateway = Gateway()
        val disk = Disk().apply { failure = IOException("storage unavailable") }
        val states = CachedSourceHomeRepository(gateway, persistent = disk)
            .observe(gateway.currentAccess(), request).toList()
        assertNull(states.last().error)
        assertTrue(states.last().data != null)
        disk.failure = CancellationException("obsolete")
        assertThrows(CancellationException::class.java) {
            runBlocking {
                CachedSourceHomeRepository(gateway, persistent = disk)
                    .observe(gateway.currentAccess(), request).toList()
            }
        }
    }

    @Test
    fun restoredFeedRejectsOldRevisionExpiredDayAndBackwardClock() = runBlocking {
        for (change in 0..2) {
            val gateway = Gateway()
            val disk = Disk()
            val clock = TestClock()
            CachedSourceHomeRepository(
                gateway,
                clock,
                persistent = disk,
            ).observe(gateway.currentAccess(), request).toList()
            when (change) {
                0 -> gateway.access.value = gateway.currentAccess().copy(
                    source = gateway.currentAccess().source!!.copy(revision = "new"),
                )
                1 -> clock.now += 24 * 60 * 60_000L
                2 -> clock.now = 0
            }
            val states = CachedSourceHomeRepository(gateway, clock, persistent = disk)
                .observe(gateway.currentAccess(), request).toList()
            assertNull(states.first().data)
            assertEquals(2, gateway.calls.get())
        }
    }

    @Test
    fun inMemoryPagesAlsoExpireAfterOneDay() = runBlocking {
        val gateway = Gateway()
        val clock = TestClock()
        val repository = CachedSourceHomeRepository(gateway, clock)
        repository.observe(gateway.currentAccess(), request).toList()
        clock.now += 24 * 60 * 60_000L
        gateway.error = IOException("offline")
        val states = repository.observe(gateway.currentAccess(), request).toList()
        assertNull(states.first().data)
        assertNull(states.last().data)
    }

    @Test
    fun freshPageIsImmediateAndDuplicateCollectorsShareASingleRequest() = runBlocking {
        val gateway = Gateway()
        val repository = CachedSourceHomeRepository(gateway)
        List(8) { async { repository.observe(gateway.currentAccess(), request).toList() } }.awaitAll()
        val states = repository.observe(gateway.currentAccess(), request).toList()
        assertEquals(1, gateway.calls.get())
        assertEquals(1, states.size)
        assertFalse(states.single().loading)
        assertTrue(states.single().data!!.hasNextPage)
    }

    @Test
    fun expiredPageSurvivesPartialNetworkFailureAndRetriesSeparately() = runBlocking {
        val gateway = Gateway()
        val clock = TestClock()
        val repository = CachedSourceHomeRepository(gateway, clock)
        repository.observe(gateway.currentAccess(), request).toList()
        clock.now += CachedSourceHomeRepository.TTL
        gateway.error = IOException("HTTP 429")
        val result = repository.observe(gateway.currentAccess(), request).toList()
        assertTrue(result.first().stale)
        assertTrue(result.last().stale)
        assertEquals("HTTP 429", result.last().error)
        assertEquals(result.first().data, result.last().data)
        gateway.error = null
        assertNull(repository.observe(gateway.currentAccess(), SourceHomeRequest("films")).toList().last().error)
    }

    @Test
    fun privateModeBypassesCacheAndClearsPreviouslySavedPages() = runBlocking {
        val gateway = Gateway()
        val repository = CachedSourceHomeRepository(gateway)
        val normal = gateway.currentAccess()
        repository.observe(normal, request).toList()
        gateway.access.value = normal.copy(isPrivate = true)
        repeat(2) { repository.observe(gateway.currentAccess(), request).toList() }
        gateway.access.value = normal
        repository.observe(normal, request).toList()
        assertEquals(4, gateway.calls.get())
    }

    @Test
    fun downloadOnlyAndDisabledSourceNeverReadCacheOrNetwork() = runBlocking {
        val gateway = Gateway()
        val repository = CachedSourceHomeRepository(gateway)
        val normal = gateway.currentAccess()
        repository.observe(normal, request).toList()
        gateway.access.value = normal.copy(offline = true)
        assertNull(repository.observe(gateway.currentAccess(), request).toList().single().data)
        gateway.access.value = SourceHomeAccess()
        assertNull(repository.observe(normal, request).toList().single().data)
        assertEquals(1, gateway.calls.get())
    }

    @Test
    fun extensionRevisionPagesCategoriesAndTrimmedQueriesHaveDistinctKeys() = runBlocking {
        val gateway = Gateway()
        val repository = CachedSourceHomeRepository(gateway)
        val normal = gateway.currentAccess()
        repository.observe(normal, request).toList()
        repository.observe(normal, request.copy(page = 2)).toList()
        repository.observe(normal, request.copy(sectionId = "films")).toList()
        repository.observe(normal, request.copy(query = " test ")).toList()
        repository.observe(normal, request.copy(query = "test")).toList()
        gateway.access.value = normal.copy(source = normal.source!!.copy(revision = "16.2"))
        repository.observe(gateway.currentAccess(), request).toList()
        assertEquals(5, gateway.calls.get())
    }

    @Test
    fun cacheEvictionIsBoundedToConsultedPages() = runBlocking {
        val gateway = Gateway()
        val repository = CachedSourceHomeRepository(gateway, capacity = 2)
        for (page in 1..3) repository.observe(gateway.currentAccess(), request.copy(page = page)).toList()
        repository.observe(gateway.currentAccess(), request).toList()
        assertEquals(4, gateway.calls.get())
    }

    @Test
    fun cancelledSourceRequestsPropagateCancellation() {
        val gateway = Gateway().apply { error = CancellationException("obsolete search") }
        assertThrows(CancellationException::class.java) {
            runBlocking { CachedSourceHomeRepository(gateway).observe(gateway.currentAccess(), request).toList() }
        }
    }

    @Test
    fun disablingSourceDuringRequestDiscardsResult() = runBlocking {
        val gateway = Gateway().apply { gate = CompletableDeferred() }
        val repository = CachedSourceHomeRepository(gateway)
        val result = async { repository.observe(gateway.currentAccess(), request).toList() }
        while (gateway.calls.get() == 0) delay(1)
        gateway.access.value = SourceHomeAccess()
        gateway.gate!!.complete(Unit)
        assertNull(result.await().last().data)
    }
}
