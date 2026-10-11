package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import nyanime.tv.api.TvStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class TvPlayerSessionTest {
    private val ref = TvChannelRef(50, "feed")
    private val media =
        TvPlayback(TvChannel(ref, "Channel"), listOf(TvStream("auto", "https://media.example/live", "Auto")))
    private class Native : TvNativeOutput {
        val calls = mutableListOf<String>()
        var ticket = 0L
        var window: ClosedFloatingPointRange<Double>? = null
        override fun load(stream: TvStream, ticket: Long) {
            this.ticket = ticket
            calls += "load"
        }
        override fun pause(value: Boolean) {
            calls += "pause:$value"
        }
        override fun speed(value: Double) {
            calls += "speed"
        }
        override fun seek(seconds: Double) {
            calls += "seek"
        }
        override fun enhancement(enabled: Boolean) {
            calls += "enhancement:$enabled"
        }
        override fun release() {
            calls += "release"
        }
        override fun seekWindow() = window
    }
    private fun repository(
        dispatcher: CoroutineDispatcher,
        playback: TvPlayback = media,
        resolve: suspend () -> TvPlayback = { playback },
    ): TvRepository {
        val source = object : TvSource {
            override val id = ref.sourceId
            override val name = "Test source"
            override val language = "all"
            override val capabilities = TvCapabilities(mapOf("en" to "TV"))
            override suspend fun catalogue(request: TvCatalogueRequest) = TvCatalogue(listOf(media.channel))
            override suspend fun programmes(request: TvGuideRequest) = TvGuide(emptyList())
            override suspend fun resolve(channel: TvChannelRef) = resolve()
        }
        val cache = object : TvSourceCache {
            override suspend fun get(key: String): TvCacheEntry? = null
            override suspend fun put(key: String, entry: TvCacheEntry) {}
        }
        return TvRepository(
            TvRegistry().apply {
                replace(listOf(TvRegistration(source, cache)))
            },
            TvPreferences(InMemoryPreferenceStore()),
            dispatcher,
        )
    }

    @Test fun `release disconnects all late events and controls from the native player`() = runTest {
        val native = Native()
        val session =
            TvPlayerSession(this, repository(StandardTestDispatcher(testScheduler)), native, {
                1000
            }, { true }, { false })
        session.select(ref)
        withTimeout(5000) { session.state.first { it.media != null } }
        testScheduler.runCurrent()
        session.close()
        val previous = native.calls.toList()
        session.loaded(native.ticket)
        session.failed(native.ticket)
        session.buffering(native.ticket, true)
        session.live()
        session.pause(true)
        session.room(true)
        session.speed(1.1)
        session.seek(30.0)
        session.quality(0)
        session.select(ref)
        assertEquals(previous, native.calls)
        assertEquals(1, native.calls.count { it == "release" })
    }

    @Test fun `a failed channel change cannot leave the previous broadcast playing under the new name`() = runTest {
        val native = Native()
        val session = TvPlayerSession(
            this,
            repository(StandardTestDispatcher(testScheduler)),
            native,
            { 1000 },
            { false },
            { false },
        )
        session.select(ref)
        testScheduler.runCurrent()
        val oldTicket = native.ticket
        session.loaded(oldTicket)
        session.select(TvChannelRef(51, "missing-feed"))
        assertEquals("pause:true", native.calls.last())
        session.loaded(oldTicket)
        session.buffering(oldTicket, true)
        testScheduler.runCurrent()
        assertTrue(session.state.value.failed)
        assertFalse(session.state.value.ready)
        assertFalse(session.state.value.buffering)
        assertEquals(1, native.calls.count { it == "load" })
        session.close()
    }

    @Test fun `resume reloads the live edge and unsupported seek and speed never reach native`() = runTest {
        val native = Native()
        val session =
            TvPlayerSession(this, repository(StandardTestDispatcher(testScheduler)), native, {
                1000
            }, { true }, { false })
        session.select(ref)
        withTimeout(5000) { session.state.first { it.media != null } }
        testScheduler.runCurrent()
        session.loaded(native.ticket)
        session.pause(true)
        session.live()
        session.seek(40.0)
        session.speed(1.5)
        assertEquals(2, native.calls.count { it == "load" })
        assertFalse(native.calls.any { it == "seek" || it == "speed" })
        session.room(true)
        assertEquals("enhancement:false", native.calls.last())
        session.close()
    }

    @Test fun `duplicate native failures schedule one retry and old callbacks stay ignored`() = runTest {
        val native = Native()
        val session =
            TvPlayerSession(this, repository(StandardTestDispatcher(testScheduler)), native, {
                1000
            }, { false }, { false })
        session.select(ref)
        testScheduler.runCurrent()
        val first = native.ticket
        session.failed(first)
        session.failed(first)
        session.loaded(first)
        session.buffering(first, true)
        assertEquals(1, session.state.value.retryAttempt)
        assertFalse(session.state.value.ready)
        assertFalse(session.state.value.buffering)
        advanceTimeBy(1000)
        testScheduler.runCurrent()
        assertEquals(2, native.calls.count { it == "load" })
        assertTrue(native.ticket > first)
        session.pause(true)
        assertFalse(session.state.value.loading)
        session.close()
    }

    @Test fun `stalled native loading retries locally and a pause cancels that recovery`() = runTest {
        val native = Native()
        val session =
            TvPlayerSession(this, repository(StandardTestDispatcher(testScheduler)), native, {
                testScheduler.currentTime +
                    1
            }, { false }, { false })
        session.select(ref)
        testScheduler.runCurrent()
        advanceTimeBy(30_000)
        testScheduler.runCurrent()
        assertEquals(1, session.state.value.retryAttempt)
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(2, native.calls.count { it == "load" })
        session.loaded(native.ticket)
        session.buffering(native.ticket, true)
        session.pause(true)
        advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(2, native.calls.count { it == "load" })
        assertTrue(session.state.value.paused)
        session.close()
    }

    @Test fun `manual enhancement is remembered across rooms and hidden while a room is active`() = runTest {
        val native = Native()
        val session =
            TvPlayerSession(this, repository(StandardTestDispatcher(testScheduler)), native, {
                1000
            }, { true }, { false })
        session.select(ref)
        testScheduler.runCurrent()
        assertTrue(session.state.value.enhanced)
        session.toggleEnhancement()
        session.room(true)
        session.toggleEnhancement()
        session.room(false)
        assertFalse(session.state.value.enhanced)
        session.toggleEnhancement()
        session.room(true)
        assertFalse(session.state.value.enhanced)
        session.room(false)
        assertTrue(session.state.value.enhanced)
        session.close()
    }

    @Test fun `extension removal invalidates the current load before its queued callbacks arrive`() = runTest {
        val native = Native()
        val session =
            TvPlayerSession(this, repository(StandardTestDispatcher(testScheduler)), native, {
                1000
            }, { false }, { false })
        session.select(ref)
        testScheduler.runCurrent()
        val ticket = native.ticket
        session.unavailable()
        val calls = native.calls.toList()
        session.loaded(ticket)
        session.buffering(ticket, false)
        session.failed(ticket)
        advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(calls, native.calls)
        assertTrue(session.state.value.failed && session.state.value.paused)
        assertFalse(session.state.value.ready)
        session.close()
    }

    @Test fun `rewind needs both an extension capability and a native seek window`() = runTest {
        val native = Native()
        val playback = media.copy(streams = media.streams.map { it.copy(rewind = true, adjustableSpeed = true) })
        val session =
            TvPlayerSession(this, repository(StandardTestDispatcher(testScheduler), playback), native, {
                1000
            }, { false }, { false })
        session.select(ref)
        testScheduler.runCurrent()
        session.seek(30.0)
        assertFalse("seek" in native.calls)
        native.window = 20.0..40.0
        session.seek(10.0)
        session.seek(Double.NaN)
        session.seek(30.0)
        assertEquals(1, native.calls.count { it == "seek" })
        session.speed(1.1)
        session.speed(3.0)
        assertEquals(1, native.calls.count { it == "speed" })
        session.close()
    }

    @Test fun `audio focus denial holds playback locally until the user resumes with focus`() = runTest {
        val native = Native()
        var focus = false
        val session =
            TvPlayerSession(this, repository(StandardTestDispatcher(testScheduler)), native, {
                1000
            }, { false }, { false }, { focus })
        session.select(ref)
        testScheduler.runCurrent()
        assertTrue(session.state.value.paused)
        assertFalse("load" in native.calls)
        focus = true
        session.live()
        assertEquals(1, native.calls.count { it == "load" })
        session.close()
    }

    @Test fun `an unreachable address stops after one fresh resolution without replaying the same URL`() = runTest {
        val native = Native()
        var resolutions = 0
        val session = TvPlayerSession(
            this,
            repository(StandardTestDispatcher(testScheduler), resolve = {
                resolutions++
                media
            }),
            native,
            { 1000 },
            { false },
            { false },
        )
        session.select(ref)
        testScheduler.runCurrent()
        val first = native.ticket
        session.failed(first, TvPlaybackFailure.ADDRESS)
        session.failed(first, TvPlaybackFailure.ADDRESS)
        testScheduler.runCurrent()
        advanceTimeBy(180_000)
        testScheduler.runCurrent()
        assertEquals(2, resolutions)
        assertEquals(1, native.calls.count { it == "load" })
        assertTrue(session.state.value.failed)
        assertFalse(session.state.value.loading)
        assertEquals(TvPlaybackFailure.ADDRESS, session.state.value.failure)
        session.close()
    }

    @Test fun `permanent failures try another declared stream of the same channel immediately`() = runTest {
        val native = Native()
        val playback = media.copy(
            streams = media.streams +
                media.streams.single().copy(
                    id = "backup",
                    url = "https://media.example/backup",
                ),
        )
        val session = TvPlayerSession(
            this,
            repository(StandardTestDispatcher(testScheduler), playback),
            native,
            { 1000 },
            { false },
            { false },
        )
        session.select(ref)
        testScheduler.runCurrent()
        session.failed(native.ticket, TvPlaybackFailure.UNAVAILABLE)
        testScheduler.runCurrent()
        assertEquals(2, native.calls.count { it == "load" })
        assertEquals(1, session.state.value.streamIndex)
        assertEquals(ref, session.state.value.reference)
        session.loaded(native.ticket)
        assertTrue(session.state.value.ready)
        assertEquals(null, session.state.value.failure)
        session.close()
    }

    @Test fun `fresh extension resolution may replace an expired URL without cycling rejected aliases`() = runTest {
        val native = Native()
        var resolutions = 0
        val replacement = media.copy(streams = listOf(media.streams.single().copy(url = "https://media.example/fresh")))
        val session = TvPlayerSession(
            this,
            repository(StandardTestDispatcher(testScheduler), resolve = {
                if (++resolutions == 1) media else replacement
            }),
            native,
            { 1000 },
            { false },
            { false },
        )
        session.select(ref)
        testScheduler.runCurrent()
        session.failed(native.ticket, TvPlaybackFailure.ACCESS)
        testScheduler.runCurrent()
        assertEquals(2, resolutions)
        assertEquals(2, native.calls.count { it == "load" })
        assertEquals(replacement.streams.single().url, session.state.value.stream?.url)
        session.failed(native.ticket, TvPlaybackFailure.ACCESS)
        testScheduler.runCurrent()
        assertEquals(2, resolutions)
        assertEquals(2, native.calls.count { it == "load" })
        assertTrue(session.state.value.failed)
        session.close()
    }

    @Test fun `refreshed headers restore the same URL without replaying unchanged requests`() = runTest {
        val native = Native()
        var resolutions = 0
        val refreshedStream = media.streams.single().copy(headers = mapOf("Authorization" to "test"))
        val refreshed = media.copy(streams = listOf(refreshedStream))
        val session = TvPlayerSession(
            this,
            repository(StandardTestDispatcher(testScheduler), resolve = {
                if (++resolutions == 1) media else refreshed
            }),
            native,
            { 1000 },
            { false },
            { false },
        )
        session.select(ref)
        testScheduler.runCurrent()
        session.failed(native.ticket, TvPlaybackFailure.ACCESS)
        testScheduler.runCurrent()
        assertEquals(2, native.calls.count { it == "load" })
        assertEquals(refreshed.streams.single(), session.state.value.stream)
        session.failed(native.ticket, TvPlaybackFailure.ACCESS)
        testScheduler.runCurrent()
        assertTrue(session.state.value.failed)
        assertEquals(2, resolutions)
        assertEquals(2, native.calls.count { it == "load" })
        session.close()
    }

    @Test fun `header capitalization and stream labels cannot bypass rejected request identity`() = runTest {
        val native = Native()
        val first = media.streams.single().copy(headers = mapOf("Authorization" to "test"))
        val alias = first.copy(id = "alias", label = "Alternate", headers = mapOf("authorization" to "test"))
        val playback = media.copy(streams = listOf(first, alias))
        val session = TvPlayerSession(
            this,
            repository(StandardTestDispatcher(testScheduler), playback),
            native,
            { 1000 },
            { false },
            { false },
        )
        session.select(ref)
        testScheduler.runCurrent()
        session.failed(native.ticket, TvPlaybackFailure.ACCESS)
        testScheduler.runCurrent()
        assertTrue(session.state.value.failed)
        assertEquals(1, native.calls.count { it == "load" })
        session.close()
    }

    @Test fun `pause cancels a permanent-error refresh without restarting the channel`() = runTest {
        val native = Native()
        var resolutions = 0
        val session = TvPlayerSession(
            this,
            repository(StandardTestDispatcher(testScheduler), resolve = {
                if (++resolutions > 1) kotlinx.coroutines.delay(1500)
                media
            }),
            native,
            { 1000 },
            { false },
            { false },
        )
        session.select(ref)
        testScheduler.runCurrent()
        session.failed(native.ticket, TvPlaybackFailure.ADDRESS)
        testScheduler.runCurrent()
        session.pause(true)
        advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertTrue(session.state.value.paused)
        assertFalse(session.state.value.loading)
        assertEquals(1, native.calls.count { it == "load" })
        session.close()
    }
}
