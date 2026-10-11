package eu.kanade.tachiyomi.data.news

import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import kotlinx.coroutines.flow.first
import nyanime.news.api.NewsCatalogId
import nyanime.news.api.NewsMedium
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import tachiyomi.domain.track.manga.repository.MangaTrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class NewsPersonalTitle(
    val title: String,
    val ids: Set<NewsCatalogId>,
    val medium: NewsMedium,
    val key: String = "",
    val aliases: List<String> = emptyList(),
)
data class NewsPersonalLibrary(val titles: List<NewsPersonalTitle> = emptyList()) {
    val ids: Set<NewsCatalogId> get() = titles.flatMap { it.ids }.toSet()
}

/** Read-only union of the library and explicit release follows. It never creates tracker bindings. */
class NewsPersonalTitles {
    suspend fun load(): NewsPersonalLibrary {
        val anime = Injekt.get<AnimeRepository>()
        val manga = Injekt.get<MangaRepository>()
        val follows = ReleaseStore().backup().filter { it.mode == "FOLLOW" }
        val animeTracks = Injekt.get<AnimeTrackRepository>().getAnimeTracksAsFlow().first().groupBy { it.animeId }
        val mangaTracks = Injekt.get<MangaTrackRepository>().getMangaTracksAsFlow().first().groupBy { it.mangaId }
        val animeEntries = (
            anime.getAnimeFavorites() +
                follows.filter { it.medium == ReleaseMedium.ANIME.name }.mapNotNull {
                    anime.getAnimeByUrlAndSourceId(it.url, it.source)
                }
            ).distinctBy { it.id }
        val mangaEntries = (
            manga.getMangaFavorites() +
                follows.filter { it.medium == ReleaseMedium.MANGA.name }.mapNotNull {
                    manga.getMangaByUrlAndSourceId(it.url, it.source)
                }
            ).distinctBy { it.id }
        return NewsPersonalLibrary(
            animeEntries.map { entry ->
                val hints = SourceTrackingHints.from(entry)
                val ids = entry.homePresentation?.catalogIds.orEmpty().mapNotNull { (provider, value) ->
                    catalog(provider, value, NewsMedium.ANIME)
                }.toSet() +
                    setOfNotNull(
                        catalog("anilist", hints?.anilistId, NewsMedium.ANIME),
                        catalog("myanimelist", hints?.malId, NewsMedium.ANIME),
                    ) +
                    animeTracks[entry.id].orEmpty().mapNotNull { track ->
                        trackId(track.trackerId, track.remoteId, NewsMedium.ANIME)
                    }
                NewsPersonalTitle(
                    entry.title,
                    ids,
                    NewsMedium.ANIME,
                    NewsRules.key("ANIME:${entry.source}", entry.url),
                    (entry.homePresentation?.aliases.orEmpty() + hints?.titles.orEmpty()).distinct(),
                )
            } +
                mangaEntries.map { entry ->
                    NewsPersonalTitle(
                        entry.title,
                        mangaTracks[entry.id].orEmpty().mapNotNull {
                            trackId(it.trackerId, it.remoteId, NewsMedium.MANGA)
                        }.toSet(),
                        NewsMedium.MANGA,
                        NewsRules.key("MANGA:${entry.source}", entry.url),
                    )
                },
        )
    }

    private fun trackId(tracker: Long, id: Long, medium: NewsMedium) = when (tracker) {
        1L -> catalog("myanimelist", id, medium)
        2L -> catalog("anilist", id, medium)
        3L -> catalog("kitsu", id, medium)
        else -> null
    }

    private fun catalog(provider: String, value: Long?, medium: NewsMedium): NewsCatalogId? =
        value?.takeIf { it > 0 }?.let { NewsCatalogId(provider, it.toString(), medium) }
}
