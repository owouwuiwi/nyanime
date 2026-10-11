package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackTracksTest {
    @Test
    fun `atomic native snapshot accepts missing optional metadata and unknown fields`() {
        val tracks = PlaybackTrackSnapshot.decode(
            """[{"id":1,"type":"video","codec":"h264"},{"id":2,"type":"audio","lang":"en","selected":true},{"id":3,"type":"sub"}]""",
        )!!
        assertEquals(3, tracks.size)
        assertEquals("", tracks[1].title)
        assertEquals("en", tracks[1].lang)
        assertNull(tracks[2].lang)
    }

    @Test
    fun `unavailable metadata is different from an empty valid snapshot`() {
        assertNull(PlaybackTrackSnapshot.decode(null))
        assertNull(PlaybackTrackSnapshot.decode("not-json"))
        assertEquals(emptyList<NativePlaybackTrack>(), PlaybackTrackSnapshot.decode("[]"))
    }

    @Test
    fun `invalid native IDs cannot collide with unopened alternatives or Off`() {
        assertEquals(
            emptyList<NativePlaybackTrack>(),
            PlaybackTrackSnapshot.decode("""[{"id":-1,"type":"audio"},{"type":"sub"}]"""),
        )
    }

    @Test
    fun `all declared alternatives are visible without loading any of them`() {
        val declared = (1..38).map { ExternalPlaybackTrack("https://media.example/sub/$it", "Language $it") }
        val catalog = PlaybackTrackCatalog(subtitles = declared)
        val choices = catalog.choices(PlaybackTrackKind.Subtitle, emptyList())
        assertEquals(38, choices.size)
        assertEquals(38, choices.map { it.id }.distinct().size)
        assertTrue(choices.all { it.id < -1 })
        assertEquals(declared.last(), catalog.external(PlaybackTrackKind.Subtitle, choices.last().id))
    }

    @Test
    fun `a loaded alternative replaces its pending row and retains its declared label`() {
        val track = ExternalPlaybackTrack("https://media.example/audio/en", "English")
        val catalog = PlaybackTrackCatalog(audio = listOf(track))
        val native = listOf(NativePlaybackTrack(4, "audio", externalFilename = track.url))
        assertEquals(listOf(PlaybackTrackChoice(4, "English", null)), catalog.choices(PlaybackTrackKind.Audio, native))
        assertEquals(4, catalog.loadedId(PlaybackTrackKind.Audio, track.url, native))
    }

    @Test
    fun `opening a later language never reorders the menu`() {
        val declarations = (1..5).map { ExternalPlaybackTrack("https://media.example/audio/$it", "Language $it") }
        val catalog = PlaybackTrackCatalog(audio = declarations)
        val before = catalog.choices(PlaybackTrackKind.Audio, emptyList())
        val after = catalog.choices(
            PlaybackTrackKind.Audio,
            listOf(NativePlaybackTrack(7, "audio", externalFilename = declarations[3].url)),
        )
        assertEquals(before.map { it.title }, after.map { it.title })
        assertEquals(7, after[3].id)
        assertEquals(before[0].id, after[0].id)
    }

    @Test
    fun `equal labels never merge different streams or different track kinds`() {
        val first = ExternalPlaybackTrack("https://media.example/audio/one", "English")
        val second = ExternalPlaybackTrack("https://media.example/audio/two", "English")
        val catalog = PlaybackTrackCatalog(listOf(first, second), listOf(first))
        val native = listOf(NativePlaybackTrack(2, "sub", externalFilename = first.url))
        assertEquals(2, catalog.choices(PlaybackTrackKind.Audio, native).size)
        assertEquals(1, catalog.choices(PlaybackTrackKind.Subtitle, native).size)
        assertNull(catalog.loadedId(PlaybackTrackKind.Audio, first.url, native))
    }

    @Test
    fun `duplicate URLs are removed within a kind while order and embedded tracks survive`() {
        val first = ExternalPlaybackTrack("https://media.example/a", "First")
        val second = ExternalPlaybackTrack("https://media.example/b", "Second")
        val catalog = PlaybackTrackCatalog(listOf(first, first.copy(title = "Alias"), second))
        val choices = catalog.choices(PlaybackTrackKind.Audio, listOf(NativePlaybackTrack(1, "audio", "Embedded")))
        assertEquals(listOf("Embedded", "First", "Second"), choices.map { it.title })
        assertEquals(second, catalog.external(PlaybackTrackKind.Audio, -3))
    }

    @Test
    fun `invalid choice IDs cannot load another track`() {
        val catalog = PlaybackTrackCatalog(listOf(ExternalPlaybackTrack("https://media.example/a", "First")))
        listOf(-1, 0, 1, -3, Int.MIN_VALUE, Int.MAX_VALUE).forEach {
            assertNull(catalog.external(PlaybackTrackKind.Audio, it))
        }
    }

    @Test
    fun `external audio uses exact relative and absolute seeks even for fast requests`() {
        assertEquals("relative+exact", PlaybackSeekPolicy.flags(false, false, true))
        assertEquals("absolute+exact", PlaybackSeekPolicy.flags(true, false, true))
    }

    @Test
    fun `precise seeks are explicit while fast embedded seeks retain their behavior`() {
        assertEquals("relative+exact", PlaybackSeekPolicy.flags(false, true, false))
        assertEquals("absolute+exact", PlaybackSeekPolicy.flags(true, true, false))
        assertEquals("relative", PlaybackSeekPolicy.flags(false, false, false))
        assertEquals("absolute+keyframes", PlaybackSeekPolicy.flags(true, false, false))
    }
}
