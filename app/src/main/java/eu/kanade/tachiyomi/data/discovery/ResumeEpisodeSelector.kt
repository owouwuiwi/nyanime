package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.flow.first
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.anime.interactor.GetNextEpisodes
import tachiyomi.domain.items.episode.model.Episode

data class ResumeEpisodeSelection(
    val episode: Episode,
    /** Kept available when the current episode is only being bypassed for the Home suggestion. */
    val finale: Episode? = null,
)

/** One source-independent rule for every "continue watching" entry point. */
class ResumeEpisodeSelector(
    private val next: GetNextEpisodes,
    private val history: GetAnimeHistory,
    private val cues: EpisodeEndingCueStore,
    private val completion: NearEndingCompletion,
) {
    suspend fun selectForAnime(
        animeId: Long,
        eligible: (Episode) -> Boolean = { true },
    ): ResumeEpisodeSelection? {
        val lastId = history.subscribe("").first().firstOrNull { it.animeId == animeId }?.episodeId
        return select(animeId, lastId, eligible)
    }

    suspend fun select(
        animeId: Long,
        fromEpisodeId: Long?,
        eligible: (Episode) -> Boolean = { true },
    ): ResumeEpisodeSelection? {
        val episodes = next.await(animeId, onlyUnseen = false)
        val currentIndex = episodes.indexOfFirst { it.id == fromEpisodeId }
        val current = episodes.getOrNull(currentIndex)
        val cue = current?.takeUnless { it.seen }?.let { cues.get(it.id) }
        val advance = current != null &&
            (
                current.seen ||
                    shouldAdvanceResume(
                        current.lastSecondSeen,
                        current.totalSeconds,
                        cue,
                    )
                )
        if (advance && current != null && !current.seen) {
            completion.completeIfReached(animeId, current, cue)
        }
        val candidates = episodes.drop(if (currentIndex < 0) 0 else currentIndex + if (advance) 1 else 0)
        val selected = candidates.firstOrNull { !it.seen && eligible(it) } ?: return null
        val finale = current?.takeIf {
            advance &&
                selected.id != it.id &&
                it.lastSecondSeen > 0 &&
                it.totalSeconds - it.lastSecondSeen > 5_000L
        }
        return ResumeEpisodeSelection(selected, finale)
    }
}
