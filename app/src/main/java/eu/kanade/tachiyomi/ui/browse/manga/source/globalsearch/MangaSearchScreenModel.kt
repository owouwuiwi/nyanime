package eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
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
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.search.ExtensionSearchAdapter
import tachiyomi.domain.search.SearchAssistance
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchPage
import tachiyomi.domain.search.SearchSession
import tachiyomi.domain.search.TitleSearch
import tachiyomi.domain.search.searchTitle
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class MangaSearchScreenModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val extensionManager: MangaExtensionManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
) : StateScreenModel<MangaSearchScreenModel.State>(initialState) {

    private val titleSearch: TitleSearch = Injekt.get()
    private var searchSession: SearchSession? = null
    private var assistanceJob: Job? = null
    private var debounceJob: Job? = null
    private var exactSearch = false

    private val searches = SourceSearchRunner<CatalogueSource>(ioCoroutineScope)

    private val enabledLanguages = sourcePreferences.enabledLanguages().get()
    private val disabledSources = sourcePreferences.disabledMangaSources().get()
    protected val pinnedSources = sourcePreferences.pinnedMangaSources().get()

    private var lastQuery: String? = null
    private var lastSourceFilter: MangaSourceFilter? = null

    protected var extensionFilter: String? = null

    private val sortComparator = { map: Map<CatalogueSource, MangaSearchItemResult> ->
        compareBy<CatalogueSource>(
            { (map[it] as? MangaSearchItemResult.Success)?.isEmpty ?: true },
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
        screenModelScope.launch {
            uiPreferences.showMangaInOtherLanguages().changes().collectLatest {
                lastQuery = null
                search()
            }
        }
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga, key1 = initialManga.id) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    open fun getEnabledSources(): List<CatalogueSource> {
        return sourceManager.getCatalogueSources()
            .filter {
                it.lang in enabledLanguages &&
                    "${it.id}" !in disabledSources &&
                    (uiPreferences.showMangaInOtherLanguages().get() || it.lang == "it")
            }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    private fun getSelectedSources(): List<CatalogueSource> {
        val enabledSources = getEnabledSources()

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        return extensionManager.installedExtensionsFlow.value
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filterIsInstance<CatalogueSource>()
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

    fun setSourceFilter(filter: MangaSourceFilter) {
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
            val session = titleSearch.session(query, SearchMedium.MANGA, exactSearch)
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
                    .associateWith { existingResults[it] ?: MangaSearchItemResult.Loading }
                    .toPersistentMap(),
            )
        } else {
            updateItems(
                sources
                    .associateWith { MangaSearchItemResult.Loading }
                    .toPersistentMap(),
            )
        }
        sources.filter { state.value.items[it] is MangaSearchItemResult.Loading }
            .forEach { fetch(it, query) }
    }

    fun retry(source: CatalogueSource) {
        val query = lastQuery ?: return
        if (source !in getSelectedSources() || state.value.items[source] !is MangaSearchItemResult.Error) return
        updateItem(source, MangaSearchItemResult.Loading)
        fetch(source, query)
    }

    private fun fetch(source: CatalogueSource, query: String) {
        val session = requireNotNull(searchSession)
        searches.submit(source, fetch = {
            val filters = source.getFilterList()
            val page = session.search(
                object : ExtensionSearchAdapter<SManga> {
                    override val key = source.id.toString()
                    override fun identity(item: SManga) = item.url
                    override fun title(item: SManga) = item.searchTitle(source.id)
                    override suspend fun fetch(page: Int, query: String): SearchPage<SManga> {
                        val response = source.getSearchManga(page, query, filters)
                        return SearchPage(response.mangas, response.hasNextPage)
                    }
                },
                1,
            )
            page.items.map { networkToLocalManga.await(it.toDomainManga(source.id)) }
        }) { result ->
            updateItem(source, result.fold({ MangaSearchItemResult.Success(it) }, { MangaSearchItemResult.Error(it) }))
        }
    }

    private fun updateItems(items: PersistentMap<CatalogueSource, MangaSearchItemResult>) {
        mutableState.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items))
                    .toPersistentMap(),
            )
        }
    }

    private fun updateItem(source: CatalogueSource, result: MangaSearchItemResult) {
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
        val sourceFilter: MangaSourceFilter = MangaSourceFilter.PinnedOnly,
        val onlyShowHasResults: Boolean = false,
        val items: PersistentMap<CatalogueSource, MangaSearchItemResult> = persistentMapOf(),
    ) {
        val progress: Int = items.count { it.value !is MangaSearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }
}

enum class MangaSourceFilter {
    All,
    PinnedOnly,
}

sealed interface MangaSearchItemResult {
    data object Loading : MangaSearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : MangaSearchItemResult

    data class Success(
        val result: List<Manga>,
    ) : MangaSearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || this !is Success || !isEmpty
    }
}
