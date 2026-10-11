package eu.kanade.tachiyomi.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.discovery.DiscoveryPlaybackService
import eu.kanade.tachiyomi.data.discovery.EpisodeEndingCue
import eu.kanade.tachiyomi.data.discovery.EpisodeEndingCueStore
import eu.kanade.tachiyomi.data.discovery.NearEndingCompletion
import eu.kanade.tachiyomi.data.discovery.ResumeEpisodeSelector
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.anime.interactor.GetNextEpisodes
import tachiyomi.domain.history.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.history.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.model.Episode

class DiscoveryPlaybackTest {
    private val anime = Anime.create().copy(id = 1, title = "Example", source = 2)
    private val first = Episode.create().copy(
        id = 10,
        animeId = 1,
        name = "Episode 1",
        sourceOrder = 1,
        episodeNumber = 1.0,
        lastSecondSeen = 40,
        totalSeconds = 100,
    )
    private val second = Episode.create().copy(
        id = 11,
        animeId = 1,
        name = "Episode 2",
        sourceOrder = 0,
        episodeNumber = 2.0,
    )
    private val history = mockk<GetAnimeHistory>()
    private val getAnime = mockk<GetAnime>()
    private val episodes = mockk<GetEpisodesByAnimeId>()
    private val downloads = mockk<AnimeDownloadManager>()
    private val base = mockk<BasePreferences>()
    private val next = GetNextEpisodes(episodes, getAnime, mockk<AnimeHistoryRepository>())
    private val cues = mockk<EpisodeEndingCueStore>()
    private val completion = mockk<NearEndingCompletion>(relaxed = true)
    private val selector = ResumeEpisodeSelector(next, history, cues, completion)
    private val service = DiscoveryPlaybackService(selector, downloads, base)

    private fun prepare(seen: Boolean = false) {
        coEvery { getAnime.await(1) } returns anime
        coEvery { episodes.await(1) } returns listOf(first.copy(seen = seen), second)
        every { history.subscribe("") } returns
            flowOf(listOf(AnimeHistoryWithRelations(1, 10, 1, anime.title, 1.0, null, anime.asAnimeCover())))
        every { base.downloadedOnly().get() } returns false
        coEvery { cues.get(any()) } returns null
    }

    @Test
    fun `incomplete episode resumes same identity and progress`() = runBlocking {
        prepare()
        val episode = service.nextEpisode(anime)
        assertEquals(first.id, episode?.id)
        assertEquals(40L, episode?.lastSecondSeen)
    }

    @Test
    fun `finished episode advances through existing episode ordering`() = runBlocking {
        prepare(seen = true)
        assertEquals(second.id, service.nextEpisode(anime)?.id)
    }

    @Test
    fun `last episode has nothing to resume`() = runBlocking {
        prepare(seen = true)
        coEvery { episodes.await(1) } returns listOf(first.copy(seen = true))
        assertNull(service.nextEpisode(anime))
    }

    @Test
    fun `download only never opens a remote next episode`() = runBlocking {
        prepare()
        every { base.downloadedOnly().get() } returns true
        every { downloads.isEpisodeDownloaded(any(), any(), any(), any(), any()) } returns false
        assertNull(service.nextEpisode(anime))
        every { downloads.isEpisodeDownloaded(any(), any(), any(), any(), any()) } returns true
        assertEquals(first.id, service.nextEpisode(anime)?.id)
    }

    @Test
    fun `recreating playback service preserves persisted position and bookmarks`() = runBlocking {
        prepare()
        val persisted = first.copy(lastSecondSeen = 1_250_000, totalSeconds = 2_400_000, bookmark = true)
        coEvery { episodes.await(1) } returns listOf(persisted, second)
        val recreated = DiscoveryPlaybackService(selector, downloads, base)
        assertEquals(persisted, recreated.nextEpisode(anime))
        assertEquals(persisted, recreated.nextEpisode(anime))
    }

    @Test
    fun `completed episode advances once and new partial progress is retained`() = runBlocking {
        prepare(seen = true)
        val persistedNext = second.copy(lastSecondSeen = 90_000, totalSeconds = 1_400_000)
        coEvery { episodes.await(1) } returns listOf(first.copy(seen = true), persistedNext)
        assertEquals(persistedNext, service.nextEpisode(anime))
        val recreated = DiscoveryPlaybackService(selector, downloads, base)
        assertEquals(persistedNext, recreated.nextEpisode(anime))
    }

    @Test
    fun `ending cue proposes next and requests completion`() = runBlocking {
        prepare()
        val unfinished = first.copy(lastSecondSeen = 835_000, totalSeconds = 1_200_000)
        coEvery { episodes.await(1) } returns listOf(unfinished, second)
        coEvery { cues.get(first.id) } returns EpisodeEndingCue(1_200_000, 840_000, 940_000)
        val selection = selector.selectForAnime(anime.id)
        assertEquals(second.id, selection?.episode?.id)
        assertEquals(unfinished.id, selection?.finale?.id)
        assertEquals(false, unfinished.seen)
        coVerify(exactly = 1) { completion.completeIfReached(anime.id, unfinished, any()) }
    }

    @Test
    fun `seen episode stopped before post credits still exposes its finale`() = runBlocking {
        prepare(seen = true)
        val stopped = first.copy(seen = true, lastSecondSeen = 900_000, totalSeconds = 1_200_000)
        coEvery { episodes.await(1) } returns listOf(stopped, second)
        val selection = selector.selectForAnime(anime.id)
        assertEquals(second.id, selection?.episode?.id)
        assertEquals(stopped.id, selection?.finale?.id)
    }

    @Test
    fun `already seen episode advances even before a recognized ending`() = runBlocking {
        prepare(seen = true)
        val stopped = first.copy(seen = true, lastSecondSeen = 700_000, totalSeconds = 1_200_000)
        coEvery { episodes.await(1) } returns listOf(stopped, second)
        coEvery { cues.get(first.id) } returns EpisodeEndingCue(1_200_000, 840_000, 940_000)
        assertEquals(second.id, selector.selectForAnime(anime.id)?.episode?.id)
        coVerify(exactly = 0) { completion.completeIfReached(any(), any(), any()) }
    }

    @Test
    fun `near end without next episode leaves no resume card`() = runBlocking {
        prepare()
        coEvery { episodes.await(1) } returns listOf(first.copy(lastSecondSeen = 990_000, totalSeconds = 1_000_000))
        assertNull(selector.selectForAnime(anime.id))
    }
}
