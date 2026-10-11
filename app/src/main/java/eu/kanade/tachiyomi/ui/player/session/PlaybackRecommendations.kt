

package eu.kanade.tachiyomi.ui.player.session

import eu.kanade.domain.entries.anime.interactor.SyncRelatedAnimeWithSource
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.tachiyomi.data.discovery.DiscoveryPlaybackService
import eu.kanade.tachiyomi.data.discovery.DiscoverySourceService
import eu.kanade.tachiyomi.data.discovery.SourceHomeSourceChoice
import eu.kanade.tachiyomi.ui.player.PlayerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import mihon.domain.source.interactor.UpdateAnimeFromRemote
import tachiyomi.data.discovery.mergeHomeCards
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import tachiyomi.domain.entries.anime.interactor.GetRelatedAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeRelationGroup
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal data class PlaybackSuggestions(
    val related: List<AnimeRelationGroup> = emptyList(),
    val recommendations: List<Anime> = emptyList(),
)

/** Reuses extension-declared relationships and existing verified-identity merging. */
internal class PlaybackRecommendations(
    private val library: GetLibraryAnime = Injekt.get(),
    private val related: GetRelatedAnime = Injekt.get(),
    private val refresh: SyncRelatedAnimeWithSource = Injekt.get(),
    private val sources: DiscoverySourceService = Injekt.get(),
    private val incognito: GetAnimeIncognitoState = Injekt.get(),
) {
    suspend fun refresh(anime: Anime) = withContext(Dispatchers.IO) { refresh.await(anime) }

    fun observe(anime: Anime): Flow<PlaybackSuggestions> = combine(
        related.subscribe(anime.id),
        library.subscribe(),
    ) { relations, titles ->
        withContext(Dispatchers.IO) {
            val available = sources.available().map { it.id }.toSet()
            val privacy = mutableMapOf<Long, Boolean>()
            fun eligible(title: Anime): Boolean = title.id != anime.id &&
                title.source in available &&
                !privacy.getOrPut(title.source) { incognito.await(title.source) }
            val groups = relations.map { group ->
                group.copy(anime = mergeHomeCards(group.anime.filter(::eligible), mergeSourceVariants = true).take(12))
            }.filter { it.anime.isNotEmpty() }
            val grouped = groups.flatMap { it.anime }
            val excluded = (grouped + anime).flatMap { card ->
                card.homePresentation?.choices.orEmpty().map { it.animeId } +
                    card.id
            }.toSet()
            val excludedIds = (grouped + anime).flatMap { it.homePresentation?.catalogIds.orEmpty().entries }.map {
                it.key to
                    it.value
            }.toSet()
            val genres = anime.genre.orEmpty().map { it.lowercase(java.util.Locale.ROOT) }.toSet()
            val candidates = titles.distinctBy { it.id }.asSequence()
                .filter {
                    it.id !in excluded &&
                        it.unseenCount > 0 &&
                        eligible(it.anime) &&
                        it.anime.homePresentation?.catalogIds.orEmpty().none { id ->
                            (id.key to id.value) in excludedIds
                        }
                }
                .map { entry ->
                    val overlap = entry.anime.genre.orEmpty().count { it.lowercase(java.util.Locale.ROOT) in genres }
                    val sameAuthor =
                        anime.author?.takeIf { it.isNotBlank() } == entry.anime.author && anime.author != null
                    entry.anime to (overlap * 2 + if (sameAuthor) 3 else 0)
                }.filter { it.second >= 2 }
                .sortedByDescending { it.second }.map { it.first }.take(32).toList()
            PlaybackSuggestions(groups, mergeHomeCards(candidates, mergeSourceVariants = true).take(12))
        }
    }

    companion object {
        /** The existing resume selector remains the authority for saved progress and first unseen episodes. */
        suspend fun request(session: PlayerSession, card: Anime): android.content.Intent = withContext(Dispatchers.IO) {
            val selectedId = SourceHomeSourceChoice.preferredAnimeId(session, card)
            val anime = Injekt.get<GetAnime>().await(selectedId) ?: throw
                eu.kanade.tachiyomi.ui.player.PlayerViewModel.ExceptionWithStringResource(
                    "Title unavailable",
                    tachiyomi.i18n.aniyomi.AYMR.strings.no_episode_loaded,
                )
            val episodes = Injekt.get<GetEpisodesByAnimeId>()
            if (episodes.await(anime.id).isEmpty()) {
                Injekt.get<UpdateAnimeFromRemote>().awaitEpisodesUpdate(
                    anime,
                    fetchDetails = !anime.initialized,
                    fetchEpisodes = true,
                )
                    .getOrThrow()
            }
            val episode = Injekt.get<DiscoveryPlaybackService>().nextEpisode(anime) ?: throw
                eu.kanade.tachiyomi.ui.player.PlayerViewModel.ExceptionWithStringResource(
                    "Episode unavailable",
                    tachiyomi.i18n.aniyomi.AYMR.strings.no_episode_loaded,
                )
            eu.kanade.tachiyomi.ui.player.PlayerActivity.newIntent(session, anime.id, episode.id)
        }
    }
}
