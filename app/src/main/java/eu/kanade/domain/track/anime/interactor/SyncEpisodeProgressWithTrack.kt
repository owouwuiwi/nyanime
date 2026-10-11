package eu.kanade.domain.track.anime.interactor

import android.app.Application
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.EnhancedAnimeTracker
import it.lorelore.nyanime.tracking.TrackingAutomaticProgress
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.interactor.UpdateEpisode
import tachiyomi.domain.items.episode.model.toEpisodeUpdate
import tachiyomi.domain.track.anime.model.AnimeTrack
import kotlin.math.max

class SyncEpisodeProgressWithTrack(
    private val updateEpisode: UpdateEpisode,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val app: Application,
) {

    suspend fun await(
        animeId: Long,
        remoteTrack: AnimeTrack,
        service: AnimeTracker,
    ) {
        if (service !is EnhancedAnimeTracker) {
            return
        }

        val sortedEpisodes = getEpisodesByAnimeId.await(animeId)
            .sortedBy { it.episodeNumber }
            .filter { it.isRecognizedNumber }

        val episodeUpdates = sortedEpisodes
            .filter { episode -> episode.episodeNumber <= remoteTrack.lastEpisodeSeen && !episode.seen }
            .map { it.copy(seen = true).toEpisodeUpdate() }

        // only take into account continuous watching
        val localLastSeen = sortedEpisodes.takeWhile { it.seen }.lastOrNull()?.episodeNumber ?: 0F
        val lastSeen = max(remoteTrack.lastEpisodeSeen, localLastSeen.toDouble())

        try {
            updateEpisode.awaitAll(episodeUpdates)
            TrackingAutomaticProgress.enqueue(
                app,
                false,
                animeId,
                lastSeen,
                trackerId = (service as eu.kanade.tachiyomi.data.track.Tracker).id,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e)
        }
    }
}
