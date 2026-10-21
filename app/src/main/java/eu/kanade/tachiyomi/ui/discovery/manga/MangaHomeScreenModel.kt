package eu.kanade.tachiyomi.ui.discovery.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.domain.items.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.discovery.MangaHomeCategories
import eu.kanade.tachiyomi.data.discovery.MangaHomeChapter
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomeLocalContent
import eu.kanade.tachiyomi.data.discovery.MangaHomeMerge
import eu.kanade.tachiyomi.data.discovery.MangaHomePage
import eu.kanade.tachiyomi.data.discovery.MangaHomeRegistry
import eu.kanade.tachiyomi.data.discovery.MangaHomeService
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseUpdateGate
import eu.kanade.tachiyomi.source.MangaSourceUpdateGate
import eu.kanade.tachiyomi.ui.updates.dismissLibraryUpdate
import eu.kanade.tachiyomi.ui.updates.inboxKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.discovery.SourceHomeListing
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.SourceHomeSource
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.history.manga.interactor.GetMangaHistory
import tachiyomi.domain.history.manga.interactor.GetNextChapters
import tachiyomi.domain.history.manga.model.MangaHistoryWithRelations
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.updates.manga.interactor.GetMangaUpdates
import tachiyomi.domain.updates.manga.model.MangaUpdatesWithRelations
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

data class MangaHomeRowState(
    val page: MangaHomePage? = null,
    val loadedPages: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
) {
    fun complete(result: MangaHomePage?, pageNumber: Int, failure: String? = null): MangaHomeRowState {
        if (result == null) return copy(loading = false, error = failure)
        val incoming = result.items.map { it.key }.toSet()
        val previous = if (pageNumber > 1) page?.items.orEmpty().filterNot { it.key in incoming } else emptyList()
        return MangaHomeRowState(
            result.copy(items = (previous + result.items).distinctBy { it.key }),
            pageNumber,
            false,
            failure,
        )
    }
}

data class MangaHomeState(
    val initializing: Boolean = true,
    val homes: List<SourceHomeSource> = emptyList(),
    val selected: SourceHomeSource? = null,
    val mixed: Boolean = true,
    val offline: Boolean = false,
    val rows: Map<String, MangaHomeRowState> = emptyMap(),
    val history: List<MangaHistoryWithRelations> = emptyList(),
    val updates: List<MangaUpdatesWithRelations> = emptyList(),
    val opening: String? = null,
)

class MangaHomeScreenModel(
    private val homeId: String = MangaHomeCategories.DEFAULT_HOME,
    private val registry: MangaHomeRegistry = Injekt.get(),
    private val service: MangaHomeService = Injekt.get(),
    private val base: BasePreferences = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val manager: MangaSourceManager = Injekt.get(),
    private val incognito: GetMangaIncognitoState = Injekt.get(),
    private val getUpdates: GetMangaUpdates = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
) : StateScreenModel<MangaHomeState>(MangaHomeState()) {
    val events = MutableSharedFlow<Event>()
    private var loadJob: Job? = null
    private var openJob: Job? = null
    private var generation = 0
    private val homeListing = registry.observe().stateIn(
        screenModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SourceHomeListing(),
    )

    init {
        screenModelScope.launch {
            state.map { it.rows to it.selected?.title.orEmpty() }.distinctUntilChanged()
                .collectLatest { (rows, title) ->
                    withContext(Dispatchers.IO) {
                        val items = rows.values.flatMap { it.page?.items.orEmpty() }
                        val privateSources = items.mapTo(mutableSetOf()) { it.manga.source }
                            .filterTo(mutableSetOf()) { incognito.await(it) }
                        currentCoroutineContext().ensureActive()
                        eu.kanade.tachiyomi.ui.search.UnifiedSearchExploreCache.offer(
                            items.filterNot { it.manga.source in privateSources }.map {
                                eu.kanade.tachiyomi.ui.search.UnifiedSearchEntry.manga(
                                    it,
                                    MangaHomeCategories.key(homeId),
                                    title,
                                )
                            },
                        )
                    }
                }
        }
        screenModelScope.launch {
            combine(service.identityChanges, uiPreferences.preferredMangaHomeSource().changes()) { _, preferred ->
                preferred
            }
                .collect { preferred ->
                    mutableState.update { current ->
                        if (!current.mixed) {
                            current
                        } else {
                            current.copy(
                                rows = current.rows.mapValues { (_, row) ->
                                    row.copy(
                                        page = row.page?.let {
                                            service.mergeKnown(it, preferred.takeIf { id -> id != 0L })
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
        }
        screenModelScope.launch {
            combine(
                homeListing,
                base.downloadedOnly().changes(),
                base.incognitoMode().changes(),
                preferences.incognitoMangaExtensions().changes(),
            ) { listing, offline, _, _ -> listing to offline }.collectLatest { (listing, offline) ->
                val homes = MangaHomeCategories.sources(listing, homeId)
                val previous = state.value.selected
                val selected = homes.firstOrNull { it.key == previous?.key }
                    ?: homes.firstOrNull { it.primary } ?: homes.firstOrNull()
                loadJob?.cancel()
                openJob?.cancel()
                generation++
                mutableState.update {
                    it.copy(
                        initializing = listing.loading,
                        homes = homes,
                        selected = selected,
                        mixed = if (previous == null) true else it.mixed,
                        offline = offline,
                        rows = emptyMap(),
                        opening = null,
                    )
                }
                if (selected != null && !offline) refresh()
            }
        }
        val localContent = MangaHomeLocalContent(manager, preferences, base, incognito, uiPreferences)
        screenModelScope.launch {
            localContent.history(Injekt.get<GetMangaHistory>().subscribe(""), limit = Int.MAX_VALUE)
                .combine(homeListing) { rows, listing ->
                    if (listing.loading) {
                        emptyList()
                    } else {
                        rows.filter {
                            MangaHomeCategories.includesSource(homeId, registry.declaredHomeIds(it.coverData.sourceId))
                        }.take(20)
                    }
                }
                .collect { history -> mutableState.update { it.copy(history = history) } }
        }
        screenModelScope.launch {
            localContent.updates(getUpdates.subscribe(Instant.now().minusSeconds(30L * 86_400)), limit = Int.MAX_VALUE)
                .combine(homeListing) { rows, listing ->
                    if (listing.loading) {
                        emptyList()
                    } else {
                        rows.filter {
                            MangaHomeCategories.includesSource(homeId, registry.declaredHomeIds(it.sourceId))
                        }.take(30)
                    }
                }
                .collect { updates -> mutableState.update { it.copy(updates = updates) } }
        }
    }

    fun dismissUpdate(update: MangaUpdatesWithRelations) {
        dismissLibraryUpdate(uiPreferences.dismissedLibraryUpdates(), update.inboxKey())
    }

    fun selectHome(key: String) {
        val selected = state.value.homes.firstOrNull { it.key == key } ?: return
        if (selected == state.value.selected && !state.value.mixed) return
        loadJob?.cancel()
        openJob?.cancel()
        generation++
        mutableState.update { it.copy(selected = selected, mixed = false, rows = emptyMap(), opening = null) }
        refresh()
    }

    fun selectAll() {
        if (state.value.mixed) return
        loadJob?.cancel()
        generation++
        mutableState.update { it.copy(mixed = true, rows = emptyMap()) }
        refresh()
    }

    fun preferSource(id: Long) {
        uiPreferences.preferredMangaHomeSource().set(id)
    }

    fun refresh() {
        val home = state.value.selected ?: return
        if (state.value.offline) return
        loadJob?.cancel()
        val version = ++generation
        loadJob = screenModelScope.launch {
            coroutineScope {
                val sections = if (state.value.mixed) {
                    state.value.homes.flatMap { it.sections }.distinctBy { it.id }
                } else {
                    home.sections
                }
                sections.map { section ->
                    async {
                        if (state.value.mixed) {
                            fetchMixed(SourceHomeRequest(section.id), version)
                        } else {
                            fetch(home, SourceHomeRequest(section.id), version)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun loadSection(sectionId: String, next: Boolean = false) {
        val home = state.value.selected ?: return
        val row = state.value.rows[sectionId]
        if (row?.loading == true || state.value.offline) return
        val page = if (next) (row?.loadedPages ?: 0) + 1 else 1
        val version = generation
        screenModelScope.launch {
            if (state.value.mixed) {
                fetchMixed(SourceHomeRequest(sectionId, page), version)
            } else {
                fetch(home, SourceHomeRequest(sectionId, page), version)
            }
        }
    }

    private suspend fun fetchMixed(request: SourceHomeRequest, version: Int) {
        if (version != generation) return
        val providers = state.value.homes.filter { home -> home.sections.any { it.id == request.sectionId } }
            .sortedWith(compareByDescending<SourceHomeSource> { it.primary }.thenBy { it.key })
        if (providers.isEmpty()) return
        mutableState.update { current ->
            current.copy(
                rows = current.rows +
                    (
                        request.sectionId to
                            (current.rows[request.sectionId] ?: MangaHomeRowState()).copy(loading = true, error = null)
                        ),
            )
        }
        val results = coroutineScope {
            providers.map { home ->
                async {
                    try {
                        Result.success(service.fetch(home.key, request))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                }
            }.awaitAll()
        }
        if (version != generation) return
        val pages = results.mapNotNull { it.getOrNull() }
        val error = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
            ?: "Caricamento non riuscito".takeIf { pages.isEmpty() }
        mutableState.update { current ->
            val merged = MangaHomeMerge.merge(
                pages,
                uiPreferences.preferredMangaHomeSource().get().takeIf { it != 0L },
            )
            current.copy(
                rows = current.rows +
                    (
                        request.sectionId to (current.rows[request.sectionId] ?: MangaHomeRowState()).complete(
                            if (pages.isEmpty()) {
                                null
                            } else {
                                service.mergeKnown(
                                    merged,
                                    uiPreferences.preferredMangaHomeSource().get().takeIf {
                                        it !=
                                            0L
                                    },
                                )
                            },
                            request.page,
                            error,
                        )
                        ),
            )
        }
        if (version != generation) return
        // Identity lookups are useful for merging providers, never for filling an empty or single-source feed.
        if (pages.isEmpty() || providers.size == 1) return
        val enriched = coroutineScope {
            pages.map { page ->
                async {
                    page.copy(
                        items = page.items.map { item ->
                            if (item.presentation?.catalogIds?.isNotEmpty() == true) {
                                item
                            } else {
                                try {
                                    service.enrichIdentity(item)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    item
                                }
                            }
                        },
                    )
                }
            }.awaitAll()
        }
        if (version != generation) return
        val richer = MangaHomeMerge.merge(
            enriched,
            uiPreferences.preferredMangaHomeSource().get().takeIf { it != 0L },
        )
        mutableState.update { current ->
            current.copy(
                rows = current.rows +
                    (
                        request.sectionId to (current.rows[request.sectionId] ?: MangaHomeRowState()).complete(
                            service.mergeKnown(
                                richer,
                                uiPreferences.preferredMangaHomeSource().get().takeIf {
                                    it !=
                                        0L
                                },
                            ),
                            request.page,
                            error,
                        )
                        ),
            )
        }
    }

    private suspend fun fetch(home: SourceHomeSource, request: SourceHomeRequest, version: Int) {
        if (version != generation) return
        mutableState.update {
            it.copy(
                rows = it.rows +
                    (
                        request.sectionId to (it.rows[request.sectionId] ?: MangaHomeRowState())
                            .copy(loading = true, error = null)
                        ),
            )
        }
        try {
            val page = service.fetch(home.key, request)
            if (version != generation) return
            mutableState.update { current ->
                current.copy(
                    rows = current.rows +
                        (
                            request.sectionId to (current.rows[request.sectionId] ?: MangaHomeRowState())
                                .complete(page, request.page)
                            ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (version == generation) {
                mutableState.update {
                    it.copy(
                        rows = it.rows +
                            (
                                request.sectionId to (it.rows[request.sectionId] ?: MangaHomeRowState())
                                    .copy(loading = false, error = e.message ?: "Caricamento non riuscito")
                                ),
                    )
                }
            }
        }
    }

    fun openChapter(item: MangaHomeItem, chapter: MangaHomeChapter) {
        if (openJob?.isActive == true) return
        val home = state.value.homes.firstOrNull { it.id == item.manga.source } ?: return
        val access = registry.access(home.key)
        if (access.offline || access.source == null || item.manga.source != home.id) return
        openJob = screenModelScope.launch {
            mutableState.update { it.copy(opening = chapter.url) }
            try {
                val target = withContext(Dispatchers.IO) {
                    withTimeout(30_000) {
                        val chapters = Injekt.get<GetChaptersByMangaId>()
                        val local = chapters.await(item.manga.id).firstOrNull { it.url == chapter.url }
                        if (local != null) return@withTimeout local
                        check(access == registry.access(home.key)) { "Fonte non disponibile" }
                        val source = requireNotNull(manager.get(item.manga.source))
                        ReleaseUpdateGate.withEntry(ReleaseMedium.MANGA, item.manga.id) {
                            val remote = MangaSourceUpdateGate.await(
                                source,
                                item.manga.toSManga(),
                                emptyList(),
                                fetchDetails = false,
                                fetchChapters = true,
                            ).chapters
                            check(access == registry.access(home.key)) { "Fonte non disponibile" }
                            Injekt.get<SyncChaptersWithSource>().await(remote, item.manga, source)
                        }
                        chapters.await(item.manga.id).firstOrNull { it.url == chapter.url }
                            ?: error("Questo capitolo non è più disponibile. Apri la scheda per aggiornare l’elenco.")
                    }
                }
                check(access == registry.access(home.key)) { "Fonte non disponibile" }
                events.emit(Event.Read(item.manga.id, target.id))
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                events.emit(Event.Error("Il capitolo non ha risposto in tempo. Riprova."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.emit(Event.Error(e.message ?: "Impossibile aprire il capitolo"))
            } finally {
                mutableState.update { it.copy(opening = null) }
            }
        }
    }

    fun resume(history: MangaHistoryWithRelations) {
        if (openJob?.isActive == true) return
        openJob = screenModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                Injekt.get<GetNextChapters>().await(
                    history.mangaId,
                    history.chapterId,
                    onlyUnread = false,
                ).firstOrNull { chapter ->
                    !base.downloadedOnly().get() ||
                        Injekt.get<eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager>()
                            .isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                history.title,
                                history.coverData.sourceId,
                            )
                }
            }
            if (next == null) {
                events.emit(Event.Details(history.mangaId))
            } else {
                events.emit(Event.Read(history.mangaId, next.id))
            }
        }
    }

    fun cancelOpening() {
        openJob?.cancel()
        mutableState.update { it.copy(opening = null) }
    }

    sealed interface Event {
        data class Read(val mangaId: Long, val chapterId: Long) : Event
        data class Details(val mangaId: Long) : Event
        data class Error(val message: String) : Event
    }
}
