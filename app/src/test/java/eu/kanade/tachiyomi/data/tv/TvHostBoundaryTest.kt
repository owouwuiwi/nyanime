package eu.kanade.tachiyomi.data.tv

import android.content.Context
import eu.kanade.tachiyomi.data.cast.CastDevice
import eu.kanade.tachiyomi.data.cast.CastMedia
import eu.kanade.tachiyomi.data.cast.CastPlayback
import eu.kanade.tachiyomi.data.cast.CastProgressWriter
import eu.kanade.tachiyomi.data.cast.CastProtocol
import eu.kanade.tachiyomi.data.cast.CastState
import eu.kanade.tachiyomi.data.cast.TvCastContent
import eu.kanade.tachiyomi.data.cast.liveObservation
import eu.kanade.tachiyomi.ui.search.AtlasCards
import eu.kanade.tachiyomi.ui.search.AtlasEntry
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvChannelRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.search.SearchMedium

class TvHostBoundaryTest {
    private val ref = TvChannelRef(42, "opaque-feed")

    @Test fun `a live receiver never invents a broadcast clock or samples another channel`() {
        val state = CastState(
            device = CastDevice("receiver", "TV", CastProtocol.DLNA),
            media = CastMedia(
                null, null, 42, "Channel", "", "https://media.example/live",
                "application/vnd.apple.mpegurl", 0, 0, live = TvCastContent(ref),
            ),
            playback = CastPlayback(positionMs = 30_000, paused = false),
        )
        val observation = state.liveObservation(ref)!!
        assertEquals(30.0, observation.positionSeconds)
        assertNull(observation.broadcastUtc())
        assertFalse(observation.canChangeSpeed)
        assertTrue(state.copy(connecting = true).liveObservation(ref)!!.buffering)
        assertTrue(state.copy(playback = state.playback.copy(paused = true)).liveObservation(ref)!!.paused)
        assertNull(state.liveObservation(TvChannelRef(42, "another-feed")))
        assertNull(state.copy(device = null).liveObservation(ref))
    }

    @Test fun `live Cast never saves episode progress even when a receiver reports a duration`() = runTest {
        val context = mockk<Context>()
        val media = CastMedia(
            1, 2, ref.sourceId, "Channel", "", "https://media.example/live",
            "application/vnd.apple.mpegurl", 0, 0, live = TvCastContent(ref),
        )
        CastProgressWriter(context).save(media, CastPlayback(positionMs = 90_000, durationMs = 100_000))
        verify { context wasNot Called }
    }

    @Test fun `TV and recorded video remain separate even if their opaque references coincide`() {
        val tv = AtlasEntry.live(TvChannel(ref, "Channel"))
        val video = AtlasEntry("video", SearchMedium.VIDEO, ref.sourceId, ref.id, "Channel", "video", "Video")
        assertEquals(2, AtlasCards.merge(listOf(tv, video)).size)
        assertEquals(1, AtlasCards.merge(listOf(tv, tv.copy(title = "Updated channel"))).size)
    }

    @Test fun `personal channel order and choices survive the ordinary settings restore`() {
        val saved = InMemoryPreferenceStore.InMemoryPreference("tv_personal_v1", null, "")
        val store = mockk<PreferenceStore> {
            every { getString("tv_personal_v1", "") } returns saved
        }
        val preferences = TvPreferences(store)
        val other = TvChannelRef(42, "second-feed")
        preferences.favourite(ref, true)
        preferences.favourite(other, true)
        preferences.moveFavourite(other, -1)
        preferences.update { it.copy(gridGuide = true, sources = mapOf("42" to TvSelection("XY", "en"))) }
        val backup = saved.get()
        val restored = InMemoryPreferenceStore(
            sequenceOf(InMemoryPreferenceStore.InMemoryPreference("tv_personal_v1", backup, "")),
        )
        assertEquals(preferences.read(), TvPreferences(restored).read())
        assertEquals(listOf(other, ref), TvPreferences(restored).read().favourites)
        assertFalse(backup.contains("https:"))
    }
}
