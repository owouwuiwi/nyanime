package eu.kanade.domain.track.manga.interactor

import android.content.Context
import it.lorelore.nyanime.tracking.TrackingAutomaticProgress
import tachiyomi.core.common.util.lang.withNonCancellableContext

class TrackChapter {
    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double) {
        withNonCancellableContext {
            TrackingAutomaticProgress.enqueue(context, true, mangaId, chapterNumber)
        }
    }
}
