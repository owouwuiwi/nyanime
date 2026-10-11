package eu.kanade.tachiyomi.ui.discovery

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.discovery.DiscoverySource
import eu.kanade.tachiyomi.data.discovery.DiscoverySourceService
import eu.kanade.tachiyomi.data.discovery.LocalHomeItem
import eu.kanade.tachiyomi.data.discovery.LocalHomeSections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.discovery.AnimeCatalogRepository
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogPage
import tachiyomi.domain.discovery.CatalogRequest
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DiscoveryScreenModel(
    private val catalog: AnimeCatalogRepository = Injekt.get(),
    private val sourceService: DiscoverySourceService = Injekt.get(),
    private val locals: LocalHomeSections = Injekt.get(),
    private val base: BasePreferences = Injekt.get(),
    private val sources: AnimeSourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    store: PreferenceStore = Injekt.get(),
) : StateScreenModel<DiscoveryScreenModel.State>(State()) {
    private val jobs = mutableMapOf<CatalogFeed, Job>()
    private val sourceJobs = mutableMapOf<Boolean, Job>()
    private val selectedPreference = store.getLong("fork_discovery_selected_source", -1)

    init {
        screenModelScope.launch {
            locals.resume.observe().collect { data -> mutableState.update { it.copy(resume = data) } }
        }
        screenModelScope.launch {
            locals.updates.observe().collect { data -> mutableState.update { it.copy(updates = data) } }
        }
        screenModelScope.launch {
            base.downloadedOnly().changes().collectLatest { offline ->
                mutableState.update { it.copy(offline = offline) }
                feeds.forEach { load(it) }
                sourceJobs.values.forEach(Job::cancel)
                state.value.selectedSource?.let { selectSource(it) }
            }
        }
        screenModelScope.launch {
            combine(
                sources.sources,
                sourcePreferences.disabledAnimeSources().changes(),
                sourcePreferences.enabledLanguages().changes(),
                sourcePreferences.showNsfwSource().changes(),
            ) { _, _, _, _ -> sourceService.available() }.collectLatest { available ->
                val chosen = available.firstOrNull { it.id == selectedPreference.get() } ?: available.firstOrNull()
                mutableState.update { it.copy(sources = available) }
                if (chosen == null) {
                    sourceJobs.values.forEach(Job::cancel)
                    mutableState.update {
                        it.copy(
                            selectedSource = null,
                            popular = SectionState(loading = false),
                            latest = SectionState(loading = false),
                        )
                    }
                } else {
                    selectSource(chosen.id)
                }
            }
        }
    }

    fun refresh() {
        feeds.forEach { load(it, true) }
        state.value.selectedSource?.let(::selectSource)
    }

    fun load(feed: CatalogFeed, refresh: Boolean = false) {
        jobs.remove(feed)?.cancel()
        jobs[feed] = screenModelScope.launch {
            catalog.observe(CatalogRequest(feed), refresh, state.value.offline).collect { section ->
                mutableState.update { it.copy(catalog = it.catalog + (feed to section)) }
            }
        }
    }

    fun selectSource(id: Long) {
        selectedPreference.set(id)
        mutableState.update {
            if (it.selectedSource == id) {
                it
            } else {
                it.copy(
                    selectedSource = id,
                    popular = SectionState(loading = false),
                    latest = SectionState(loading = false),
                )
            }
        }
        loadSource(false)
        loadSource(true)
    }

    fun loadSource(latest: Boolean) {
        sourceJobs.remove(latest)?.cancel()
        val id = state.value.selectedSource ?: return
        if (state.value.offline) {
            setSource(latest, SectionState(loading = false, error = "Modalità solo download"))
            return
        }
        sourceJobs[latest] = screenModelScope.launch {
            val previous = if (latest) state.value.latest else state.value.popular
            setSource(latest, previous.copy(loading = true, error = null))
            try {
                setSource(latest, SectionState(sourceService.sourceFeed(id, latest), loading = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setSource(
                    latest,
                    previous.copy(
                        loading = false,
                        stale = previous.data != null,
                        error =
                        e.message ?: "Fonte non disponibile",
                    ),
                )
            }
        }
    }

    private fun setSource(latest: Boolean, value: SectionState<List<Anime>>) {
        mutableState.update { if (latest) it.copy(latest = value) else it.copy(popular = value) }
    }

    data class State(
        val catalog: Map<CatalogFeed, SectionState<CatalogPage>> = emptyMap(),
        val resume: SectionState<List<LocalHomeItem>> = SectionState(),
        val updates: SectionState<List<LocalHomeItem>> = SectionState(),
        val sources: List<DiscoverySource> = emptyList(),
        val selectedSource: Long? = null,
        val popular: SectionState<List<Anime>> = SectionState(loading = false),
        val latest: SectionState<List<Anime>> = SectionState(loading = false),
        val offline: Boolean = false,
    ) {
        val catalogueOutage: String?
            get() = catalog.values.firstOrNull {
                it.failureReason == tachiyomi.domain.discovery.CatalogFailureReason.SERVICE_UNAVAILABLE
            }?.error
    }

    companion object {
        val feeds =
            listOf(CatalogFeed.TRENDING, CatalogFeed.SEASON, CatalogFeed.TOP, CatalogFeed.NEXT_SEASON, CatalogFeed.WEEK)
    }
}
