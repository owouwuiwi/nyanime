package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackChromeVisibilityTest {
    private fun resolve(
        load: PlaybackLoadMonitor.State,
        requested: Boolean = false,
        episode: Boolean = false,
        room: Boolean = false,
    ) = playbackChromeVisibility(requested, episode, room, load)

    @Test
    fun `a double tap native seek does not flash the timeline or custom actions`() {
        val ready = PlaybackLoadMonitor.State(started = true)
        assertEquals(PlaybackChromeVisibility(false, false), resolve(ready))
        assertEquals(PlaybackChromeVisibility(false, true), resolve(ready.copy(seeking = true)))
        assertEquals(PlaybackChromeVisibility(false, true), resolve(ready.copy(seeking = true, buffering = true)))
        assertEquals(PlaybackChromeVisibility(false, false), resolve(ready))
    }

    @Test
    fun `a cache stall requests only waiting feedback while controls are hidden`() {
        val loading = PlaybackLoadMonitor.State(started = true, buffering = true)
        assertEquals(PlaybackChromeVisibility(false, true), resolve(loading))
    }

    @Test
    fun `explicitly opened controls stay open through a seek or buffering`() {
        val load = PlaybackLoadMonitor.State(started = true, seeking = true, buffering = true)
        assertEquals(PlaybackChromeVisibility(true, false), resolve(load, requested = true))
    }

    @Test
    fun `initial opening and changing episode preserve the loading controls`() {
        assertEquals(PlaybackChromeVisibility(true, false), resolve(PlaybackLoadMonitor.State(opening = true)))
        assertEquals(PlaybackChromeVisibility(true, false), resolve(PlaybackLoadMonitor.State(), episode = true))
    }

    @Test
    fun `room preparation and playback errors retain actionable controls`() {
        assertEquals(
            PlaybackChromeVisibility(true, false),
            resolve(PlaybackLoadMonitor.State(started = true), room = true),
        )
        assertEquals(
            PlaybackChromeVisibility(true, false),
            resolve(PlaybackLoadMonitor.State(failure = PlaybackFailure.Network)),
        )
    }
}
