package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import nyanime.tv.api.TvCacheEntry
import nyanime.tv.api.TvCapabilities
import nyanime.tv.api.TvCatalogue
import nyanime.tv.api.TvCatalogueRequest
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvChannelLookup
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvGuide
import nyanime.tv.api.TvGuideRequest
import nyanime.tv.api.TvPlayback
import nyanime.tv.api.TvProgramme
import nyanime.tv.api.TvRules
import nyanime.tv.api.TvSource
import nyanime.tv.api.TvSourceCache
import nyanime.tv.api.TvStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class TvRepositoryTest {
    private val ref = TvChannelRef(42, "opaque-feed")
    private class Cache : TvSourceCache {
        val entries = mutableMapOf<String, TvCacheEntry>()
        override suspend fun get(key: String) = entries[key]
        override suspend fun put(key: String, entry: TvCacheEntry) {
            entries[key] = entry
        }
    }
    private inner class Source : TvSource {
        override val id = 42L
        override val name = "Test source"
        override val language = "all"
        override val capabilities = TvCapabilities(mapOf("en" to "TV"), guide = true, channelLookup = true)
        var calls = 0
        var failure = false
        var cancelled = false
        var guideCalls = 0
        var failedChannels = emptySet<TvChannelRef>()
        var lookupCalls = 0
        var resolutions = 0
        var latency = 0L
        override suspend fun channels(references: List<TvChannelRef>): TvChannelLookup {
            lookupCalls++
            if (cancelled) throw CancellationException()
            if (failure) error("Unavailable")
            return TvChannelLookup(references.map { TvChannel(it, "Personal channel") })
        }
        override suspend fun catalogue(request: TvCatalogueRequest): TvCatalogue {
            calls++
            delay(latency)
            if (cancelled) throw CancellationException()
            if (failure) error("Unavailable")
            return TvCatalogue(listOf(TvChannel(ref, "Channel")))
        }
        override suspend fun programmes(request: TvGuideRequest): TvGuide {
            guideCalls++
            if (cancelled) throw CancellationException()
            if (failure) error("Unavailable")
            return TvGuide(
                request.channels.filterNot {
                    it in failedChannels
                }.map { TvProgramme("one", it, "Programme", request.startUtc, request.endUtc) },
                failed = request.channels.toSet().intersect(failedChannels),
            )
        }
        override suspend fun resolve(channel: TvChannelRef): TvPlayback {
            resolutions++
            return TvPlayback(TvChannel(ref, "Channel"), listOf(TvStream("one", "https://media.example/live", "Auto")))
        }
    }

    @Test fun `a failed refresh preserves the saved catalogue and its timestamp`() = runTest {
        val source = Source()
        val registry = TvRegistry().apply { replace(listOf(TvRegistration(source, Cache()))) }
        val repository =
            TvRepository(registry, TvPreferences(InMemoryPreferenceStore()), StandardTestDispatcher(testScheduler))
        val first = repository.catalogue(42, TvCatalogueRequest()).toList().last()
        source.failure = true
        val refresh = repository.catalogue(42, TvCatalogueRequest(refresh = true)).toList()
        assertEquals(first.data, refresh.first().data)
        assertTrue(refresh.last().error && refresh.last().saved)
        assertEquals(first.checkedAt, refresh.last().checkedAt)
    }

    @Test fun `extension removal cancels outstanding use and preserves personal references`() = runTest {
        val personal = TvPreferences(InMemoryPreferenceStore())
        personal.favourite(ref, true)
        val registry = TvRegistry()
        val repository = TvRepository(registry, personal, StandardTestDispatcher(testScheduler))
        assertTrue(repository.catalogue(42, TvCatalogueRequest()).toList().last().error)
        assertEquals(listOf(ref), personal.read().favourites)
    }

    @Test fun `incognito never adds television to recent viewing`() {
        val personal = TvPreferences(InMemoryPreferenceStore())
        personal.visited(ref, true)
        assertTrue(personal.read().recent.isEmpty())
        personal.visited(ref, false)
        assertEquals(listOf(ref), personal.read().recent)
    }

    @Test fun `damaged settings remain recoverable and cannot be overwritten by a favourite tap`() {
        val backing = InMemoryPreferenceStore.InMemoryPreference("tv_personal_v1", "", "")
        val store = object : tachiyomi.core.common.preference.PreferenceStore by InMemoryPreferenceStore() {
            override fun getString(
                key: String,
                defaultValue: String,
            ): tachiyomi.core.common.preference.Preference<String> {
                require(key == "tv_personal_v1")
                return backing
            }
        }
        val preferences = TvPreferences(store)
        preferences.favourite(ref, true)
        store.getString("tv_personal_v1", "").set("{incomplete")
        assertEquals(listOf(ref), preferences.read().favourites)
        assertTrue(preferences.fault.value)
        assertTrue(runCatching { preferences.favourite(ref, false) }.isFailure)
        assertEquals("{incomplete", store.getString("tv_personal_v1", "").get())
    }

    @Test fun `scrolling guide reuses overlapping channels and retains failed refreshes`() = runTest {
        val source = Source()
        val cache = Cache()
        val registry = TvRegistry().apply { replace(listOf(TvRegistration(source, cache))) }
        var clock = 1_800_000_000_000L
        val repository =
            TvRepository(registry, TvPreferences(InMemoryPreferenceStore()), StandardTestDispatcher(testScheduler), {
                clock
            })
        val second = TvChannelRef(42, "second-feed")
        val window = TvGuideRequest(listOf(ref), clock, clock + TvRules.DAY)
        repository.guide(window)
        val firstCheck = clock
        val expanded = repository.guideFlow(window.copy(channels = listOf(ref, second))).toList()
        assertEquals(1, expanded.first().data.programmes.size)
        assertEquals(setOf(second), expanded.first().loading)
        assertEquals(2, expanded.last().data.programmes.size)
        assertEquals(2, source.guideCalls)
        clock += 3_600_001
        source.failedChannels = setOf(ref)
        val refresh = repository.guide(window.copy(channels = listOf(ref, second)))
        assertEquals(setOf(ref), refresh.data.failed)
        assertEquals(setOf(ref), refresh.saved)
        assertEquals(firstCheck, refresh.checkedAt[ref])
        assertEquals(clock, refresh.checkedAt[second])
        assertEquals(2, refresh.data.programmes.size)
    }

    @Test fun `cancelled guide fetch cannot become a saved successful response`() = runTest {
        val source = Source().apply { cancelled = true }
        val cache = Cache()
        val registry = TvRegistry().apply { replace(listOf(TvRegistration(source, cache))) }
        val repository =
            TvRepository(registry, TvPreferences(InMemoryPreferenceStore()), StandardTestDispatcher(testScheduler))
        val request = TvGuideRequest(listOf(ref), 1_800_000_000_000L, 1_800_086_400_000L)
        val error = runCatching { repository.guide(request) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(cache.entries.isEmpty())
    }

    @Test fun `cancelled queued requests do not split the per-key lock`() = runTest {
        val gate = TvRequestGate()
        var active = 0
        var peak = 0
        (0..20).map {
            async {
                gate.run("same") {
                    active++
                    peak = maxOf(peak, active)
                    delay(10)
                    active--
                }
            }
        }.awaitAll()
        assertEquals(1, peak)
        assertEquals(0, active)
    }

    @Test fun `personal channels use metadata only and retain it after a failed refresh`() = runTest {
        val source = Source()
        val cache = Cache()
        val registry = TvRegistry().apply { replace(listOf(TvRegistration(source, cache))) }
        var clock = 1_800_000_000_000L
        val repository =
            TvRepository(registry, TvPreferences(InMemoryPreferenceStore()), StandardTestDispatcher(testScheduler), {
                clock
            })
        val first = repository.channels(listOf(ref))
        assertEquals("Personal channel", first.channels.single().name)
        repository.channels(listOf(ref))
        assertEquals(1, source.lookupCalls)
        assertEquals(0, source.resolutions)
        clock += TvRules.DAY + 1
        source.failure = true
        val stale = repository.channels(listOf(ref))
        assertEquals(first.channels, stale.channels)
        assertEquals(setOf(ref), stale.failed)
        registry.replace(emptyList())
        val removed = repository.channels(listOf(ref))
        assertTrue(removed.channels.isEmpty())
        assertEquals(setOf(ref), removed.unavailable)
    }

    @Test fun `corrupt cached catalogue cannot inject a foreign source reference`() = runTest {
        val source = Source()
        val cache = Cache()
        val key = "catalogue:null:null:null:null"
        cache.entries[key] =
            TvCacheEntry(
                """{"value":{"channels":[{"ref":{"sourceId":99,"id":"foreign"},"name":"Foreign"}]},"checkedAt":1800000000000}""",
                1_800_000_000_000L,
            )
        val registry = TvRegistry().apply { replace(listOf(TvRegistration(source, cache))) }
        val repository =
            TvRepository(registry, TvPreferences(InMemoryPreferenceStore()), StandardTestDispatcher(testScheduler), {
                1_800_000_000_000L
            })
        val emissions = repository.catalogue(42, TvCatalogueRequest()).toList()
        assertNull(emissions.first().data)
        assertEquals(ref, emissions.last().data!!.channels.single().ref)
        assertEquals(1, source.calls)
    }

    @Test fun `simultaneous forced refreshes coalesce even when the wall clock has not advanced`() = runTest {
        val source = Source()
        val registry = TvRegistry().apply { replace(listOf(TvRegistration(source, Cache()))) }
        val repository =
            TvRepository(registry, TvPreferences(InMemoryPreferenceStore()), StandardTestDispatcher(testScheduler), {
                1_800_000_000_000L
            })
        repository.catalogue(42, TvCatalogueRequest()).toList()
        source.latency = 10
        (1..2).map { async { repository.catalogue(42, TvCatalogueRequest(refresh = true)).toList() } }.awaitAll()
        assertEquals(2, source.calls)
    }
}
