package eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.ui.browse.SourceSearchRunner
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.search.ExtensionSearchAdapter
import tachiyomi.domain.search.SearchAssistance
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchPage
import tachiyomi.domain.search.SearchSession
import tachiyomi.domain.search.TitleSearch
import tachiyomi.domain.search.searchTitle
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class AnimeSearchScreenModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val extensionManager: AnimeExtensionManager = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<AnimeSearchScreenModel.State>(initialState) {

    private val titleSearch: TitleSearch = Injekt.get()
    private var searchSession: SearchSession? = null
    private var assistanceJob: Job? = null
    private var debounceJob: Job? = null
    private var exactSearch = false

    private val searches = SourceSearchRunner<AnimeSource>(ioCoroutineScope)

    private val enabledLanguages = sourcePreferences.enabledLanguages().get()
    private val disabledSources = sourcePreferences.disabledAnimeSources().get()
    protected val pinnedSources = sourcePreferences.pinnedAnimeSources().get()

    private var lastQuery: String? = null
    private var lastSourceFilter: AnimeSourceFilter? = null

    protected var extensionFilter: String? = null

    private val sortComparator = { map: Map<AnimeSource, AnimeSearchItemResult> ->
        compareBy<AnimeSource>(
            { (map[it] as? AnimeSearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        screenModelScope.launch {
            preferences.globalSearchFilterState().changes().collectLatest { state ->
                mutableState.update { it.copy(onlyShowHasResults = state) }
            }
        }
    }

    @Composable
    fun getAnime(initialAnime: Anime): androidx.compose.runtime.State<Anime> {
        return produceState(initialValue = initialAnime, key1 = initialAnime.id) {
            getAnime.subscribe(initialAnime.url, initialAnime.source)
                .filterNotNull()
                .collectLatest { anime ->
                    value = anime
                }
        }
    }

    open fun getEnabledSources(): List<AnimeSource> {
        return sourceManager.getAll()
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabledSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    private fun getSelectedSources(): List<AnimeSource> {
        val enabledSources = getEnabledSources()

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        return extensionManager.installedExtensionsFlow.value
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filter { it in enabledSources }
    }

    fun updateSearchQuery(query: String?) {
        if (query == state.value.searchQuery) return
        searches.reset()
        assistanceJob?.cancel()
        debounceJob?.cancel()
        exactSearch = false
        lastQuery = null
        mutableState.update { it.copy(searchQuery = query, assistance = SearchAssistance()) }
        debounceJob = screenModelScope.launch {
            delay(350)
            search()
        }
    }

    fun searchExactly() {
        exactSearch = !exactSearch
        lastQuery = null
        search()
    }

    fun chooseSuggestion(query: String) {
        updateSearchQuery(query)
        search()
    }

    fun setSourceFilter(filter: AnimeSourceFilter) {
        mutableState.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState().toggle()
    }

    fun search() {
        debounceJob?.cancel()
        val query = state.value.searchQuery
        val sourceFilter = state.value.sourceFilter

        if (query.isNullOrBlank()) {
            searches.reset()
            lastQuery = null
            lastSourceFilter = null
            updateItems(persistentMapOf())
            return
        }
        val sameQuery = this.lastQuery == query
        if (sameQuery && this.lastSourceFilter == sourceFilter) return

        this.lastQuery = query
        this.lastSourceFilter = sourceFilter

        searches.reset()
        if (!sameQuery || searchSession == null) {
            assistanceJob?.cancel()
            val session = titleSearch.session(query, SearchMedium.VIDEO, exactSearch)
            searchSession = session
            mutableState.update { it.copy(assistance = session.assistance.value) }
            assistanceJob = screenModelScope.launch {
                session.assistance.collect { value ->
                    mutableState.update { if (searchSession === session) it.copy(assistance = value) else it }
                }
            }
        }
        val sources = getSelectedSources()

        // Reuse previous results if possible
        if (sameQuery) {
            val existingResults = state.value.items
            updateItems(
                sources
                    .associateWith { existingResults[it] ?: AnimeSearchItemResult.Loading }
                    .toPersistentMap(),
            )
        } else {
            updateItems(
                sources
                    .associateWith { AnimeSearchItemResult.Loading }
                    .toPersistentMap(),
            )
        }

        sources.filter { state.value.items[it] is AnimeSearchItemResult.Loading }
            .forEach { fetch(it, query) }
    }

    fun retry(source: AnimeSource) {
        val query = lastQuery ?: return
        if (source !in getSelectedSources() || state.value.items[source] !is AnimeSearchItemResult.Error) return
        updateItem(source, AnimeSearchItemResult.Loading)
        fetch(source, query)
    }

    private fun fetch(source: AnimeSource, query: String) {
        val session = requireNotNull(searchSession)
        searches.submit(source, fetch = {
            val filters = source.getFilterList()
            val page = session.search(
                object : ExtensionSearchAdapter<SAnime> {
                    override val key = source.id.toString()
                    override fun identity(item: SAnime) = item.url
                    override fun title(item: SAnime) = item.searchTitle(source.id)
                    override suspend fun fetch(page: Int, query: String): SearchPage<SAnime> {
                        val response = source.getSearchAnime(page, query, filters)
                        return SearchPage(response.animes, response.hasNextPage)
                    }
                },
                1,
            )
            page.items.map { networkToLocalAnime.await(it.toDomainAnime(source.id)) }
        }) { result ->
            updateItem(source, result.fold({ AnimeSearchItemResult.Success(it) }, { AnimeSearchItemResult.Error(it) }))
        }
    }

    private fun updateItems(items: PersistentMap<AnimeSource, AnimeSearchItemResult>) {
        mutableState.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items))
                    .toPersistentMap(),
            )
        }
    }

    private fun updateItem(source: AnimeSource, result: AnimeSearchItemResult) {
        mutableState.update { current ->
            if (source !in current.items) return@update current
            val items = current.items.put(source, result)
            current.copy(items = items.toSortedMap(sortComparator(items)).toPersistentMap())
        }
    }

    @Immutable
    data class State(
        val fromSourceId: Long? = null,
        val searchQuery: String? = null,
        val assistance: SearchAssistance = SearchAssistance(),
        val sourceFilter: AnimeSourceFilter = AnimeSourceFilter.PinnedOnly,
        val onlyShowHasResults: Boolean = false,
        val items: PersistentMap<AnimeSource, AnimeSearchItemResult> = persistentMapOf(),
    ) {
        val progress: Int = items.count { it.value !is AnimeSearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }
}

enum class AnimeSourceFilter {
    All,
    PinnedOnly,
}

sealed interface AnimeSearchItemResult {
    data object Loading : AnimeSearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : AnimeSearchItemResult

    data class Success(
        val result: List<Anime>,
    ) : AnimeSearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || this !is Success || !isEmpty
    }
}
