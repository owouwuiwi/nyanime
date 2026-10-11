package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode

class DiscoveryPlaybackService(
    private val resume: ResumeEpisodeSelector,
    private val downloads: AnimeDownloadManager,
    private val base: BasePreferences,
) {
    suspend fun nextEpisode(anime: Anime): Episode? = withContext(Dispatchers.IO) {
        resume.selectForAnime(anime.id) {
            !base.downloadedOnly().get() ||
                downloads.isEpisodeDownloaded(it.name, it.scanlator, anime.title, anime.source)
        }?.episode
    }
}
