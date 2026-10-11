package it.lorelore.nyanime.tracking

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Extends existing personal records on real completion; never changes seen/read flags. */
object PersonalTrackingObserver {
    suspend fun observe(context: Context, manga: Boolean, entryId: Long, progress: Double) {
        try {
            if (Injekt.get<BasePreferences>().incognitoMode().get()) return
            val subject = if (manga) {
                val entry = Injekt.get<GetManga>().await(entryId) ?: return
                if (Injekt.get<GetMangaIncognitoState>().await(entry.source)) return
                TrackingSubject(TrackingMedium.MANGA, entry.source, entry.url)
            } else {
                val entry = Injekt.get<GetAnime>().await(entryId) ?: return
                if (Injekt.get<GetAnimeIncognitoState>().await(entry.source)) return
                TrackingSubject(TrackingMedium.ANIME, entry.source, entry.url)
            }
            PersonalTrackingStore.get(context).observe(subject, progress)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Personal tracking observation failed" }
        }
    }
}
