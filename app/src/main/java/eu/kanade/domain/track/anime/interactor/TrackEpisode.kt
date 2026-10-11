package eu.kanade.domain.track.anime.interactor

import android.content.Context
import it.lorelore.nyanime.tracking.TrackingAutomaticProgress
import tachiyomi.core.common.util.lang.withNonCancellableContext

class TrackEpisode {
    suspend fun await(context: Context, animeId: Long, episodeNumber: Double) {
        withNonCancellableContext {
            TrackingAutomaticProgress.enqueue(context, false, animeId, episodeNumber)
        }
    }
}
