package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaCatalogIdResolver
import eu.kanade.tachiyomi.source.MangaSourceUpdateGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.search.searchTitle
import tachiyomi.domain.source.manga.service.MangaSourceManager
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class MangaHomeService(
    private val registry: MangaHomeRegistry,
    private val manager: MangaSourceManager,
    private val toLocal: NetworkToLocalManga,
) {
    private val requests = Semaphore(2)
    private val interactiveRequests = Semaphore(2)
    private val identityRequests = Semaphore(2)
    private val identityCache = ConcurrentHashMap<String, Map<String, Long>>()
    private val index = MangaHomeIdentityIndex()
    private val revision = MutableStateFlow(0L)
    val identityChanges = revision.asStateFlow()

    private fun configureIndex() {
        if (index.configure(registry.current().homes.associate { it.id to it.revision })) revision.update { it + 1 }
    }

    private fun remember(item: MangaHomeItem): MangaHomeItem {
        if (index.remember(item)) revision.update { it + 1 }
        return item
    }

    fun withAlternatives(item: MangaHomeItem, preferredSource: Long? = null): MangaHomeItem =
        index.attach(item, preferredSource)

    fun mergeKnown(page: MangaHomePage, preferredSource: Long? = null): MangaHomePage =
        MangaHomeMerge.merge(
            listOf(
                page.copy(
                    items = page.items.map {
                        withAlternatives(it, preferredSource)
                    },
                ),
            ),
            preferredSource,
        )

    suspend fun resolveAlternatives(item: MangaHomeItem): MangaHomeItem = withContext(Dispatchers.IO) {
        configureIndex()
        val identified = enrichIdentity(item, timeoutMillis = 15_000)
        val ids = identified.presentation?.catalogIds.orEmpty()
        if (ids.isEmpty()) return@withContext withAlternatives(identified)
        val existing = withAlternatives(identified)
        val knownSources = (existing.alternateSources.map { it.source } + existing.manga.source).toSet()
        coroutineScope {
            registry.current().homes.filter { it.id !in knownSources }.map { home ->
                async {
                    identityRequests.withPermit {
                        val source = manager.get(home.id) as? MangaCatalogIdResolver ?: return@withPermit
                        try {
                            val remote = withTimeoutOrNull(20_000) {
                                ids.entries.sortedBy {
                                    if (it.key ==
                                        "anilist"
                                    ) {
                                        0
                                    } else {
                                        1
                                    }
                                }.firstNotNullOfOrNull { (kind, id) ->
                                    source.findMangaByCatalogId(kind, id)
                                }
                            } ?: return@withPermit
                            if (registry.current().homes.none {
                                    it.key == home.key && it.revision == home.revision
                                }
                            ) {
                                return@withPermit
                            }
                            val hints = SourceTrackingHints.from(remote).catalogIds()
                            val presentation = MangaHomePresentation.from(remote)
                            val candidate = MangaHomeItem(
                                toLocal.await(remote.toDomainManga(home.id)),
                                (presentation ?: MangaHomePresentation()).copy(
                                    catalogIds =
                                    presentation?.catalogIds.orEmpty() + hints,
                                ),
                                remote.title,
                            )
                            if (MangaHomeMerge.samePublicWork(identified, candidate)) remember(candidate)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A failed provider remains retryable; do not store a negative identity.
                        }
                    }
                }
            }.awaitAll()
        }
        withAlternatives(identified)
    }

    suspend fun enrichIdentity(item: MangaHomeItem, timeoutMillis: Long = 6_000): MangaHomeItem {
        configureIndex()
        val cached = withAlternatives(item)
        if (cached.presentation?.catalogIds?.isNotEmpty() == true) return remember(cached)
        val sourceRevision = registry.current().homes.firstOrNull { it.id == item.manga.source }?.revision.orEmpty()
        val key = "$sourceRevision:${item.manga.source}:${item.manga.url}"
        val ids = identityCache[key] ?: identityRequests.withPermit {
            identityCache[key] ?: withTimeoutOrNull(timeoutMillis) {
                val source = manager.get(item.manga.source) ?: return@withTimeoutOrNull emptyMap()
                val details = MangaSourceUpdateGate.await(
                    source,
                    item.manga.toSManga(),
                    emptyList(),
                    fetchDetails = true,
                    fetchChapters = false,
                ).manga
                SourceTrackingHints.from(details).catalogIds()
            }.orEmpty().also { found ->
                if (identityCache.size > 512) identityCache.clear()
                if (found.isNotEmpty()) identityCache[key] = found
            }
        }
        return if (ids.isEmpty()) {
            item
        } else {
            remember(
                item.copy(
                    presentation = (item.presentation ?: MangaHomePresentation()).copy(catalogIds = ids),
                ),
            )
        }
    }

    private fun SourceTrackingHints?.catalogIds(): Map<String, Long> = buildMap {
        this@catalogIds?.anilistId?.let { put("anilist", it) }
        this@catalogIds?.malId?.let { put("myanimelist", it) }
        this@catalogIds?.mangaUpdatesId?.let { put("mangaupdates", it) }
    }

    suspend fun fetch(key: String, request: SourceHomeRequest): MangaHomePage = withContext(Dispatchers.IO) {
        configureIndex()
        val permits = if (request.sectionId == SourceHomeRequest.SEARCH || request.sectionId.startsWith("category:")) {
            interactiveRequests
        } else {
            requests
        }
        permits.withPermit {
            val access = registry.access(key)
            check(!access.offline) { "La Home online non è disponibile in modalità Solo scaricati" }
            val definition = requireNotNull(access.source) { "Fonte non disponibile" }
            val source = requireNotNull(manager.get(definition.id) as? CatalogueSource)
            val section = if (request.sectionId == SourceHomeRequest.SEARCH) {
                requireNotNull(definition.search)
            } else {
                (definition.sections + definition.categories).firstOrNull { it.id == request.sectionId }
                    ?: error("Sezione non disponibile: aggiorna la Home")
            }
            val chosen = if (request.browse && section.moreSelections != null) {
                section.copy(selections = section.moreSelections ?: section.selections)
            } else {
                section
            }
            val filters = MangaHomeFilters.apply(source.getFilterList(), chosen, request.date)
            MangaHomeFilters.applyBrowse(filters, definition.browseFilters, section.browseValues + request.filters)
            try {
                withTimeout(30_000) {
                    val result = source.getSearchManga(request.page, request.query.trim(), filters)
                    check(access == registry.access(key)) { "La fonte è stata disabilitata" }
                    val items = result.mangas.take(100).map { remote ->
                        val home = MangaHomePresentation.from(remote)
                        val catalogIds = SourceTrackingHints.from(remote).catalogIds()
                        val presentation = if (catalogIds.isEmpty()) {
                            home
                        } else {
                            (home ?: MangaHomePresentation()).copy(catalogIds = home?.catalogIds.orEmpty() + catalogIds)
                        }
                        val local = toLocal.await(remote.toDomainManga(source.id))
                        remember(
                            MangaHomeItem(
                                local.copy(
                                    thumbnailUrl =
                                    remote.thumbnail_url?.takeIf(String::isNotBlank) ?: local.thumbnailUrl,
                                ),
                                presentation,
                                remote.title,
                                searchAliases = remote.searchTitle(source.id).aliases,
                            ),
                        )
                    }.distinctBy(MangaHomeItem::key)
                    check(access == registry.access(key)) { "La fonte è stata disabilitata" }
                    MangaHomePage(items, result.hasNextPage)
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("La fonte non ha risposto in tempo. Riprova.", e)
            }
        }
    }
}
