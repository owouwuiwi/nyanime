package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.ui.updates.inboxKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import tachiyomi.domain.discovery.HomeSectionProvider
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.items.episode.interactor.GetEpisode
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.updates.anime.interactor.GetAnimeUpdates
import tachiyomi.source.local.entries.anime.isLocal
import java.time.Instant

data class LocalHomeItem(
    val anime: Anime,
    val episode: Episode,
    val updateKey: String? = null,
    val finale: Episode? = null,
    val forcedStartPositionMs: Long? = null,
) {
    val progress: Float get() = if (episode.totalSeconds > 0) {
        (episode.lastSecondSeen.toFloat() / episode.totalSeconds).coerceIn(0f, 1f)
    } else {
        0f
    }
}

private data class LocalEntry(val animeId: Long, val episodeId: Long, val updateKey: String? = null)

class LocalHomeSectionProvider(
    private val history: GetAnimeHistory,
    private val resumeSelector: ResumeEpisodeSelector,
    private val updates: GetAnimeUpdates,
    private val getAnime: GetAnime,
    private val getEpisode: GetEpisode,
    private val base: BasePreferences,
    private val uiPreferences: UiPreferences,
    private val incognito: GetAnimeIncognitoState,
    private val preferences: SourcePreferences,
    private val sources: AnimeSourceManager,
    private val downloads: AnimeDownloadManager,
    private val sourceService: DiscoverySourceService,
    private val endingCues: EpisodeEndingCueStore,
    private val resume: Boolean,
    private val sourceIds: Set<Long>? = null,
    private val visibility: ResumeVisibility,
) : HomeSectionProvider<LocalHomeItem> {
    override fun observe() = combine(
        if (resume) {
            history.subscribe("").combine(endingCues.observeChanges()) { list, _ ->
                list.distinctBy { it.animeId }.map { LocalEntry(it.animeId, it.episodeId) }
            }
        } else {
            updates.subscribe(Instant.now().minusSeconds(30 * 86_400))
                .combine(uiPreferences.dismissedLibraryUpdates().changes()) { list, dismissed ->
                    list.distinctBy { it.animeId }
                        .filterNot { it.seen || it.inboxKey() in dismissed }
                        .map { LocalEntry(it.animeId, it.episodeId, it.inboxKey()) }
                }
        },
        base.incognitoMode().changes(),
        base.downloadedOnly().changes(),
        combine(
            preferences.disabledAnimeSources().changes(),
            preferences.enabledLanguages().changes(),
            preferences.incognitoAnimeExtensions().changes(),
            preferences.showNsfwSource().changes(),
        ) { _, _, _, _ -> Unit },
        combine(sources.sources, visibility.hidden.changes()) { _, _ -> Unit },
    ) { entries, private, _, _, _ ->
        if (private && !resume) return@combine SectionState(data = emptyList(), loading = false)
        var accepted = 0
        val items = entries.mapNotNull { (animeId, episodeId, updateKey) ->
            if (accepted >= 30) return@mapNotNull null
            if (resume && animeId.toString() in visibility.hidden.get()) return@mapNotNull null
            val anime = getAnime.await(animeId) ?: return@mapNotNull null
            if (sourceIds != null && anime.source !in sourceIds) return@mapNotNull null
            if (anime.source.toString() in preferences.disabledAnimeSources().get()) return@mapNotNull null
            if (!anime.isLocal() &&
                sources.get(anime.source)?.let(sourceService::isEnabled) != true
            ) {
                return@mapNotNull null
            }
            if (incognito.await(anime.source)) return@mapNotNull null
            val selection = if (resume) {
                resumeSelector.select(animeId, episodeId) {
                    !base.downloadedOnly().get() || isDownloaded(anime, it)
                }
            } else {
                getEpisode.await(episodeId)?.let(::ResumeEpisodeSelection)
            }
            selection?.takeIf {
                val episode = it.episode
                !base.downloadedOnly().get() || isDownloaded(anime, episode)
            }?.let {
                accepted++
                LocalHomeItem(anime, it.episode, updateKey, it.finale)
            }
        }
        SectionState(data = items, loading = false)
    }.catch { emit(SectionState(loading = false, error = "Impossibile caricare la libreria")) }.flowOn(Dispatchers.IO)

    private fun isDownloaded(anime: Anime, episode: Episode) = downloads.isEpisodeDownloaded(
        episode.name,
        episode.scanlator,
        anime.title,
        anime.source,
    )
}
