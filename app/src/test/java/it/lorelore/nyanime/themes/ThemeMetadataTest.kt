package it.lorelore.nyanime.themes

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class ThemeMetadataTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val identity = ThemeIdentity(123, 456)
    private fun document(id: Int = 456, links: String = "", spoiler: Boolean = false) = json.parseToJsonElement(
        """{"anime":[{"resources":[{"site":"MyAnimeList","external_id":$id},{"site":"AniList","external_id":123}],
        "animethemes":[{"type":"OP","sequence":2,"song":{"title":"A song","artists":[{"name":"An artist"}],
        "resources":[$links]},"animethemeentries":[{"version":1,"episodes":"13-24","spoiler":$spoiler}]}]}]}""",
    )

    @Test fun `verified catalogue identity preserves versions artists and episode ranges`() {
        val song = AnimeThemesProvider.parse(document(), identity).single()
        assertEquals(ThemeKind.OPENING, song.kind)
        assertEquals(2, song.number)
        assertEquals(listOf("An artist"), song.artists)
        assertTrue(song.includesEpisode(15))
        assertFalse(song.includesEpisode(12))
    }

    @Test fun `ignored filters or conflicting season ids never attach another work`() {
        assertTrue(AnimeThemesProvider.parse(document(id = 457), identity).isEmpty())
        val noIdentity = json.parseToJsonElement("""{"anime": [{"animethemes": []}]}""")
        assertTrue(AnimeThemesProvider.parse(noIdentity, identity).isEmpty())
    }

    @Test fun `multiple identity matches remain ambiguous`() {
        val one = document().toString().removePrefix("{\"anime\":[").removeSuffix("]}")
        assertTrue(AnimeThemesProvider.parse(json.parseToJsonElement("{\"anime\":[$one,$one]}"), identity).isEmpty())
    }

    @Test fun `song resource is not automatically a television sequence`() {
        val resource = """{"site":"YouTube","link":"https://www.youtube.com/watch?v=AbCdEf012_-"}"""
        val song = AnimeThemesProvider.parse(document(links = resource), identity).single()
        assertEquals(ThemeVideoKind.UNKNOWN, song.videos.single().kind)
    }

    @Test fun `music links can be full recordings and retain spoiler protection`() {
        val resource = """{"site":"YouTube Music","link":"https://music.youtube.com/watch?v=AbCdEf012_-"}"""
        val song = AnimeThemesProvider.parse(document(links = resource, spoiler = true), identity).single()
        assertEquals(ThemeVideoKind.FULL, song.videos.single().kind)
        assertTrue(song.videos.single().spoiler)
    }

    @Test fun `channel resources malformed ids and foreign hosts are never playable`() {
        listOf(
            "https://youtube.com/@artist",
            "https://youtube.com/playlist?list=AbCdEf012_-",
            "https://youtube.com.evil.invalid/watch?v=AbCdEf012_-",
            "https://evil.invalid/watch?v=AbCdEf012_-",
            "https://youtube.com/watch?v=short",
            "http://youtube.com/watch?v=AbCdEf012_-",
            "https://x:secret@youtube.com/watch?v=AbCdEf012_-",
        ).forEach {
            assertNull(ThemeVideoIds.fromUrl(it), it)
        }
    }

    @Test fun `supported video link forms resolve only opaque ids`() {
        listOf(
            "https://youtu.be/AbCdEf012_-",
            "https://youtube.com/watch?v=AbCdEf012_-&list=ignored",
            "https://www.youtube.com/embed/AbCdEf012_-",
            "https://m.youtube.com/shorts/AbCdEf012_-",
        ).forEach {
            assertEquals("AbCdEf012_-", ThemeVideoIds.fromUrl(it))
        }
    }

    @Test fun `television markers differ from full music videos and unclassified videos`() {
        assertEquals(ThemeVideoKind.SEQUENCE, ThemeVideoIds.classify("A song creditless opening"))
        assertEquals(ThemeVideoKind.FULL, ThemeVideoIds.classify("A song official music video"))
        assertEquals(ThemeVideoKind.UNKNOWN, ThemeVideoIds.classify("A song opening 1"))
        assertEquals(ThemeVideoKind.UNKNOWN, ThemeVideoIds.classify("A song official video"))
        assertEquals(ThemeVideoKind.UNKNOWN, ThemeVideoIds.classify("A song creditless official MV"))
    }

    @Test fun `free text episode descriptions are not invented intervals`() {
        val song =
            AnimeTheme(ThemeKind.ENDING, 1, "A song", versions = listOf(ThemeVersion(episodes = "1-4, 7, 12-15")))
        assertTrue(song.includesEpisode(7))
        assertFalse(song.includesEpisode(9))
        assertFalse(song.copy(versions = listOf(ThemeVersion(episodes = "1-?"))).includesEpisode(1))
    }

    @Test fun `text fallback preserves numbered songs and artists`() {
        val song = JikanThemesProvider.parseTheme("2: \"A song\" by An artist (eps 13-24)", ThemeKind.OPENING, 1)!!
        assertEquals(2, song.number)
        assertEquals("A song", song.title)
        assertEquals(listOf("An artist"), song.artists)
        assertTrue(song.includesEpisode(16))
    }

    @Test fun `music video association needs a unique song title and never a fragment`() {
        val themes = json.parseToJsonElement("""{"data":{"openings":["1: \"A song\" by An artist"],"endings":[]}}""")
        val videos = json.parseToJsonElement(
            """{"data":{"music_videos":[
          {"title":"Official music video","meta":{"title":"A song","author":"An artist"},
           "video":{"youtube_id":"AbCdEf012_-"}},
          {"title":"A song sequel full","meta":{"title":"A song sequel"},"video":{"youtube_id":"ZbCdEf012_-"}}
        ]}}""",
        )
        val song = JikanThemesProvider.parse(themes, videos).single()
        assertEquals(listOf("AbCdEf012_-"), song.videos.map { it.id })
        assertEquals(ThemeVideoKind.FULL, song.videos.single().kind)
    }

    @Test fun `duplicate songs merge resources without merging distinct numbered themes`() {
        val base =
            AnimeTheme(
                ThemeKind.OPENING,
                1,
                "A song",
                videos = listOf(ThemeVideo("AbCdEf012_-", ThemeVideoKind.UNKNOWN, ThemeOrigin.ANIMETHEMES)),
            )
        val other = base.copy(
            artists = listOf("An artist"),
            videos = listOf(base.videos.single().copy(kind = ThemeVideoKind.FULL, origin = ThemeOrigin.JIKAN)),
        )
        val merged = mergeThemes(listOf(base, other, base.copy(number = 2)))
        assertEquals(2, merged.size)
        assertEquals(1, merged.first().videos.size)
        assertEquals(ThemeVideoKind.FULL, merged.first().videos.single().kind)
    }

    @Test fun `conflicting video classifications remain explicit alternatives`() {
        val base =
            AnimeTheme(
                ThemeKind.OPENING,
                1,
                "A song",
                videos = listOf(ThemeVideo("AbCdEf012_-", ThemeVideoKind.FULL, ThemeOrigin.JIKAN)),
            )
        val other = base.copy(videos = listOf(base.videos.single().copy(kind = ThemeVideoKind.SEQUENCE)))
        assertEquals(ThemeVideoKind.UNKNOWN, mergeThemes(listOf(base, other)).single().videos.single().kind)
    }

    @Test fun `spoiler flags protect linked videos supplied by a different provider`() {
        val song = AnimeTheme(ThemeKind.OPENING, 1, "A song", versions = listOf(ThemeVersion(spoiler = true)))
        val other = song.copy(
            versions = emptyList(),
            videos = listOf(ThemeVideo("AbCdEf012_-", ThemeVideoKind.FULL, ThemeOrigin.JIKAN)),
        )
        assertTrue(mergeThemes(listOf(song, other)).single().videos.single().spoiler)
    }

    private class Store : PreferenceStore by InMemoryPreferenceStore() {
        val strings = mutableMapOf<String, Preference<String>>()
        override fun getString(
            key: String,
            defaultValue: String,
        ): Preference<String> = strings.getOrPut(key) { InMemoryPreferenceStore().getString(key, defaultValue) }
        override fun getAll(): Map<String, Any> = strings.mapValues { it.value.get() }
    }

    @Test fun `user choices stay separate per song version and survive a portable settings copy`() {
        val store = Store()
        val choices = ThemeChoices(store, json)
        val song = AnimeTheme(ThemeKind.OPENING, 1, "A song")
        val video = ThemeVideo("AbCdEf012_-", ThemeVideoKind.UNKNOWN, ThemeOrigin.USER)
        choices.select(identity.copy(malId = null), song, ThemeVideoKind.FULL, video)
        assertEquals(video.id, choices.selected(identity, song, ThemeVideoKind.FULL)?.id)
        assertNull(choices.selected(identity.copy(anilistId = 124), song, ThemeVideoKind.FULL))
        assertNull(choices.selected(identity, song, ThemeVideoKind.SEQUENCE))
        assertNull(choices.selected(identity, song.copy(number = 2), ThemeVideoKind.FULL))
        assertTrue(store.getAll().keys.all(BackupPreferencePolicy::isPortable))
        val restored = Store()
        store.getAll().forEach { (key, value) -> restored.getString(key).set(value as String) }
        assertEquals(video.id, ThemeChoices(restored, json).selected(identity, song, ThemeVideoKind.FULL)?.id)
    }
}
