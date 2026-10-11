package eu.kanade.tachiyomi.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.entries.anime.interactor.SyncSeasonsWithSource
import eu.kanade.domain.items.episode.interactor.SyncEpisodesWithSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.data.discovery.DiscoverySource
import eu.kanade.tachiyomi.data.discovery.DiscoverySourceService
import eu.kanade.tachiyomi.data.discovery.SmartSourceResolver
import eu.kanade.tachiyomi.data.discovery.SourceSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogSeriesEvidence
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.service.AnimeSourceManager

@Suppress("DEPRECATION")
class SmartSourceResolverTest {
    private val sources = mockk<DiscoverySourceService>()
    private val manager = mockk<AnimeSourceManager>()
    private val toLocal = mockk<NetworkToLocalAnime>()
    private val seasons = mockk<SyncSeasonsWithSource>()
    private val episodes = mockk<SyncEpisodesWithSource>()
    private val base = mockk<BasePreferences>()
    private val engine = mockk<AnimeSource>()
    private val source = DiscoverySource(2, "Fonte italiana", "it", true)
    private val local = Anime.create().copy(id = 10, source = 2, title = "Example", url = "/example")
    private val target = CatalogAnime(CatalogId(value = 20), "Example Season 2", episodes = 12)
    private val series = mockk<CatalogSeriesEvidence>()
    private val resolver = SmartSourceResolver(sources, manager, toLocal, seasons, episodes, base, series)

    private fun prepare(count: Int) {
        coEvery { engine.getAnimeEpisodeUpdate(any(), any(), any(), any()) } coAnswers {
            SAnimeEpisodeUpdate(
                engine.getAnimeDetails(firstArg()),
                if (arg<Boolean>(3)) engine.getEpisodeList(firstArg()) else emptyList(),
            )
        }
        coEvery { engine.getAnimeSeasonUpdate(any(), any(), any(), any()) } coAnswers {
            SAnimeSeasonUpdate(engine.getAnimeDetails(firstArg()), engine.getSeasonList(firstArg()))
        }
        coEvery { series.minimumCombinedEpisodes(any()) } returns 24
        every { base.downloadedOnly().get() } returns false
        coEvery { sources.resolve(any()) } returns null
        coEvery { sources.available() } returns listOf(source)
        coEvery { sources.remember(any(), any()) } returns Unit
        every { sources.search(any(), any()) } returns flowOf(SourceSearchResult(source, listOf(local)))
        every { manager.get(2) } returns engine
        every { engine.id } returns 2
        every { engine.name } returns source.name
        every { sources.isEnabled(engine) } returns true
        coEvery { engine.getAnimeDetails(any()) } returns SAnime.create().apply {
            title = "Example"
            url = local.url
            fetch_type = FetchType.Episodes
        }
        coEvery { engine.getEpisodeList(any()) } returns List(count) { index ->
            SEpisode.create().apply {
                name = "Episodio ${index + 1}"
                url = "/ep/$index"
                episode_number =
                    (index + 1).toFloat()
            }
        }
        coEvery { episodes.await(any(), any(), any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `catalogue season automatically opens combined source under original identity`() = runBlocking {
        prepare(24)
        assertEquals(local.id, resolver.resolve(target) {}?.id)
        coVerify(exactly = 1) { sources.remember(target, local) }
        coVerify(exactly = 1) {
            episodes.await(match { it.size == 24 }, match { it.id == local.id }, engine, any(), any())
        }
    }

    @Test
    fun `combined episodes use the same title number recognition as the library`() = runBlocking {
        prepare(24)
        coEvery { engine.getEpisodeList(any()) } returns List(24) { index ->
            SEpisode.create().apply {
                name = "Episode ${index + 1}"
                url = "/ep/$index"
                episode_number = -1f
            }
        }
        assertEquals(local.id, resolver.resolve(target) {}?.id)
    }

    @Test
    fun `first verified result stops unnecessary searches`() = runBlocking {
        prepare(24)
        every { sources.search(any(), any()) } returns kotlinx.coroutines.flow.flow {
            emit(SourceSearchResult(source, listOf(local)))
            error("A successful match must cancel remaining results")
        }
        assertEquals(local.id, resolver.resolve(target) {}?.id)
    }

    @Test
    fun `first season alone is not claimed as a combined second season`() = runBlocking {
        prepare(13)
        assertNull(resolver.resolve(target) {})
        coVerify(exactly = 0) { sources.remember(any(), any()) }
    }

    @Test
    fun `unknown catalogue structure does not invent a combined season`() = runBlocking {
        prepare(24)
        coEvery { series.minimumCombinedEpisodes(any()) } returns null
        assertNull(resolver.resolve(target) {})
    }

    @Test
    fun `single incompatible child is not selected just because it is alone`() = runBlocking {
        prepare(12)
        coEvery { engine.getAnimeDetails(any()) } returns SAnime.create().apply {
            title = "Example"
            url = local.url
            fetch_type = FetchType.Seasons
        }
        coEvery { engine.getSeasonList(any()) } returns listOf(
            SAnime.create().apply {
                title = "Example Season 1"
                url = "/first"
                season_number = 1.0
            },
        )
        assertNull(resolver.resolve(target) {})
    }

    @Test
    fun `offline resolution uses remembered identity without network`() = runBlocking {
        prepare(24)
        every { base.downloadedOnly().get() } returns true
        coEvery { sources.resolve(target) } returns local
        assertEquals(local.id, resolver.resolve(target) {}?.id)
        coVerify(exactly = 0) { engine.getAnimeDetails(any()) }
    }

    @Test
    fun `source season container opens matching child directly`() = runBlocking {
        prepare(12)
        val second = SAnime.create().apply {
            title = "Example Season 2"
            url = "/example/2"
            season_number = 2.0
        }
        coEvery { engine.getAnimeDetails(any()) } returns SAnime.create().apply {
            title = "Example"
            url = local.url
            fetch_type = FetchType.Seasons
        }
        coEvery { engine.getSeasonList(any()) } returns listOf(second)
        coEvery { seasons.await(any(), any(), any(), any(), any()) } returns emptyList()
        val child = local.copy(id = 11, url = second.url, title = second.title)
        coEvery { toLocal.await(any()) } returns child
        assertEquals(child.id, resolver.resolve(target) {}?.id)
    }
}
