package it.lorelore.nyanime.tracking

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.EnhancedAnimeTracker
import eu.kanade.tachiyomi.data.track.EnhancedMangaTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.source.ConfigurableSource
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.animesource.sourcePreferences as animePreferences
import eu.kanade.tachiyomi.source.sourcePreferences as mangaPreferences

/** Enhanced services authenticate through a source. Treat its configuration as opaque, never interpret site keys. */
object TrackingAccountBinding {
    fun enhanced(service: Tracker) = service is EnhancedAnimeTracker || service is EnhancedMangaTracker

    fun current(service: Tracker, subject: TrackingSubject): String {
        val epoch = (service as? BaseTracker)?.queueAccountEpoch().orEmpty()
        if (!enhanced(service)) return epoch
        val preferences = if (subject.medium == TrackingMedium.ANIME) {
            (Injekt.get<AnimeSourceManager>().get(subject.source) as? ConfigurableAnimeSource)?.animePreferences()
        } else {
            (Injekt.get<MangaSourceManager>().get(subject.source) as? ConfigurableSource)?.mangaPreferences()
        } ?: throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        return TrackingRequestGuard.fingerprint(epoch, opaque(preferences))
    }

    private fun opaque(preferences: SharedPreferences) = configurationFingerprint(preferences.all)

    internal fun configurationFingerprint(values: Map<String, *>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        values.toSortedMap().forEach { (key, raw) ->
            val value = if (raw is Set<*>) {
                raw.map {
                    it.toString()
                }.sorted().joinToString("") { "${it.length}:$it" }
            } else {
                raw.toString()
            }
            val type = if (raw is Set<*>) "set" else raw?.javaClass?.name
            digest.update("${key.length}:$key:$type:${value.length}:".toByteArray())
            digest.update(value.toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
