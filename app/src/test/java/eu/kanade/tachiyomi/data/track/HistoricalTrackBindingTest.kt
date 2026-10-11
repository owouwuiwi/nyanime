package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.domain.track.anime.interactor.SyncEpisodeProgressWithTrack
import eu.kanade.domain.track.manga.interactor.AddMangaTracks
import eu.kanade.domain.track.manga.interactor.SyncChapterProgressWithTrack
import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.database.models.manga.MangaTrack
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import tachiyomi.domain.track.manga.interactor.InsertMangaTrack
import tachiyomi.domain.track.manga.repository.MangaTrackRepository
import tachiyomi.domain.track.anime.model.AnimeTrack as DomainAnimeTrack
import tachiyomi.domain.track.manga.model.MangaTrack as DomainMangaTrack

class HistoricalTrackBindingTest {
    @Test
    fun `binding an unstarted library anime does not send progress to the tracker`() = runTest {
        val episodes = mockk<GetEpisodesByAnimeId>()
        coEvery { episodes.await(5) } returns listOf(
            Episode.create().copy(animeId = 5, seen = false, episodeNumber = 1.0),
        )
        val repository = mockk<AnimeTrackRepository>()
        coJustRun { repository.insertAnime(any()) }
        val sync = mockk<SyncEpisodeProgressWithTrack>()
        coJustRun { sync.await(any(), any(), any()) }
        val tracker = mockk<AnimeTracker>()
        val item = AnimeTrack.create(7).apply {
            anime_id = 5
            remote_id = 23
            title = "Example"
        }
        coEvery { tracker.bind(item, false) } returns item

        AddAnimeTracks(InsertAnimeTrack(repository), sync, episodes, mockk())
            .bind(tracker, item, 5)

        coVerify(exactly = 1) { tracker.bind(item, false) }
        coVerify(exactly = 0) { tracker.setRemoteLastEpisodeSeen(any(), any()) }
    }

    @Test
    fun `binding an unstarted library manga does not send progress to the tracker`() = runTest {
        val chapters = mockk<GetChaptersByMangaId>()
        coEvery { chapters.await(5, any()) } returns listOf(
            Chapter.create().copy(mangaId = 5, read = false, chapterNumber = 1.0),
        )
        val repository = mockk<MangaTrackRepository>()
        coJustRun { repository.insertManga(any()) }
        val sync = mockk<SyncChapterProgressWithTrack>()
        coJustRun { sync.await(any(), any(), any()) }
        val tracker = mockk<MangaTracker>()
        val item = MangaTrack.create(7).apply {
            manga_id = 5
            remote_id = 23
            title = "Example"
        }
        coEvery { tracker.bind(item, false) } returns item

        AddMangaTracks(InsertMangaTrack(repository), sync, chapters, mockk())
            .bind(tracker, item, 5)

        coVerify(exactly = 1) { tracker.bind(item, false) }
        coVerify(exactly = 0) { tracker.setRemoteLastChapterRead(any(), any()) }
    }

    @Test
    fun `binding an older anime keeps remote and local progress aligned across gaps`() = runTest {
        val episodes = mockk<GetEpisodesByAnimeId>()
        coEvery { episodes.await(5) } returns listOf(
            Episode.create().copy(animeId = 5, seen = true, episodeNumber = 1.0),
            Episode.create().copy(animeId = 5, seen = false, episodeNumber = 2.0),
            Episode.create().copy(animeId = 5, seen = true, episodeNumber = 3.0),
        )
        val stored = mutableListOf<DomainAnimeTrack>()
        val repository = mockk<AnimeTrackRepository>()
        coEvery { repository.insertAnime(any()) } answers { stored += firstArg<DomainAnimeTrack>() }
        val sync = mockk<SyncEpisodeProgressWithTrack>()
        coJustRun { sync.await(any(), any(), any()) }
        val tracker = mockk<AnimeTracker>()
        val item = AnimeTrack.create(7).apply {
            anime_id = 5
            remote_id = 23
            title = "Example"
            last_episode_seen = 1.0
            total_episodes = 12
            started_watching_date = 1
        }
        coEvery { tracker.bind(item, true) } returns item
        coJustRun { tracker.setRemoteLastEpisodeSeen(any(), 3) }

        AddAnimeTracks(InsertAnimeTrack(repository), sync, episodes, mockk())
            .bind(tracker, item, 5)

        assertEquals(listOf(1.0, 3.0), stored.map { it.lastEpisodeSeen })
        coVerify(exactly = 1) { tracker.setRemoteLastEpisodeSeen(any(), 3) }
    }

    @Test
    fun `binding an older manga keeps remote and local progress aligned across gaps`() = runTest {
        val chapters = mockk<GetChaptersByMangaId>()
        coEvery { chapters.await(5, any()) } returns listOf(
            Chapter.create().copy(mangaId = 5, read = true, chapterNumber = 1.0),
            Chapter.create().copy(mangaId = 5, read = false, chapterNumber = 2.0),
            Chapter.create().copy(mangaId = 5, read = true, chapterNumber = 3.0),
        )
        val stored = mutableListOf<DomainMangaTrack>()
        val repository = mockk<MangaTrackRepository>()
        coEvery { repository.insertManga(any()) } answers { stored += firstArg<DomainMangaTrack>() }
        val sync = mockk<SyncChapterProgressWithTrack>()
        coJustRun { sync.await(any(), any(), any()) }
        val tracker = mockk<MangaTracker>()
        val item = MangaTrack.create(7).apply {
            manga_id = 5
            remote_id = 23
            title = "Example"
            last_chapter_read = 1.0
            total_chapters = 12
            started_reading_date = 1
        }
        coEvery { tracker.bind(item, true) } returns item
        coJustRun { tracker.setRemoteLastChapterRead(any(), 3) }

        AddMangaTracks(InsertMangaTrack(repository), sync, chapters, mockk())
            .bind(tracker, item, 5)

        assertEquals(listOf(1.0, 3.0), stored.map { it.lastChapterRead })
        coVerify(exactly = 1) { tracker.setRemoteLastChapterRead(any(), 3) }
    }
}
