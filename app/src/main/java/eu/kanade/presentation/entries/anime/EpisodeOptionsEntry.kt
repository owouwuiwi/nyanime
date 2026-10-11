package eu.kanade.presentation.entries.anime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import kotlin.coroutines.cancellation.CancellationException

internal data class EpisodeOptionsEntry(val episode: Episode, val anime: Anime)

/** The database may have changed since the user opened the episode's menu. */
internal suspend fun loadEpisodeOptionsEntry(
    episodeId: Long,
    animeId: Long,
    getEpisode: suspend (Long) -> Episode?,
    getAnime: suspend (Long) -> Anime?,
): Result<EpisodeOptionsEntry> {
    return try {
        currentCoroutineContext().ensureActive()
        val episode = getEpisode(episodeId)
        currentCoroutineContext().ensureActive()
        val anime = getAnime(animeId)
        currentCoroutineContext().ensureActive()
        if (episode == null || anime == null || episode.animeId != anime.id) {
            Result.failure(
                NoSuchElementException("The episode or its title is no longer available"),
            )
        } else {
            Result.success(EpisodeOptionsEntry(episode, anime))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
