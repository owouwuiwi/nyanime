package eu.kanade.tachiyomi.data.discovery

import android.app.Application
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks

class NearEndingCompletionTest {
    private val anime = Anime.create().copy(id = 1, source = 2)
    private val episode = Episode.create().copy(
        id = 10,
        animeId = 1,
        lastSecondSeen = 835_000,
        totalSeconds = 1_200_000,
    )
    private val cue = EpisodeEndingCue(1_200_000, 840_000, 940_000)
    private val getAnime = mockk<GetAnime>()
    private val incognito = mockk<GetAnimeIncognitoState>()
    private val episodes = mockk<EpisodeRepository>()
    private val tracks = mockk<GetAnimeTracks>()
    private val trackerManager = mockk<TrackerManager>()
    private val trackPreferences = mockk<TrackPreferences>()
    private val trackingQueue = mockk<DelayedAnimeTrackingStore>()
    private val trackEpisode = mockk<TrackEpisode>()
    private val app = mockk<Application>()
    private val completion = NearEndingCompletion(
        getAnime, incognito, episodes, tracks, trackerManager,
        trackPreferences, trackingQueue, trackEpisode, app,
    )

    private fun prepare() {
        coEvery { getAnime.await(1) } returns anime
        every { incognito.await(2) } returns false
        coEvery { episodes.getEpisodeById(10) } returns episode
        coEvery { episodes.updateEpisode(any()) } just Runs
        every { trackPreferences.autoUpdateTrack().get() } returns false
    }

    @Test
    fun `five seconds before ending marks episode seen outside player`() = runBlocking {
        prepare()
        completion.completeIfReached(anime.id, episode, cue)
        coVerify(exactly = 1) { episodes.updateEpisode(match { it.id == episode.id && it.seen == true }) }
    }

    @Test
    fun `earlier progress and incognito never mark seen`() = runBlocking {
        prepare()
        completion.completeIfReached(anime.id, episode.copy(lastSecondSeen = 834_000), cue)
        every { incognito.await(2) } returns true
        completion.completeIfReached(anime.id, episode, cue)
        coVerify(exactly = 0) { episodes.updateEpisode(any()) }
    }
}
