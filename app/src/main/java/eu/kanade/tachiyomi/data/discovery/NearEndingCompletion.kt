package eu.kanade.tachiyomi.data.discovery

import android.app.Application
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.anime.service.DelayedAnimeTrackingUpdateJob
import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import kotlin.time.Duration.Companion.minutes

/** Completes a recognized ending outside playback, so the player keeps its existing behavior. */
class NearEndingCompletion(
    private val anime: GetAnime,
    private val incognito: GetAnimeIncognitoState,
    private val episodes: EpisodeRepository,
    private val tracks: GetAnimeTracks,
    private val trackerManager: TrackerManager,
    private val trackPreferences: TrackPreferences,
    private val trackingQueue: DelayedAnimeTrackingStore,
    private val trackEpisode: TrackEpisode,
    private val app: Application,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun completeIfReached(animeId: Long, episode: Episode, cue: EpisodeEndingCue?) {
        if (episode.seen ||
            cue == null ||
            !cue.matches(episode.totalSeconds) ||
            !shouldAdvanceResume(episode.lastSecondSeen, episode.totalSeconds, cue)
        ) {
            return
        }

        mutex.withLock {
            try {
                val entry = anime.await(animeId) ?: return@withLock
                if (incognito.await(entry.source)) return@withLock
                val latest = episodes.getEpisodeById(episode.id) ?: return@withLock
                if (latest.animeId != animeId ||
                    latest.seen ||
                    !cue.matches(latest.totalSeconds) ||
                    !shouldAdvanceResume(latest.lastSecondSeen, latest.totalSeconds, cue)
                ) {
                    return@withLock
                }

                val canTrack = trackPreferences.autoUpdateTrack().get() &&
                    latest.episodeNumber.isFinite() &&
                    latest.episodeNumber > 0.0
                val pending = if (canTrack) {
                    tracks.await(animeId).filter { track ->
                        latest.episodeNumber > track.lastEpisodeSeen &&
                            trackerManager.get(track.trackerId)?.isLoggedIn == true
                    }
                } else {
                    emptyList()
                }
                episodes.updateEpisode(EpisodeUpdate(id = latest.id, seen = true))
                if (pending.isEmpty()) return@withLock
                pending.forEach { trackingQueue.addAnime(it.id, latest.episodeNumber) }
                DelayedAnimeTrackingUpdateJob.setupTask(app, initialDelay = 1.minutes)
                scope.launch {
                    try {
                        trackEpisode.await(app, animeId, latest.episodeNumber)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Could not update episode tracker" }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Could not complete episode near its ending" }
            }
        }
    }
}
