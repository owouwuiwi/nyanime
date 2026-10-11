package it.lorelore.nyanime.themes

import eu.kanade.tachiyomi.data.workinfo.WorkCacheEntry
import eu.kanade.tachiyomi.data.workinfo.WorkInfoCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AnimeThemesRepositoryTest {
    private val identity = ThemeIdentity(123, 456)
    private val song = AnimeTheme(ThemeKind.OPENING, 1, "A fictional song")
    private class Cache : WorkInfoCache {
        val values = mutableMapOf<String, WorkCacheEntry>()
        var writes = 0
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, entry: WorkCacheEntry) {
            writes++
            values[key] = entry
        }
    }
    private inner class Provider(override val origin: ThemeOrigin = ThemeOrigin.ANIMETHEMES) : ThemeProvider {
        var calls = 0
        var wait: CompletableDeferred<Unit>? = null
        var error: Exception? = null
        override suspend fun themes(identity: ThemeIdentity): List<AnimeTheme> {
            calls++
            wait?.await()
            error?.let { throw it }
            return listOf(song)
        }
    }
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000_000_000), ZoneOffset.UTC)

    @Test fun `disabled metadata and invalid ids never access providers`() = runTest {
        val provider = Provider()
        val repo =
            AnimeThemesRepository(listOf(provider), Cache(), Json, {
                false
            }, clock, this, StandardTestDispatcher(testScheduler))
        assertTrue(repo.observe(identity, true, true).toList().single().songs.isEmpty())
        assertEquals(0, provider.calls)
        val enabled =
            AnimeThemesRepository(listOf(provider), Cache(), Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        enabled.observe(ThemeIdentity(), true, true).toList()
        assertEquals(0, provider.calls)
    }

    @Test fun `fresh cache avoids network and stale data remains available offline`() = runTest {
        val provider = Provider()
        val cache = Cache()
        val repo =
            AnimeThemesRepository(listOf(provider), cache, Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        assertEquals(listOf(song), repo.observe(identity, true, true).toList().last().songs)
        assertEquals(listOf(song), repo.observe(identity, true, true).toList().single().songs)
        cache.values.replaceAll { _, value -> value.copy(savedAt = 0) }
        assertEquals(listOf(song), repo.observe(identity, false, true).toList().single().songs)
        assertEquals(1, provider.calls)
    }

    @Test fun `one provider can finish while another remains pending or fails`() = runTest {
        val first = Provider()
        val second = Provider(ThemeOrigin.JIKAN).also {
            it.wait = CompletableDeferred()
            it.error =
                IOException("Offline")
        }
        val repo =
            AnimeThemesRepository(listOf(first, second), Cache(), Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        val states = mutableListOf<ThemeCatalogue>()
        val collection = async { repo.observe(identity, true, true).toList(states) }
        runCurrent()
        assertTrue(states.any { it.loading && it.songs == listOf(song) })
        second.wait!!.complete(Unit)
        collection.await()
        assertEquals(listOf(song), states.last().songs)
        assertTrue(states.last().partialFailure)
    }

    @Test fun `partial data does not overwrite a complete disk snapshot`() = runTest {
        val provider = Provider()
        val cache = Cache()
        val repo =
            AnimeThemesRepository(listOf(provider), cache, Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        repo.observe(identity, true, true).toList()
        provider.error = PartialThemeData(listOf(song.copy(number = 2)))
        val last = repo.observe(identity, true, true, force = true).toList().last()
        assertEquals(2, last.songs.size)
        assertTrue(last.partialFailure)
        assertEquals(1, cache.writes)
    }

    @Test fun `concurrent readers share requests and one cancellation keeps the other alive`() = runTest {
        val provider = Provider().also { it.wait = CompletableDeferred() }
        val repo =
            AnimeThemesRepository(listOf(provider), Cache(), Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        val a = async { repo.observe(identity, true, true).toList() }
        val b = async { repo.observe(identity, true, true).toList() }
        runCurrent()
        assertEquals(1, provider.calls)
        a.cancelAndJoin()
        provider.wait!!.complete(Unit)
        assertEquals(listOf(song), b.await().last().songs)
    }

    @Test fun `leaving the last reader cancels its flight without writing a snapshot`() = runTest {
        val provider = Provider().also { it.wait = CompletableDeferred() }
        val cache = Cache()
        val repo =
            AnimeThemesRepository(listOf(provider), cache, Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        val collection = async { repo.observe(identity, true, true).toList() }
        runCurrent()
        collection.cancelAndJoin()
        provider.wait!!.complete(Unit)
        runCurrent()
        assertEquals(0, cache.writes)
    }

    @Test fun `incognito requests never share a persistent writer`() = runTest {
        val provider = Provider().also { it.wait = CompletableDeferred() }
        val cache = Cache()
        val repo =
            AnimeThemesRepository(listOf(provider), cache, Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        val a = async { repo.observe(identity, true, true).toList() }
        val b = async { repo.observe(identity, true, false).toList() }
        runCurrent()
        assertEquals(2, provider.calls)
        provider.wait!!.complete(Unit)
        a.await()
        b.await()
        assertEquals(1, cache.writes)
    }

    @Test fun `provider timeout reports an error without cancelling other data`() = runTest {
        val first = Provider()
        val second = Provider(ThemeOrigin.JIKAN).also { it.wait = CompletableDeferred() }
        val repo =
            AnimeThemesRepository(listOf(first, second), Cache(), Json, {
                true
            }, clock, this, StandardTestDispatcher(testScheduler))
        val collection = async { repo.observe(identity, true, true).toList() }
        runCurrent()
        advanceTimeBy(25_001)
        val last = collection.await().last()
        assertFalse(last.loading)
        assertTrue(last.partialFailure)
        assertEquals(listOf(song), last.songs)
    }
}
