package eu.kanade.tachiyomi.data.library.anime

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.model.Anime
import kotlin.math.abs

/** Persistent request pacing across Home visits and app restarts. No source-specific rules. */
class AnimeRefreshSchedule(private val store: PreferenceStore) {
    fun lastAutomaticBatch(): Long =
        store.getLong(Preference.appStateKey("anime_auto_batch_at"), 0L).get()

    fun recordAutomaticBatch(now: Long) {
        store.getLong(Preference.appStateKey("anime_auto_batch_at"), 0L).set(now)
    }

    fun lastAttempt(animeId: Long): Long = abs(result(animeId).get())

    fun priority(anime: Anime): Int = when (anime.status) {
        SAnime.ONGOING.toLong() -> 0
        SAnime.COMPLETED.toLong() -> 2
        else -> 1
    }

    fun isDue(anime: Anime, now: Long): Boolean {
        if (anime.updateStrategy == AnimeUpdateStrategy.ONLY_FETCH_ONCE) return false
        val previous = result(anime.id).get()
        if (previous == 0L) return true
        val interval = when {
            previous < 0L -> FAILURE_RETRY_MS
            anime.status == SAnime.COMPLETED.toLong() -> COMPLETED_INTERVAL_MS
            anime.status == SAnime.ONGOING.toLong() -> ONGOING_INTERVAL_MS
            else -> UNKNOWN_INTERVAL_MS
        }
        return now - abs(previous) >= interval
    }

    @Synchronized
    fun reserve(sourceId: Long, now: Long): Boolean {
        val window = store.getLong(Preference.appStateKey("recent_anime_window_$sourceId"), 0L)
        val count = store.getInt(Preference.appStateKey("recent_anime_count_$sourceId"), 0)
        val start = window.get()
        if (start <= 0L || now < start || now - start >= SOURCE_WINDOW_MS) {
            window.set(now)
            count.set(1)
            return true
        }
        if (count.get() >= MAX_PER_SOURCE_WINDOW) return false
        count.set(count.get() + 1)
        return true
    }

    fun record(animeId: Long, now: Long, successful: Boolean) {
        result(animeId).set(if (successful) now else -now)
    }

    private fun result(animeId: Long) =
        store.getLong(Preference.appStateKey("recent_anime_result_$animeId"), 0L)

    companion object {
        const val MAX_PER_HOME_VISIT = 12
        private const val MAX_PER_SOURCE_WINDOW = 12
        private const val SOURCE_WINDOW_MS = 60 * 60_000L
        private const val FAILURE_RETRY_MS = 60 * 60_000L
        private const val ONGOING_INTERVAL_MS = 12 * 60 * 60_000L
        private const val UNKNOWN_INTERVAL_MS = 24 * 60 * 60_000L
        private const val COMPLETED_INTERVAL_MS = 7 * 24 * 60 * 60_000L
    }
}
