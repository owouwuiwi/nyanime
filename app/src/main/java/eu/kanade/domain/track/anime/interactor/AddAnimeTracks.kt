package eu.kanade.domain.track.anime.interactor

import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.EnhancedAnimeTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistory
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AddAnimeTracks(
    private val insertTrack: InsertAnimeTrack,
    private val syncChapterProgressWithTrack: SyncEpisodeProgressWithTrack,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val trackerManager: TrackerManager,
    private val history: suspend (Long) -> List<AnimeHistory> = { Injekt.get<GetAnimeHistory>().await(it) },
) {

    // TODO: update all trackers based on common data
    suspend fun bind(tracker: AnimeTracker, item: AnimeTrack, animeId: Long) = withNonCancellableContext {
        withIOContext {
            val allChapters = getEpisodesByAnimeId.await(animeId)
            val hasSeenEpisodes = allChapters.any { it.seen }
            tracker.bind(item, hasSeenEpisodes)

            var track = item.toDomainTrack(idRequired = false) ?: return@withIOContext
            val initialTrack = track

            insertTrack.await(track)

            // TODO: merge into [SyncChapterProgressWithTrack]?
            // Update chapter progress if newer chapters marked read locally
            if (hasSeenEpisodes) {
                val latestLocalReadChapterNumber = allChapters
                    .filter {
                        it.seen &&
                            it.episodeNumber > 0 &&
                            it.episodeNumber.isFinite() &&
                            (track.totalEpisodes <= 0 || it.episodeNumber <= track.totalEpisodes)
                    }
                    .maxOfOrNull { it.episodeNumber } ?: -1.0

                if (latestLocalReadChapterNumber > track.lastEpisodeSeen) {
                    track = track.copy(
                        lastEpisodeSeen = latestLocalReadChapterNumber,
                    )
                    tracker.setRemoteLastEpisodeSeen(track.toDbTrack(), latestLocalReadChapterNumber.toInt())
                }

                if (track.startDate <= 0) {
                    val firstReadChapterDate = history(animeId)
                        .mapNotNull { it.seenAt }
                        .filter { it.time > 0 }
                        .minByOrNull { it.time }

                    firstReadChapterDate?.let {
                        // History already stores an instant; shifting it again changes the calendar day.
                        val startDate = firstReadChapterDate.time
                        track = track.copy(
                            startDate = startDate,
                        )
                        tracker.setRemoteStartDate(track.toDbTrack(), startDate)
                    }
                }
            }

            // Keep the local row in step with the progress and start date sent above.
            if (track != initialTrack) insertTrack.await(track)
            syncChapterProgressWithTrack.await(animeId, track, tracker)
        }
    }

    suspend fun bindEnhancedTrackers(anime: Anime, source: AnimeSource) = withNonCancellableContext {
        withIOContext {
            trackerManager.loggedInTrackers()
                .filterIsInstance<EnhancedAnimeTracker>()
                .filter { it.accept(source) }
                .forEach { service ->
                    try {
                        service.match(anime)?.let { track ->
                            track.anime_id = anime.id
                            (service as Tracker).animeService.bind(track)
                            insertTrack.await(track.toDomainTrack(idRequired = false)!!)

                            syncChapterProgressWithTrack.await(
                                anime.id,
                                track.toDomainTrack(idRequired = false)!!,
                                service.animeService,
                            )
                        }
                    } catch (e: Exception) {
                        logcat(
                            LogPriority.WARN,
                            e,
                        ) { "Could not match anime: ${anime.title} with service $service" }
                    }
                }
        }
    }
}
