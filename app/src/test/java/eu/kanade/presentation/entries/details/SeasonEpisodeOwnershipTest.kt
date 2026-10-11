package eu.kanade.presentation.entries.details

import aniyomi.domain.anime.SeasonAnime
import eu.kanade.domain.entries.anime.model.downloadedFilter
import eu.kanade.domain.entries.anime.model.seasonDownloadedFilter
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreenModel
import eu.kanade.tachiyomi.ui.entries.anime.AnimeSeasonItem
import eu.kanade.tachiyomi.ui.entries.anime.EpisodeList
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import java.lang.reflect.Proxy

class SeasonEpisodeOwnershipTest {
    @BeforeEach fun ordinaryDownloadFilters() {
        mockkStatic("eu.kanade.domain.entries.anime.model.AnimeKt")
        every { any<Anime>().downloadedFilter } returns TriState.DISABLED
        every { any<Anime>().seasonDownloadedFilter } returns TriState.DISABLED
    }

    @AfterEach fun restoreFilters() {
        unmockkStatic("eu.kanade.domain.entries.anime.model.AnimeKt")
    }

    private val source = Proxy.newProxyInstance(
        AnimeSource::class.java.classLoader,
        arrayOf(AnimeSource::class.java),
    ) { _, _, _ -> error("No provider access is needed for section ownership") } as AnimeSource
    private val parent = Anime.create().copy(
        id = 10,
        source = 37,
        title = "Synthetic collection",
        fetchType = FetchType.Seasons,
    )
    private val child = Anime.create().copy(id = 20, source = 37, title = "First collection")
    private fun state(loaded: Boolean) = AnimeScreenModel.State.Success(
        anime = parent,
        source = source,
        isFromSource = false,
        episodes = emptyList(),
        seasons = listOf(AnimeSeasonItem(SeasonAnime(child, 2, 0, 0, 0, 0, 0, 0))),
        seasonEpisodes = if (!loaded) {
            emptyMap()
        } else {
            mapOf(
                child.id to AnimeScreenModel.SeasonEpisodes(
                    child,
                    listOf(
                        EpisodeList.Item(
                            Episode.create().copy(id = 200, animeId = child.id),
                            AnimeDownload.State.NOT_DOWNLOADED,
                            0,
                        ),
                    ),
                    loaded = true,
                ),
            )
        },
    )

    @Test fun `native episode actions resolve the child owner rather than parent container`() {
        val state = state(true)
        assertEquals(child.id, state.displayEpisodes.single().episode.animeId)
        assertEquals(child, state.owner(child.id))
        assertEquals(parent, state.owner(parent.id))
        assertNull(state.owner(999))
    }

    @Test fun `next downloads do not interleave repeated ordinals from different owners`() {
        val firstOwner = child.copy(seasonSourceOrder = 0, episodeFlags = Anime.EPISODE_SORTING_NUMBER)
        val secondOwner = child.copy(
            id = 30,
            title = "Second collection",
            seasonSourceOrder = 1,
            episodeFlags = Anime.EPISODE_SORTING_NUMBER,
        )
        fun cached(owner: Anime, base: Long) = AnimeScreenModel.SeasonEpisodes(
            owner,
            listOf(2.0, 1.0).map { number ->
                EpisodeList.Item(
                    Episode.create().copy(
                        id = base + number.toLong(),
                        animeId = owner.id,
                        episodeNumber = number,
                    ),
                    AnimeDownload.State.NOT_DOWNLOADED,
                    0,
                )
            },
            loaded = true,
        )
        val state = state(true).copy(
            seasons = listOf(firstOwner, secondOwner).map { AnimeSeasonItem(SeasonAnime(it, 2, 0, 0, 0, 0, 0, 0)) },
            seasonEpisodes = mapOf(
                firstOwner.id to cached(firstOwner, 200),
                secondOwner.id to cached(secondOwner, 300),
            ),
        )
        assertEquals(listOf(201L, 202L, 301L, 302L), state.nextDownloadEpisodes().map { it.id })
    }

    @Test fun `unloaded native sections remain present without accessing a provider`() {
        val state = state(false)
        assertEquals(1, state.processedSeasons.size)
        assertEquals(child, state.owner(child.id))
        assertEquals(emptyList<EpisodeList.Item>(), state.displayEpisodes)
    }
}
