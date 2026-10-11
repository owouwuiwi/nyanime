package eu.kanade.tachiyomi.ui.privacy

import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaCover

enum class PrivacyMedia { VIDEO, MANGA }
data class PrivacySourceRatings(val video: Set<Long>, val manga: Set<Long>)

/** Application metadata cache; no screen, image, network URL or device API is retained. */
class PrivacyContentIndex(anime: AnimeExtensionManager, manga: MangaExtensionManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ratings = combine(anime.installedExtensionsFlow, manga.installedExtensionsFlow) { videos, books ->
        PrivacySourceRatings(
            videos.filter { it.isNsfw }.flatMap { it.sources }.mapTo(mutableSetOf()) { it.id },
            books.filter { it.isNsfw }.flatMap { it.sources }.mapTo(mutableSetOf()) { it.id },
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, currentRatings(anime, manga))

    fun isNsfw(data: Any?, snapshot: PrivacySourceRatings = ratings.value): Boolean = when (data) {
        is nyanime.tv.api.TvChannel -> data.adult
        is Anime -> NsfwContentPolicy.isNsfw(data.source in snapshot.video, data.genre)
        is Manga -> NsfwContentPolicy.isNsfw(data.source in snapshot.manga, data.genre)
        is AnimeCover -> data.sourceId in snapshot.video
        is MangaCover -> data.sourceId in snapshot.manga
        else -> false
    }

    private fun currentRatings(anime: AnimeExtensionManager, manga: MangaExtensionManager) = PrivacySourceRatings(
        anime.installedExtensionsFlow.value.filter { it.isNsfw }.flatMap { it.sources }.mapTo(mutableSetOf()) { it.id },
        manga.installedExtensionsFlow.value.filter { it.isNsfw }.flatMap { it.sources }.mapTo(mutableSetOf()) { it.id },
    )
}
