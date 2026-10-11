package eu.kanade.tachiyomi.ui.discovery

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.data.discovery.mergeHomeCards
import tachiyomi.domain.discovery.SourceHomeGroupAccess
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.search.ExtensionSearchAdapter
import tachiyomi.domain.search.SearchAssistance
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchPage
import tachiyomi.domain.search.SearchSession
import tachiyomi.domain.search.TitleSearch
import tachiyomi.domain.search.runtimeSearchTitle
import tachiyomi.domain.search.searchAliases
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class SourceHomeListScreenModel(
    homeKey: String,
    sectionId: String,
    private val services: ExtensionHomeServices = Injekt.get(),
    private var date: String? = null,
) : StateScreenModel<SourceHomeListScreenModel.State>(State()) {
    private val titleSearch: TitleSearch = Injekt.get()
    private var searchSession: SearchSession? = null
    private var assistanceJob: Job? = null
    private var exact = false
    private val repository = services.merged
    private val accessFlow = services.observeGroup(homeKey)
    private var job: Job? = null
    private var nextPage = 1
    private val initialCategory = sectionId.takeIf { it.startsWith("category:") }
    private val sectionId = if (initialCategory != null) SourceHomeRequest.SEARCH else sectionId
    private var initializedCategory = false

    init {
        screenModelScope.launch {
            accessFlow.collect { access ->
                job?.cancel()
                var selection = state.value.filters
                if (!initializedCategory && access.group != null) {
                    initializedCategory = true
                    selection = requireNotNull(access.group).providers.firstNotNullOfOrNull { provider ->
                        provider.categories.firstOrNull { it.id == initialCategory }?.browseValues
                    }.orEmpty()
                }
                val supported = access.group?.browseFilters.orEmpty()
                selection =
                    selection.filter { (name, values) -> supported.any { it.name == name && it.accepts(values) } }
                mutableState.value = State(access = access, query = state.value.query, filters = selection)
                nextPage = 1
                load(reset = true)
            }
        }
    }

    fun search(query: String, immediate: Boolean = false) {
        if (query.trim() == state.value.query && !immediate) return
        exact = false
        job?.cancel()
        mutableState.update { it.copy(query = query.trim(), hasNext = true, error = null) }
        nextPage = 1
        load(reset = true, debounce = !immediate)
    }

    fun searchExactly() {
        exact = !exact
        load(reset = true)
    }

    fun applyFilters(values: Map<String, List<String>>) {
        val controls = state.value.access.group?.browseFilters.orEmpty()
        val next = values.filter { (name, value) ->
            controls.any {
                it.name == name && it.accepts(value) && value != it.defaults
            }
        }
        if (next == state.value.filters) return
        job?.cancel()
        mutableState.value = State(access = state.value.access, query = state.value.query, filters = next)
        nextPage = 1
        load(reset = true)
    }

    fun selectDate(value: String?) {
        if (date == value) return
        date = value
        job?.cancel()
        mutableState.value =
            State(access = state.value.access, query = state.value.query, filters = state.value.filters)
        nextPage = 1
        load(reset = true)
    }

    suspend fun surprise(episode: Boolean): Anime? {
        val access = state.value.access
        val group = access.group ?: return null
        if (access.offline || if (episode) !group.hasRandomEpisode else !group.hasRandom) return null
        val request = SourceHomeRequest(
            if (episode) SourceHomeRequest.RANDOM_EPISODE else SourceHomeRequest.RANDOM,
        )
        return repository.observe(access, request, refresh = true)
            .first { it.data != null || it.error != null }
            .data?.items?.firstOrNull()
    }

    fun load(reset: Boolean = false, debounce: Boolean = false) {
        val current = state.value
        if (current.access.loading || current.access.group == null || current.access.offline) return
        if (!reset && (job?.isActive == true || !current.hasNext)) return
        job?.cancel()
        val page = if (reset) 1 else nextPage
        val previous = if (reset) emptyList() else current.items
        mutableState.update { it.copy(loading = true, error = null) }
        job = screenModelScope.launch {
            if (debounce) delay(350)
            if (current.query.isNotBlank()) {
                loadSearch(current, page, previous, reset)
                return@launch
            }
            assistanceJob?.cancel()
            searchSession = null
            mutableState.update { it.copy(assistance = SearchAssistance()) }
            repository.observe(
                current.access,
                SourceHomeRequest(sectionId, page, current.query, date, current.filters, browse = true),
                refresh = reset && current.items.isNotEmpty(),
            ).collect { value ->
                val result = value.data
                mutableState.update {
                    it.copy(
                        items = if (result != null) {
                            mergeHomeCards(previous + result.items)
                        } else {
                            it.items
                        },
                        title = result?.title ?: it.title,
                        loading = value.loading,
                        error = value.error,
                        stale = value.stale,
                        hasNext = result?.hasNextPage ?: it.hasNext,
                    )
                }
                if (!value.loading && value.error == null && result != null) nextPage = page + 1
            }
        }
    }

    private suspend fun loadSearch(current: State, page: Int, previous: List<Anime>, reset: Boolean) {
        if (reset || searchSession == null) {
            assistanceJob?.cancel()
            searchSession = titleSearch.session(current.query, SearchMedium.VIDEO, exact)
            val session = requireNotNull(searchSession)
            mutableState.update { it.copy(assistance = session.assistance.value) }
            assistanceJob = screenModelScope.launch {
                session.assistance.collect { value ->
                    mutableState.update { if (searchSession === session) it.copy(assistance = value) else it }
                }
            }
        }
        val session = requireNotNull(searchSession)
        val eligible = current.access.providers.filter { provider ->
            provider.source?.let { source ->
                source.search != null &&
                    current.filters.all { (name, values) ->
                        source.browseFilters.any { it.name == name && it.accepts(values) }
                    }
            } == true
        }
        val responses = withContext(Dispatchers.IO) {
            coroutineScope {
                val completed = Channel<Result<SearchPage<Anime>>>(eligible.size.coerceAtLeast(1))
                eligible.forEach { provider ->
                    launch {
                        val result = try {
                            val result = session.search(homeAdapter(provider, current, reset), page)
                            Result.success(result)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                        completed.send(result)
                    }
                }
                val received = mutableListOf<Result<SearchPage<Anime>>>()
                repeat(eligible.size) {
                    received += completed.receive()
                    val available = received.mapNotNull { it.getOrNull() }
                    if (available.any { it.items.isNotEmpty() }) {
                        // A slow source must not hold back its peers. Keep received rows in place.
                        mutableState.update { state ->
                            if (searchSession === session &&
                                state.query == current.query &&
                                state.filters == current.filters &&
                                state.access == current.access
                            ) {
                                state.copy(items = mergeHomeCards(previous + available.flatMap { it.items }))
                            } else {
                                state
                            }
                        }
                    }
                }
                received
            }
        }
        val successful = responses.mapNotNull { it.getOrNull() }
        mutableState.update { state ->
            if (searchSession !== session ||
                state.query != current.query ||
                state.filters != current.filters ||
                state.access != current.access
            ) {
                return@update state
            }
            state.copy(
                items = if (successful.isNotEmpty()) {
                    mergeHomeCards(previous + successful.flatMap { it.items })
                } else {
                    if (reset) emptyList() else state.items
                },
                loading = false,
                hasNext = successful.any { it.hasNextPage },
                stale = false,
                error = responses.firstNotNullOfOrNull { it.exceptionOrNull()?.message },
            )
        }
        if (successful.isNotEmpty()) nextPage = page + 1
    }

    private fun homeAdapter(
        provider: tachiyomi.domain.discovery.SourceHomeAccess,
        current: State,
        reset: Boolean,
    ) = object : ExtensionSearchAdapter<Anime> {
        private val home = requireNotNull(provider.source)
        override val key = home.key
        override fun identity(item: Anime) = item.source.toString() + ":" + item.url
        override fun title(item: Anime) = runtimeSearchTitle(
            item.source,
            item.url,
            item.title,
            SearchMedium.VIDEO,
            searchAliases(item.memo),
        )
        override suspend fun fetch(page: Int, query: String): SearchPage<Anime> {
            val value = services.forHome(home.key).repository.observe(
                provider,
                SourceHomeRequest(sectionId, page, query, date, current.filters, browse = true),
                refresh = reset && current.items.isNotEmpty(),
            ).first { !it.loading }
            if (value.error != null) throw IOException(value.error)
            val data = value.data ?: throw IOException("Missing search response")
            return SearchPage(data.items, data.hasNextPage)
        }
    }

    data class State(
        val access: SourceHomeGroupAccess = SourceHomeGroupAccess(loading = true),
        val query: String = "",
        val assistance: SearchAssistance = SearchAssistance(),
        val filters: Map<String, List<String>> = emptyMap(),
        val title: String? = null,
        val items: List<Anime> = emptyList(),
        val loading: Boolean = false,
        val stale: Boolean = false,
        val error: String? = null,
        val hasNext: Boolean = true,
    )
}
