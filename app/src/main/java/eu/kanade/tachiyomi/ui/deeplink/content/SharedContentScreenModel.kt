package eu.kanade.tachiyomi.ui.deeplink.content

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.domain.items.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.items.chapter.model.toSChapter
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseUpdateGate
import eu.kanade.tachiyomi.data.share.ContentLink
import eu.kanade.tachiyomi.data.share.ContentLinks
import eu.kanade.tachiyomi.data.share.SharedMedium
import eu.kanade.tachiyomi.data.watch.WatchCatalogReference
import eu.kanade.tachiyomi.source.MangaSourceUpdateGate
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import mihon.domain.source.interactor.UpdateAnimeFromRemote
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.items.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.episode.interactor.GetEpisodeByUrlAndAnimeId
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SharedContentScreenModel(private val encoded: String) : StateScreenModel<SharedContentScreenModel.State>(
    State.Loading,
) {
    private var job: Job? = null

    init {
        retry()
    }

    fun retry() {
        job?.cancel()
        mutableState.value = State.Loading
        job = screenModelScope.launchIO {
            val link = ContentLinks.decode(encoded)
            if (link == null) {
                mutableState.value = State.Failed(Failure.INVALID)
                return@launchIO
            }
            var entryId: Long? = null
            try {
                val result = withTimeout(30_000) {
                    when (link.medium) {
                        SharedMedium.TV -> throw ResolutionFailure(Failure.INVALID)
                        SharedMedium.ANIME -> {
                            val manager = Injekt.get<AnimeSourceManager>()
                            manager.isInitialized.first { it }
                            val source =
                                manager.get(link.sourceId) as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
                                    ?: throw ResolutionFailure(Failure.SOURCE_MISSING)
                            if (!WatchCatalogReference.isAllowed(
                                    link.entryUrl,
                                    source.baseUrl,
                                )
                            ) {
                                throw ResolutionFailure(Failure.INVALID)
                            }
                            val anime = Injekt.get<GetAnimeByUrlAndSourceId>().await(link.entryUrl, source.id)
                                ?: run {
                                    val reference = SAnime.create().apply {
                                        url = link.entryUrl
                                        title = link.title
                                    }
                                    val details = source.getAnimeDetails(reference).apply {
                                        url = link.entryUrl
                                        if (runCatching { title }.getOrNull().isNullOrBlank()) title = link.title
                                        initialized = true
                                    }
                                    Injekt.get<NetworkToLocalAnime>().await(details.toDomainAnime(source.id))
                                }
                            entryId = anime.id
                            val itemId = link.itemUrl?.let { url ->
                                val getItem = Injekt.get<GetEpisodeByUrlAndAnimeId>()
                                getItem.await(url, anime.id)?.id ?: run {
                                    Injekt.get<UpdateAnimeFromRemote>().awaitEpisodesUpdate(
                                        anime,
                                        fetchDetails = false,
                                        fetchEpisodes = true,
                                    ).getOrThrow()
                                    getItem.await(url, anime.id)?.id ?: throw ResolutionFailure(Failure.ITEM_MISSING)
                                }
                            }
                            State.Resolved(link, anime.id, itemId)
                        }
                        SharedMedium.MANGA -> {
                            val manager = Injekt.get<MangaSourceManager>()
                            manager.isInitialized.first { it }
                            val source =
                                manager.get(link.sourceId) as? eu.kanade.tachiyomi.source.online.HttpSource
                                    ?: throw ResolutionFailure(Failure.SOURCE_MISSING)
                            if (!WatchCatalogReference.isAllowed(
                                    link.entryUrl,
                                    source.baseUrl,
                                )
                            ) {
                                throw ResolutionFailure(Failure.INVALID)
                            }
                            val manga = Injekt.get<GetMangaByUrlAndSourceId>().await(link.entryUrl, source.id)
                                ?: run {
                                    val reference = SManga.create().apply {
                                        url = link.entryUrl
                                        title = link.title
                                    }
                                    val details = source.getMangaDetails(reference).apply {
                                        url = link.entryUrl
                                        if (runCatching { title }.getOrNull().isNullOrBlank()) title = link.title
                                        initialized = true
                                    }
                                    Injekt.get<NetworkToLocalManga>().await(details.toDomainManga(source.id))
                                }
                            entryId = manga.id
                            val itemId = link.itemUrl?.let { url ->
                                val getItem = Injekt.get<GetChapterByUrlAndMangaId>()
                                getItem.await(url, manga.id)?.id
                                    ?: ReleaseUpdateGate.withEntry(ReleaseMedium.MANGA, manga.id) {
                                        // Check again after acquiring the normal per-title update lock.
                                        getItem.await(url, manga.id)?.id ?: run {
                                            val chapters = Injekt.get<GetChaptersByMangaId>().await(manga.id)
                                            val remote = MangaSourceUpdateGate.await(
                                                source,
                                                manga.toSManga(),
                                                chapters.map { it.toSChapter() },
                                                fetchDetails = false,
                                                fetchChapters = true,
                                            ).chapters
                                            Injekt.get<SyncChaptersWithSource>().await(remote, manga, source, false)
                                            getItem.await(url, manga.id)?.id
                                                ?: throw ResolutionFailure(Failure.ITEM_MISSING)
                                        }
                                    }
                            }
                            State.Resolved(link, manga.id, itemId)
                        }
                    }
                }
                mutableState.value = result
            } catch (_: TimeoutCancellationException) {
                mutableState.value = State.Failed(Failure.NETWORK, link, entryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update {
                    State.Failed((e as? ResolutionFailure)?.failure ?: Failure.NETWORK, link, entryId)
                }
            }
        }
    }

    enum class Failure { INVALID, SOURCE_MISSING, ITEM_MISSING, NETWORK }
    private class ResolutionFailure(val failure: Failure) : Exception()

    sealed interface State {
        data object Loading : State
        data class Resolved(val link: ContentLink, val entryId: Long, val itemId: Long?) : State
        data class Failed(val failure: Failure, val link: ContentLink? = null, val entryId: Long? = null) : State
    }
}
