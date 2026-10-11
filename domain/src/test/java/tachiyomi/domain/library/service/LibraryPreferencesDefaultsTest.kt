package tachiyomi.domain.library.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga

class LibraryPreferencesDefaultsTest {
    @Test
    fun `season defaults preserve download and watched filters independently`() {
        val downloads =
            listOf(Anime.SHOW_ALL, Anime.SEASON_SHOW_DOWNLOADED, Anime.SEASON_SHOW_NOT_DOWNLOADED)
        val watched = listOf(Anime.SHOW_ALL, Anime.SEASON_SHOW_UNSEEN, Anime.SEASON_SHOW_SEEN)
        downloads.forEach { downloaded ->
            watched.forEach { seen ->
                val preferences = LibraryPreferences(StoredPreferences())
                val anime = Anime.create().copy(seasonFlags = downloaded or seen)

                preferences.setSeasonSettingsDefault(anime)

                assertEquals(
                    downloaded,
                    preferences.filterSeasonByDownload().get(),
                    "download=$downloaded, seen=$seen",
                )
                assertEquals(
                    seen,
                    preferences.filterSeasonByUnseen().get(),
                    "download=$downloaded, seen=$seen",
                )
            }
        }
    }

    @Test
    fun `saving another title replaces filters including disabled values`() {
        val preferences = LibraryPreferences(StoredPreferences())
        preferences.setSeasonSettingsDefault(
            Anime.create()
                .copy(seasonFlags = Anime.SEASON_SHOW_DOWNLOADED or Anime.SEASON_SHOW_UNSEEN),
        )

        preferences.setSeasonSettingsDefault(
            Anime.create().copy(seasonFlags = Anime.SEASON_SHOW_SEEN),
        )

        assertEquals(Anime.SHOW_ALL, preferences.filterSeasonByDownload().get())
        assertEquals(Anime.SEASON_SHOW_SEEN, preferences.filterSeasonByUnseen().get())

        preferences.setSeasonSettingsDefault(Anime.create())

        assertEquals(Anime.SHOW_ALL, preferences.filterSeasonByDownload().get())
        assertEquals(Anime.SHOW_ALL, preferences.filterSeasonByUnseen().get())
    }

    @Test
    fun `season defaults leave episode and chapter filters unchanged`() {
        val preferences = LibraryPreferences(StoredPreferences())
        preferences.filterEpisodeByDownloaded().set(Anime.EPISODE_SHOW_DOWNLOADED)
        preferences.filterEpisodeBySeen().set(Anime.EPISODE_SHOW_SEEN)
        preferences.filterChapterByDownloaded().set(Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
        preferences.filterChapterByRead().set(Manga.CHAPTER_SHOW_READ)

        preferences.setSeasonSettingsDefault(
            Anime.create()
                .copy(seasonFlags = Anime.SEASON_SHOW_NOT_DOWNLOADED or Anime.SEASON_SHOW_UNSEEN),
        )

        assertEquals(Anime.EPISODE_SHOW_DOWNLOADED, preferences.filterEpisodeByDownloaded().get())
        assertEquals(Anime.EPISODE_SHOW_SEEN, preferences.filterEpisodeBySeen().get())
        assertEquals(
            Manga.CHAPTER_SHOW_NOT_DOWNLOADED,
            preferences.filterChapterByDownloaded().get(),
        )
        assertEquals(Manga.CHAPTER_SHOW_READ, preferences.filterChapterByRead().get())
    }

    /** Keep values across fresh preference handles, as the Android store does. */
    private class StoredPreferences : PreferenceStore by InMemoryPreferenceStore() {
        private val longs = mutableMapOf<String, Preference<Long>>()
        private val ints = mutableMapOf<String, Preference<Int>>()
        private val booleans = mutableMapOf<String, Preference<Boolean>>()

        override fun getLong(key: String, defaultValue: Long): Preference<Long> =
            longs.getOrPut(key) {
                InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
            }

        override fun getInt(key: String, defaultValue: Int): Preference<Int> =
            ints.getOrPut(key) {
                InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
            }

        override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
            booleans.getOrPut(key) {
                InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
            }
    }
}
