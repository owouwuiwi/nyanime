package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Playback times and episode progress are both stored in milliseconds. */
data class EpisodeEndingCue(val durationMs: Long, val startMs: Long, val endMs: Long) {
    fun matches(duration: Long): Boolean = duration > 0 &&
        abs(durationMs - duration) <= max(5_000L, duration / 50)

    companion object {
        fun from(stamps: List<TimeStamp>, durationSeconds: Int): EpisodeEndingCue? {
            if (durationSeconds < 120) return null
            val durationMs = durationSeconds * 1_000L
            return stamps.asSequence()
                .filter { it.type == ChapterType.Ending && it.start.isFinite() && it.end.isFinite() }
                .map { EpisodeEndingCue(durationMs, (it.start * 1_000).toLong(), (it.end * 1_000).toLong()) }
                .filter { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= durationMs }
                .filter { it.endMs >= durationMs * 7 / 10 }
                .maxByOrNull { it.endMs }
        }
    }
}

internal fun shouldAdvanceResume(positionMs: Long, durationMs: Long, cue: EpisodeEndingCue?): Boolean {
    if (durationMs < 120_000L || positionMs <= 0 || positionMs > durationMs + 2_000L) return false
    if (cue != null && cue.matches(durationMs)) {
        return positionMs >= (cue.startMs - 5_000L).coerceAtLeast(0L)
    }
    return durationMs - positionMs <= min(60_000L, durationMs / 20)
}

interface EpisodeEndingCueStore {
    suspend fun get(episodeId: Long): EpisodeEndingCue?
    suspend fun save(episodeId: Long, cue: EpisodeEndingCue)
    suspend fun remove(episodeId: Long)
    suspend fun forAnime(animeId: Long): Map<String, EpisodeEndingCue>
    fun observeChanges(): Flow<Long>
}

class SqlEpisodeEndingCueStore(private val handler: AnimeDatabaseHandler) : EpisodeEndingCueStore {
    override suspend fun get(episodeId: Long): EpisodeEndingCue? = handler.awaitOneOrNull {
        episodeEndingCueQueries.getByEpisodeId(episodeId) { _, duration, start, end ->
            EpisodeEndingCue(duration, start, end)
        }
    }

    override suspend fun save(episodeId: Long, cue: EpisodeEndingCue) {
        handler.await {
            episodeEndingCueQueries.upsert(episodeId, cue.durationMs, cue.startMs, cue.endMs)
        }
    }

    override suspend fun remove(episodeId: Long) {
        handler.await { episodeEndingCueQueries.deleteByEpisodeId(episodeId) }
    }

    override suspend fun forAnime(animeId: Long): Map<String, EpisodeEndingCue> = handler.awaitList {
        episodeEndingCueQueries.getByAnimeId(animeId) { url, duration, start, end ->
            url to EpisodeEndingCue(duration, start, end)
        }
    }.toMap()

    override fun observeChanges(): Flow<Long> = handler.subscribeToOne {
        episodeEndingCueQueries.countAll()
    }
}
