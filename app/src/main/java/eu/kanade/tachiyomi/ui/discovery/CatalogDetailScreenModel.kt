package eu.kanade.tachiyomi.ui.discovery

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.tachiyomi.data.discovery.DiscoveryPlaybackService
import eu.kanade.tachiyomi.data.discovery.DiscoverySourceService
import eu.kanade.tachiyomi.data.discovery.SourceSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.domain.discovery.AnimeCatalogRepository
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CatalogDetailScreenModel(
    private val id: CatalogId,
    private val catalog: AnimeCatalogRepository = Injekt.get(),
    private val sources: DiscoverySourceService = Injekt.get(),
    private val smartResolver: eu.kanade.tachiyomi.data.discovery.SmartSourceResolver = Injekt.get(),
    private val playback: DiscoveryPlaybackService = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val base: BasePreferences = Injekt.get(),
) : StateScreenModel<CatalogDetailScreenModel.State>(State()) {
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var action = Action.OPEN

    init {
        load()
    }

    fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = screenModelScope.launch {
            catalog.observeDetails(id, refresh, base.downloadedOnly().get()).collect { result ->
                mutableState.update { it.copy(details = result) }
                result.data?.let { anime ->
                    try {
                        val linked = sources.resolve(anime)
                        val episode = linked?.let { playback.nextEpisode(it) }
                        mutableState.update { it.copy(linked = linked, next = episode) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Catalog information remains usable when the local link is unavailable.
                    }
                }
            }
        }
    }

    fun open(requested: Action = Action.OPEN, chooseVersion: Boolean = false) {
        if (state.value.busy) return
        val anime = state.value.details.data ?: return
        action = requested
        screenModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            try {
                if (chooseVersion) {
                    mutableState.update { it.copy(resolver = true) }
                    search(anime.title)
                } else {
                    val linked = smartResolver.resolve(anime) { message ->
                        mutableState.update { it.copy(message = message) }
                    }
                    if (linked != null) finish(linked)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(message = e.message ?: "Versione non disponibile") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        mutableState.update {
            it.copy(query = query, results = emptyList(), searching = query.isNotBlank(), resolver = true)
        }
        searchJob = screenModelScope.launch {
            try {
                if (base.downloadedOnly().get()) {
                    mutableState.update { it.copy(message = "Disattiva Solo download per cercare nelle fonti") }
                } else {
                    sources.search(query).collect { result ->
                        mutableState.update {
                            it.copy(results = (it.results + result).sortedBy { entry -> entry.source.name })
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(message = e.message ?: "Ricerca non disponibile") }
            } finally {
                if (currentCoroutineContext().isActive) mutableState.update { it.copy(searching = false) }
            }
        }
    }

    fun closeResolver() {
        searchJob?.cancel()
        mutableState.update { it.copy(resolver = false, searching = false) }
    }

    fun choose(anime: Anime) {
        if (state.value.busy) return
        val catalogAnime = state.value.details.data ?: return
        screenModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            try {
                sources.remember(catalogAnime, anime)
                closeResolver()
                finish(anime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(message = e.message ?: "Impossibile collegare la versione") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun finish(anime: Anime) {
        val next = playback.nextEpisode(anime)
        mutableState.update { it.copy(linked = anime, next = next) }
        when (action) {
            Action.OPEN -> eventChannel.send(Event.OpenAnime(anime.id))
            Action.ADD -> {
                val success = anime.favorite || updateAnime.awaitUpdateFavorite(anime.id, true)
                mutableState.update {
                    it.copy(
                        linked = if (success) anime.copy(favorite = true) else anime,
                        message = if (success) "Aggiunto alla libreria" else "Impossibile aggiungere alla libreria",
                    )
                }
            }
            Action.RESUME -> {
                if (next != null) {
                    eventChannel.send(Event.Play(next))
                } else {
                    mutableState.update { it.copy(message = "Nessun episodio disponibile da riprendere") }
                }
            }
        }
    }

    enum class Action { OPEN, ADD, RESUME }
    sealed interface Event {
        data class OpenAnime(val id: Long) : Event
        data class Play(val episode: Episode) : Event
    }
    data class State(
        val details: SectionState<CatalogAnime> = SectionState(),
        val linked: Anime? = null,
        val next: Episode? = null,
        val busy: Boolean = false,
        val resolver: Boolean = false,
        val query: String = "",
        val searching: Boolean = false,
        val results: List<SourceSearchResult> = emptyList(),
        val message: String? = null,
    )
}
