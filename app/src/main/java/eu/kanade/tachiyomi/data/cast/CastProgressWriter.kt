package eu.kanade.tachiyomi.data.cast

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.history.anime.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.items.episode.interactor.GetEpisode
import tachiyomi.domain.items.episode.interactor.UpdateEpisode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/** Single progress writer while a remote receiver owns playback; never rewrites library metadata. */
class CastProgressWriter(private val context: Context) {
    private val trackingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracking = Mutex()
    private var lastSaved: Triple<Long, Long, Long>? = null
    suspend fun save(media: CastMedia, playback: CastPlayback) {
        if (media.live != null || playback.durationMs <= 0 || playback.buffering) return
        val episodeId = media.episodeId ?: return
        val animeId = media.animeId ?: return
        val snapshot = Triple(episodeId, playback.positionMs, playback.durationMs)
        if (snapshot == lastSaved) return
        val episode = Injekt.get<GetEpisode>().await(episodeId) ?: return
        if (episode.animeId != animeId) return
        val incognito = Injekt.get<GetAnimeIncognitoState>().await(media.sourceId)
        val tracks = Injekt.get<GetAnimeTracks>().await(animeId)
        val threshold = Injekt.get<PlayerPreferences>().progressPreference().get()
        val position = playback.positionMs.coerceIn(0, playback.durationMs)
        val completed = position >= playback.durationMs * threshold
        if (!incognito || tracks.isNotEmpty()) {
            Injekt.get<UpdateEpisode>().await(
                EpisodeUpdate(
                    id = episode.id,
                    lastSecondSeen = position,
                    totalSeconds = playback.durationMs,
                    seen = episode.seen || completed,
                ),
            )
        }
        if (!incognito) {
            Injekt.get<UpsertAnimeHistory>().await(AnimeHistoryUpdate(episode.id, Date()))
        }
        if (completed &&
            !episode.seen &&
            tracks.isNotEmpty() &&
            !Injekt.get<BasePreferences>().incognitoMode().get() &&
            Injekt.get<TrackPreferences>().autoUpdateTrack().get()
        ) {
            // Tracker network requests must never hold up pause/seek or the final local handoff.
            trackingScope.launch {
                runCatching {
                    tracking.withLock {
                        Injekt.get<TrackEpisode>().await(context, animeId, episode.episodeNumber)
                    }
                }
            }
        }
        lastSaved = snapshot
    }
}
