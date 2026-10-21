package eu.kanade.tachiyomi.ui.search

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.discovery.HomeCategories
import eu.kanade.presentation.discovery.HomeCategory
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.discovery.DiscoverySourceService
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeRegistry
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeServices
import eu.kanade.tachiyomi.data.discovery.MangaHomeCategories
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomePresentation
import eu.kanade.tachiyomi.data.discovery.MangaHomeRegistry
import eu.kanade.tachiyomi.data.discovery.MangaHomeService
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.ui.browse.SourceSearchRunner
import eu.kanade.tachiyomi.ui.discovery.DiscoveryTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.search.ExtensionSearchAdapter
import tachiyomi.domain.search.SearchAssistance
import tachiyomi.domain.search.SearchMatchKind
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchPage
import tachiyomi.domain.search.SearchRequestBudget
import tachiyomi.domain.search.SearchResultRanker
import tachiyomi.domain.search.SearchSession
import tachiyomi.domain.search.TitleSearch
import tachiyomi.domain.search.mergeSearchAliases
import tachiyomi.domain.search.runtimeSearchTitle
import tachiyomi.domain.search.searchAliases
import tachiyomi.domain.search.searchTitle
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

data class UnifiedSearchProgress(
    val loading: Boolean = false,
    val nextPage: Int = 1,
    val more: Boolean = false,
    val error: String? = null,
)

data class UnifiedSearchState(
    val open: Boolean = false,
    val initializing: Boolean = true,
    val input: String = "",
    val query: String = "",
    val category: String? = null,
    val categories: List<HomeCategory> = emptyList(),
    val routes: List<UnifiedSearchRoute> = emptyList(),
    val genres: List<UnifiedSearchGenre> = emptyList(),
    val selectedGenres: Set<String> = emptySet(),
    val cards: List<UnifiedSearchCard> = emptyList(),
    val includeOtherResults: Boolean = false,
    val progress: Map<String, UnifiedSearchProgress> = emptyMap(),
    val assistance: Map<SearchMedium, SearchAssistance> = emptyMap(),
    val exact: Boolean = false,
    val offline: Boolean = false,
    val removedFilters: Int = 0,
    val scrollRevision: Int = 0,
    val panel: UnifiedSearchPanel? = null,
) {
    val exploring get() = query.isBlank() && selectedGenres.isEmpty()
    val results get() = UnifiedSearchCards.results(cards, includeOtherResults || query.isBlank())
    val broadResultsVisible get() = query.isNotBlank() &&
        cards.any { it.relevance.kind == SearchMatchKind.OTHER } &&
        !results.narrowed
    val loading get() = (open && initializing) || progress.values.any { it.loading }
    val more get() = progress.values.any { it.more }
    val failures get() = progress.values.count { it.error != null }
    val scopedRoutes get() = routes.filter { category == null || it.category == category }
    val selected get() = genres.filter { it.key in selectedGenres }
    val eligible get() = scopedRoutes.filter { UnifiedSearchFilters.request(it, selected) != null }
}

enum class UnifiedSearchPanel { CATEGORIES, FILTERS, ASSISTANCE, SETTINGS }

/** One presentation session, distinct media engines and one shared external allowance. */
class UnifiedSearchScreenModel : StateScreenModel<UnifiedSearchState>(UnifiedSearchState()) {
    private val videoRegistry: ExtensionHomeRegistry = Injekt.get()
    private val mangaRegistry: MangaHomeRegistry = Injekt.get()
    private val videoHomes: ExtensionHomeServices = Injekt.get()
    private val mangaHomes: MangaHomeService = Injekt.get()
    private val videos: AnimeSourceManager = Injekt.get()
    private val mangas: MangaSourceManager = Injekt.get()
    private val visibility: DiscoverySourceService = Injekt.get()
    private val mangaExtensions: MangaExtensionManager = Injekt.get()
    private val preferences: SourcePreferences = Injekt.get()
    private val uiPreferences: UiPreferences = Injekt.get()
    private val base: BasePreferences = Injekt.get()
    private val videoPrivacy: GetAnimeIncognitoState = Injekt.get()
    private val mangaPrivacy: GetMangaIncognitoState = Injekt.get()
    private val toVideo: NetworkToLocalAnime = Injekt.get()
    private val toManga: NetworkToLocalManga = Injekt.get()
    private val engine: TitleSearch = Injekt.get()
    private val sharedRequests = kotlinx.coroutines.sync.Semaphore(5)
    private val tv: eu.kanade.tachiyomi.data.tv.TvServices = Injekt.get()
    private val tvSearch = eu.kanade.tachiyomi.data.tv.TvSearchSession(tv.repository, sharedRequests)
    private val tvJobs = mutableMapOf<String, Job>()
    private val tvCursors = mutableMapOf<Long, String>()
    private val runner = SourceSearchRunner<String>(ioCoroutineScope, concurrency = 5, sharedRequests = sharedRequests)
    private var debounce: Job? = null
    private var logicalQuery: String? = null
    private var budget = SearchRequestBudget(3)
    private val sessions = mutableMapOf<Triple<SearchMedium, Boolean, Boolean>, SearchSession>()
    private val assistanceJobs = mutableListOf<Job>()
    private val received = linkedMapOf<String, List<UnifiedSearchEntry>>()
    private val ranker = SearchResultRanker()
    private var ranking: Job? = null
    private var revision = 0

    override fun onDispose() {
        revision++
        debounce?.cancel()
        ranking?.cancel()
        runner.reset()
        tvJobs.values.forEach { it.cancel() }
        tvJobs.clear()
        assistanceJobs.forEach { it.cancel() }
        super.onDispose()
    }

    init {
        screenModelScope.launch {
            merge(
                videoRegistry.observe().map { Unit },
                tv.registry.state.map { Unit },
                tv.initialized.map { Unit },
                tv.personal.changes.map { Unit },
                mangaRegistry.observe().map { Unit },
                uiPreferences.homeCategoryOrder().changes().map { Unit },
                base.downloadedOnly().changes().map { Unit },
                preferences.pinnedAnimeSources().changes().map { Unit },
                preferences.pinnedMangaSources().changes().map { Unit },
            ).collectLatest { rebuildRoutes() }
        }
        screenModelScope.launch {
            merge(
                preferences.tolerantSearch().changes().map { Unit },
                preferences.onlineSearchAssistance().changes().map { Unit },
                base.incognitoMode().changes().map { Unit },
                preferences.incognitoAnimeExtensions().changes().map { Unit },
                preferences.incognitoMangaExtensions().changes().map { Unit },
            ).collectLatest {
                sessions.clear()
                if (state.value.open) submit()
            }
        }
        screenModelScope.launch {
            UnifiedSearchExploreCache.snapshots.collect { if (state.value.exploring) showExplore() }
        }
        screenModelScope.launch {
            merge(
                preferences.hideInAnimeLibraryItems().changes().map { Unit },
                preferences.hideInMangaLibraryItems().changes().map { Unit },
            ).collect {
                if (state.value.exploring) showExplore() else publishCards()
            }
        }
    }

    private suspend fun rebuildRoutes() {
        val (videoListing, mangaListing) = withContext(Dispatchers.IO) {
            videoRegistry.current() to mangaRegistry.current()
        }
        val videoSources = videos.getAll().filter(visibility::isEnabled)
        val mangaSources = mangas.getCatalogueSources().filter { source ->
            source.id.toString() !in preferences.disabledMangaSources().get() &&
                (
                    source.lang in preferences.enabledLanguages().get() ||
                        (uiPreferences.showMangaInOtherLanguages().get() && source.lang != "it")
                    ) &&
                (uiPreferences.showMangaInOtherLanguages().get() || source.lang == "it") &&
                (
                    preferences.showNsfwSource().get() ||
                        mangaExtensions.installedExtensionsFlow.value.none { extension ->
                            extension.isNsfw && extension.sources.any { it.id == source.id }
                        }
                    )
        }
        val videoRoutes = videoListing.homes.filter { it.search != null }.map { home ->
            UnifiedSearchRoute(
                "video:${home.key}",
                SearchMedium.VIDEO,
                home.id,
                home.sourceName,
                home.language,
                home.homeId,
                home.title,
                home,
            )
        } +
            videoSources.filter { source ->
                videoListing.homes.none {
                    it.id == source.id && it.search != null
                }
            }.map { source ->
                UnifiedSearchRoute(
                    "video:${source.id}",
                    SearchMedium.VIDEO,
                    source.id,
                    source.name,
                    source.lang,
                    DEFAULT_VIDEO,
                    "",
                )
            }
        val mangaRoutes = mangaListing.homes.filter { it.search != null }.map { home ->
            UnifiedSearchRoute(
                "manga:${home.key}",
                SearchMedium.MANGA,
                home.id,
                home.sourceName,
                home.language,
                MangaHomeCategories.key(home.homeId),
                home.title,
                home,
            )
        } +
            mangaSources.filter { source ->
                mangaListing.homes.none {
                    it.id == source.id && it.search != null
                }
            }.map { source ->
                UnifiedSearchRoute(
                    "manga:${source.id}",
                    SearchMedium.MANGA,
                    source.id,
                    source.name,
                    source.lang,
                    DiscoveryTab.MANGA_CATEGORY,
                    "",
                )
            }
        val tvRoutes = tv.registry.state.value.values.map { registration ->
            val source = registration.source
            UnifiedSearchRoute(
                "tv:${source.id}",
                SearchMedium.VIDEO,
                source.id,
                source.name,
                source.language,
                DiscoveryTab.TV_CATEGORY,
                "TV",
                tv = true,
            )
        }
        val choices = HomeCategories.choices(
            videoListing.groups +
                MangaHomeCategories.groups(mangaListing) +
                (
                    if (tvRoutes.isNotEmpty()) {
                        listOf(
                            tachiyomi.domain.discovery.SourceHomeGroup(DiscoveryTab.TV_CATEGORY, "TV", emptyList()),
                        )
                    } else {
                        emptyList()
                    }
                    ),
            uiPreferences.homeCategoryOrder().get(),
            nativeFallback = videoListing.nativeFallback,
        ).map { if (it.id == null) it.copy(id = DEFAULT_VIDEO) else it }
        val routes = (videoRoutes + mangaRoutes + tvRoutes).sortedBy { route ->
            val pinned = if (route.medium == SearchMedium.VIDEO) {
                preferences.pinnedAnimeSources().get()
            } else {
                preferences.pinnedMangaSources().get()
            }
            if (route.source.toString() in pinned) 0 else 1
        }
        val initializing = videoListing.loading || mangaListing.loading || !tv.initialized.value
        val selection = UnifiedSearchSelection.resolve(
            state.value.category,
            state.value.selectedGenres,
            choices.map { it.id }.toSet(),
            routes,
            initializing,
        )
        val changed = routes != state.value.routes ||
            state.value.offline != base.downloadedOnly().get() ||
            state.value.initializing != initializing ||
            state.value.category != selection.category ||
            state.value.selectedGenres != selection.selected
        mutableState.update {
            it.copy(
                initializing = initializing,
                routes = routes,
                categories = choices,
                category = selection.category,
                genres = selection.genres,
                selectedGenres = selection.selected,
                offline = base.downloadedOnly().get(),
                removedFilters = it.removedFilters + (it.selectedGenres.size - selection.selected.size),
            )
        }
        if (state.value.open && changed) {
            submit()
        } else if (state.value.exploring) {
            showExplore()
        }
    }

    fun open() {
        runner.reset()
        tvJobs.values.forEach { it.cancel() }
        tvJobs.clear()
        logicalQuery = null
        val remember = preferences.rememberUnifiedSearch().get() && !base.incognitoMode().get()
        val input = if (remember) preferences.lastUnifiedSearchQuery().get() else ""
        val selection = UnifiedSearchSelection.resolve(
            if (remember) preferences.lastUnifiedSearchCategory().get() else null,
            if (remember) preferences.lastUnifiedSearchGenres().get() else emptySet(),
            state.value.categories.map { it.id }.toSet(),
            state.value.routes,
            state.value.initializing,
        )
        mutableState.update {
            it.copy(
                open = true,
                input = input,
                query = "",
                category = selection.category,
                selectedGenres = selection.selected,
                genres = selection.genres,
                exact = false,
                includeOtherResults = false,
                panel = null,
                cards = emptyList(),
                progress = emptyMap(),
                assistance = emptyMap(),
                scrollRevision = it.scrollRevision + 1,
            )
        }
        submit()
    }

    fun close() {
        revision++
        ranking?.cancel()
        debounce?.cancel()
        runner.reset()
        tvJobs.values.forEach { it.cancel() }
        tvJobs.clear()
        assistanceJobs.forEach { it.cancel() }
        val privateScope = state.value.scopedRoutes.any { route ->
            if (route.tv) {
                false
            } else if (route.medium == SearchMedium.VIDEO) {
                videoPrivacy.await(route.source)
            } else {
                mangaPrivacy.await(route.source)
            }
        }
        if (preferences.rememberUnifiedSearch().get() && !base.incognitoMode().get() && !privateScope) {
            preferences.lastUnifiedSearchQuery().set(state.value.input)
            preferences.lastUnifiedSearchCategory().set(state.value.category.orEmpty())
            preferences.lastUnifiedSearchGenres().set(state.value.selectedGenres)
        } else {
            preferences.lastUnifiedSearchQuery().delete()
            preferences.lastUnifiedSearchCategory().delete()
            preferences.lastUnifiedSearchGenres().delete()
        }
        mutableState.update {
            it.copy(
                open = false,
                panel = null,
                progress = it.progress.mapValues {
                        (
                            _,
                            p,
                        ),
                    ->
                    p.copy(loading = false)
                },
            )
        }
    }

    fun edit(value: String) {
        revision++
        ranking?.cancel()
        assistanceJobs.forEach { it.cancel() }
        mutableState.update { it.copy(input = value.take(256)) }
        debounce?.cancel()
        runner.reset()
        tvJobs.values.forEach { it.cancel() }
        tvJobs.clear()
        mutableState.update { it.copy(progress = it.progress.mapValues { (_, p) -> p.copy(loading = false) }) }
        if (preferences.liveUnifiedSearch().get()) {
            debounce = screenModelScope.launch {
                delay(350)
                submit()
            }
        }
    }

    fun selectCategory(category: String?) {
        val valid = category?.takeIf { key -> state.value.categories.any { it.id == key } }
        val genres = UnifiedSearchFilters.genres(state.value.routes.filter { valid == null || it.category == valid })
        val kept = state.value.selectedGenres.intersect(genres.map { it.key }.toSet())
        mutableState.update {
            it.copy(
                category = valid,
                genres = genres,
                selectedGenres = kept,
                removedFilters = it.removedFilters + (it.selectedGenres.size - kept.size),
            )
        }
        submit()
    }

    fun toggleGenre(key: String) {
        if (state.value.genres.none { it.key == key }) return
        mutableState.update {
            it.copy(
                selectedGenres = if (key in it.selectedGenres) it.selectedGenres - key else it.selectedGenres + key,
            )
        }
        submit()
    }

    fun clearGenres() {
        mutableState.update { it.copy(selectedGenres = emptySet()) }
        submit()
    }
    fun showPanel(panel: UnifiedSearchPanel?) {
        mutableState.update { it.copy(panel = panel) }
    }
    fun toggleExact() {
        mutableState.update { it.copy(exact = !it.exact) }
        submit()
    }
    fun useSuggestion(value: String) {
        mutableState.update { it.copy(input = value, exact = false) }
        submit()
    }

    fun showOtherResults() {
        mutableState.update { it.copy(includeOtherResults = true) }
    }

    /** A late confident answer must not remove broad results the user has started reading. */
    fun preserveBroadResults() {
        if (state.value.broadResultsVisible) {
            showOtherResults()
        }
    }

    fun submit() {
        debounce?.cancel()
        ranking?.cancel()
        runner.reset()
        tvJobs.values.forEach { it.cancel() }
        tvJobs.clear()
        assistanceJobs.forEach { it.cancel() }
        assistanceJobs.clear()
        revision++
        val query = state.value.input.trim()
        if (logicalQuery != query) {
            logicalQuery = query
            budget = SearchRequestBudget(3)
            sessions.clear()
        }
        received.clear()
        mutableState.update {
            it.copy(
                query = query,
                cards = emptyList(),
                includeOtherResults = false,
                progress = emptyMap(),
                assistance = emptyMap(),
                scrollRevision = it.scrollRevision + 1,
            )
        }
        if (state.value.exploring || state.value.offline) {
            showExplore()
            return
        }
        if (state.value.initializing) return
        val current = state.value
        val assistanceTicket = revision
        val eligible = current.eligible
        eligible.filterNot { it.tv }.map { it.medium }.distinct().forEach { medium ->
            val privateSource = privateSource(medium, current)
            val session = sessions.getOrPut(Triple(medium, current.exact, privateSource != null)) {
                engine.session(query, medium, current.exact, sourceId = privateSource, catalogBudget = budget)
            }
            assistanceJobs += screenModelScope.launch {
                session.assistance.collect { assistance ->
                    if (assistanceTicket == revision &&
                        state.value.query == query &&
                        state.value.exact == current.exact
                    ) {
                        mutableState.update {
                            it.copy(assistance = it.assistance + (medium to assistance))
                        }
                    }
                }
            }
        }
        mutableState.update {
            it.copy(
                progress = eligible.associate { route ->
                    route.key to UnifiedSearchProgress(loading = true)
                },
            )
        }
        eligible.filterNot { it.tv }.forEach { fetch(it, 1, current) }
        tvCursors.clear()
        fetchTv(current, eligible.filter { it.tv }, append = false)
    }

    fun loadMore() {
        val current = state.value
        if (!current.initializing && !current.offline && !current.exploring && current.input.trim() == current.query) {
            val live = current.eligible.filter {
                it.tv &&
                    current.progress[it.key]?.let { p -> p.more && !p.loading && p.error == null } == true
            }
            if (live.isNotEmpty()) {
                mutableState.update {
                    it.copy(
                        progress = it.progress.mapValues { (key, p) ->
                            if (live.any {
                                    it.key ==
                                        key
                                }
                            ) {
                                p.copy(loading = true)
                            } else {
                                p
                            }
                        },
                    )
                }
                fetchTv(current, live, append = true)
            }
        }
        if (current.initializing ||
            current.offline ||
            current.exploring ||
            current.input.trim() != current.query
        ) {
            return
        }
        current.eligible.filterNot { it.tv }.forEach { route ->
            val progress = current.progress[route.key] ?: return@forEach
            if (progress.more && !progress.loading && progress.error == null) {
                mutableState.update { it.copy(progress = it.progress + (route.key to progress.copy(loading = true))) }
                fetch(route, progress.nextPage, current)
            }
        }
    }

    fun retryFailures() {
        val current = state.value
        fetchTv(current, current.eligible.filter { it.tv && current.progress[it.key]?.error != null }, append = true)
        current.eligible.filterNot { it.tv }.forEach { route ->
            val progress = current.progress[route.key] ?: return@forEach
            if (progress.error != null && !progress.loading) {
                mutableState.update {
                    it.copy(
                        progress = it.progress +
                            (
                                route.key to progress.copy(
                                    loading = true,
                                    error = null,
                                )
                                ),
                    )
                }
                fetch(route, progress.nextPage, current)
            }
        }
    }

    private fun fetchTv(snapshot: UnifiedSearchState, routes: List<UnifiedSearchRoute>, append: Boolean) {
        if (!snapshot.open || snapshot.offline || routes.isEmpty()) return
        val ticket = revision
        val requests = routes.associate { route ->
            val selection = tv.personal.read().sources[route.source.toString()]
                ?: eu.kanade.tachiyomi.data.tv.TvSelection(
                    tv.registry.state.value[route.source]?.source?.capabilities?.initialCountry,
                )
            route.source to nyanime.tv.api.TvCatalogueRequest(
                selection.country,
                selection.language,
                selection.category,
                cursor = if (append) tvCursors[route.source] else null,
            )
        }
        val key = routes.joinToString { it.key }
        tvJobs.remove(key)?.cancel()
        tvJobs[key] = screenModelScope.launch {
            tvSearch.search(snapshot.query, requests, tv.adultContent.value).collect { pages ->
                if (ticket != revision || !state.value.open) return@collect
                pages.forEach { (id, page) ->
                    val route = routes.firstOrNull { it.source == id } ?: return@forEach
                    val previous = if (append) received[route.key].orEmpty() else emptyList()
                    received[route.key] = (previous + page.channels.map(UnifiedSearchEntry::live)).distinctBy { it.key }
                    val cursor = page.nextCursor?.takeIf { it != requests[id]?.cursor }
                    if (!page.error) {
                        if (cursor == null) tvCursors.remove(id) else tvCursors[id] = cursor
                    }
                    mutableState.update {
                        it.copy(
                            progress = it.progress +
                                (
                                    route.key to UnifiedSearchProgress(
                                        loading = page.loading,
                                        more = cursor != null,
                                        error = if (page.error) "TV" else null,
                                    )
                                    ),
                        )
                    }
                }
                if (state.value.exploring) {
                    val old = state.value.cards.flatMap { it.variants }.filter { it.tvChannel == null }
                    val live = state.value.routes.filter { it.tv }.flatMap { received[it.key].orEmpty() }
                    mutableState.update { it.copy(cards = UnifiedSearchCards.merge(old + live, it.cards)) }
                } else {
                    publishCards()
                }
            }
        }
    }

    private fun isPrivate(medium: SearchMedium, source: Long) = if (medium == SearchMedium.VIDEO) {
        videoPrivacy.await(source)
    } else {
        mangaPrivacy.await(source)
    }

    private fun privateSource(medium: SearchMedium, snapshot: UnifiedSearchState) = snapshot.eligible.firstOrNull {
        !it.tv && it.medium == medium && isPrivate(it.medium, it.source)
    }?.source

    private fun showExplore() {
        val current = state.value
        if (!current.exploring || current.offline) {
            if (current.offline) {
                mutableState.update {
                    it.copy(cards = emptyList())
                }
            }
            return
        }
        val public = UnifiedSearchExploreCache.snapshots.value.filter { entry ->
            current.routes.any { it.source == entry.source && it.medium == entry.medium } &&
                (current.category == null || current.category == entry.category) &&
                !isPrivate(entry.medium, entry.source) &&
                visibleEntry(entry)
        }.map { entry ->
            entry.copy(
                targets = entry.targets.filter { target ->
                    current.routes.any {
                        it.source == target.source && it.medium == target.medium
                    }
                },
            )
        }
        val live = current.routes.filter { it.tv && (current.category == null || it.category == current.category) }
        mutableState.update {
            it.copy(cards = UnifiedSearchCards.merge(public + live.flatMap { received[it.key].orEmpty() }))
        }
        if (current.open && live.isNotEmpty()) fetchTv(current, live, append = false)
    }

    private fun visibleEntry(entry: UnifiedSearchEntry) = when (entry.medium) {
        SearchMedium.VIDEO -> !preferences.hideInAnimeLibraryItems().get() || entry.anime?.favorite != true
        SearchMedium.MANGA -> !preferences.hideInMangaLibraryItems().get() || entry.manga?.favorite != true
    }

    private fun publishCards() {
        ranking?.cancel()
        val ticket = revision
        val snapshot = state.value
        val entries = snapshot.routes.flatMap { received[it.key].orEmpty() }.filter(::visibleEntry)
        ranking = screenModelScope.launch {
            val cards = withContext(Dispatchers.Default) {
                UnifiedSearchCards.ranked(
                    snapshot.query,
                    UnifiedSearchCards.merge(entries, snapshot.cards),
                    entries,
                    ranker,
                )
            }
            if (ticket == revision && state.value.open && !state.value.exploring) {
                mutableState.update { it.copy(cards = cards) }
            }
        }
    }

    private fun fetch(route: UnifiedSearchRoute, page: Int, snapshot: UnifiedSearchState) {
        val ticket = revision
        val selected = snapshot.selected
        val request = UnifiedSearchFilters.request(route, selected) ?: return
        val session = sessions.getValue(
            Triple(
                route.medium,
                snapshot.exact,
                privateSource(route.medium, snapshot) != null,
            ),
        )
        runner.submit(route.key, fetch = {
            val adapter = adapter(route, request, snapshot.selectedGenres.sorted().joinToString("|"))
            if (snapshot.query.isBlank()) adapter.fetch(page, "") else session.search(adapter, page)
        }) { result ->
            screenModelScope.launch {
                if (ticket != revision || !state.value.open) return@launch
                val progress = state.value.progress[route.key] ?: return@launch
                result.fold(
                    onSuccess = { response ->
                        val previous = if (page == 1) emptyList() else received[route.key].orEmpty()
                        received[route.key] = previous + response.items
                        publishCards()
                        mutableState.update {
                            it.copy(
                                progress = it.progress +
                                    (
                                        route.key to UnifiedSearchProgress(
                                            nextPage = page + 1,
                                            more = response.hasNextPage,
                                        )
                                        ),
                            )
                        }
                    },
                    onFailure = { error ->
                        mutableState.update {
                            it.copy(
                                progress = it.progress +
                                    (
                                        route.key to progress.copy(
                                            loading = false,
                                            error = error.message ?: error.javaClass.simpleName,
                                        )
                                        ),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun adapter(
        route: UnifiedSearchRoute,
        request: SourceHomeRequest,
        controls: String,
    ) = object : ExtensionSearchAdapter<UnifiedSearchEntry> {
        override val key = route.key + ":" + controls + ":" + route.home?.revision.orEmpty()
        override fun identity(item: UnifiedSearchEntry) = item.key
        override fun title(item: UnifiedSearchEntry) = runtimeSearchTitle(
            item.source,
            item.url,
            item.title,
            item.medium,
            item.aliases,
        )
        override suspend fun fetch(page: Int, query: String): SearchPage<UnifiedSearchEntry> {
            val response = fetchSource(page, query)
            val titles = engine.withKnownAliases(response.items.map(::title))
            return response.copy(
                items = response.items.zip(titles) { entry, known ->
                    entry.copy(aliases = mergeSearchAliases(entry.aliases, known.aliases))
                },
            )
        }

        private suspend fun fetchSource(page: Int, query: String): SearchPage<UnifiedSearchEntry> {
            if (base.downloadedOnly().get() || route !in state.value.routes) throw IOException("Source unavailable")
            if (route.home != null) {
                if (route.medium == SearchMedium.VIDEO) {
                    val access = videoRegistry.access(route.home.key)
                    val value = videoHomes.forHome(route.home.key).repository.observe(
                        access,
                        request.copy(page = page, query = query),
                        refresh = true,
                    ).first { !it.loading }
                    value.error?.let { throw IOException(it) }
                    val data = value.data ?: throw IOException("No search response")
                    return SearchPage(
                        data.items.map {
                            UnifiedSearchEntry.video(
                                it,
                                route.category,
                                route.categoryTitle,
                            )
                        },
                        data.hasNextPage,
                    )
                }
                val response = mangaHomes.fetch(route.home.key, request.copy(page = page, query = query))
                return SearchPage(
                    response.items.map {
                        UnifiedSearchEntry.manga(
                            it,
                            route.category,
                            route.categoryTitle,
                        )
                    },
                    response.hasNextPage,
                )
            }
            if (route.medium == SearchMedium.VIDEO) {
                val source = videos.get(route.source) ?: throw IOException("Source unavailable")
                val response = source.getSearchAnime(page, query, source.getFilterList())
                return SearchPage(
                    response.animes.map { remote ->
                        val local = toVideo.await(remote.toDomainAnime(source.id))
                        val anime = local.copy(
                            thumbnailUrl = remote.thumbnail_url?.takeIf(String::isNotBlank) ?: local.thumbnailUrl,
                        )
                        val hints = SourceTrackingHints.from(remote)
                        val ids = buildMap {
                            hints?.anilistId?.let { put("anilist", it) }
                            hints?.malId?.let { put("myanimelist", it) }
                        }
                        val entry = UnifiedSearchEntry.video(anime, route.category, route.categoryTitle)
                        entry.copy(
                            aliases = mergeSearchAliases(entry.aliases, searchAliases(remote.memo)),
                            catalogIds =
                            entry.catalogIds + ids,
                        )
                    },
                    response.hasNextPage,
                )
            }
            val source = mangas.get(route.source) as? eu.kanade.tachiyomi.source.CatalogueSource
                ?: throw IOException("Source unavailable")
            val response = source.getSearchManga(page, query, source.getFilterList())
            return SearchPage(
                response.mangas.map { remote ->
                    val hints = SourceTrackingHints.from(remote)
                    val ids = buildMap {
                        hints?.anilistId?.let {
                            put(
                                "anilist",
                                it,
                            )
                        }
                        hints?.malId?.let {
                            put(
                                "myanimelist",
                                it,
                            )
                        }
                        hints?.mangaUpdatesId?.let {
                            put(
                                "mangaupdates",
                                it,
                            )
                        }
                    }
                    val local = toManga.await(remote.toDomainManga(source.id))
                    val manga = local.copy(
                        thumbnailUrl = remote.thumbnail_url?.takeIf(String::isNotBlank) ?: local.thumbnailUrl,
                    )
                    val item = MangaHomeItem(manga, MangaHomePresentation(catalogIds = ids), remote.title)
                    UnifiedSearchEntry.manga(
                        item,
                        route.category,
                        route.categoryTitle,
                    ).copy(aliases = remote.searchTitle(source.id).aliases)
                },
                response.hasNextPage,
            )
        }
    }

    companion object {
        const val DEFAULT_VIDEO = "nyanime:catalog-video"
    }
}
