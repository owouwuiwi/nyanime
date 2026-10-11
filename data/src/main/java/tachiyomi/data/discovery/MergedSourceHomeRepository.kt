package tachiyomi.data.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.discovery.SourceHomeGroupAccess
import tachiyomi.domain.discovery.SourceHomeGroupRepository
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomeRepository
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.SourceHomeSource

/** Independent feeds are interleaved fairly; one failing extension never empties its peers. */
class MergedSourceHomeRepository(
    private val repositoryFor: (SourceHomeSource) -> SourceHomeRepository,
    private val currentAccess: (String) -> SourceHomeGroupAccess,
) : SourceHomeGroupRepository {
    private data class Cursor(val key: String, val revision: String, val request: SourceHomeRequest)
    private val finishedAt = LinkedHashMap<Cursor, Int>()

    override fun observe(access: SourceHomeGroupAccess, request: SourceHomeRequest, refresh: Boolean) = flow {
        emitAll(observeSnapshot(access, request, refresh))
    }.flowOn(Dispatchers.IO)

    private fun observeSnapshot(access: SourceHomeGroupAccess, request: SourceHomeRequest, refresh: Boolean) = run {
        val group = access.group
        if (group == null || access.offline || access != currentAccess(group.id)) {
            return@run flowOf(SectionState<SourceHomePage>(loading = false, error = "Home non disponibile"))
        }
        val eligible = access.providers.sortedByDescending { it.source?.primary == true }.filter { provider ->
            provider.source?.let {
                if (request.sectionId == SourceHomeRequest.SEARCH) {
                    it.search != null &&
                        request.filters.all { (name, values) ->
                            it.browseFilters.any { filter -> filter.name == name && filter.accepts(values) }
                        }
                } else if (request.sectionId == SourceHomeRequest.RANDOM) {
                    it.random != null
                } else if (request.sectionId == SourceHomeRequest.RANDOM_EPISODE) {
                    it.randomEpisode != null
                } else {
                    (it.sections + it.categories).any { section ->
                        section.id == request.sectionId && (request.date == null || section.dateFilter != null)
                    }
                }
            } == true
        }
        if (eligible.isEmpty()) {
            if (request.sectionId == SourceHomeRequest.SEARCH && request.filters.isNotEmpty()) {
                return@run flowOf(SectionState(SourceHomePage(emptyList(), false), loading = false))
            }
            return@run flowOf(
                SectionState<SourceHomePage>(loading = false, error = "Sezione non supportata"),
            )
        }
        val providers = if (request.sectionId in setOf(SourceHomeRequest.RANDOM, SourceHomeRequest.RANDOM_EPISODE)) {
            eligible.shuffled().take(1)
        } else {
            eligible
        }
        val feeds = providers.map { provider ->
            val source = requireNotNull(provider.source)
            val cursor = Cursor(source.key, source.revision, request.copy(page = 1, query = request.query.trim()))
            val finished = synchronized(finishedAt) {
                if (provider.isPrivate) finishedAt.clear()
                if (refresh || request.page == 1) finishedAt.remove(cursor)
                finishedAt[cursor]?.let { request.page > it } == true
            }
            if (finished) {
                flowOf(SectionState(SourceHomePage(emptyList(), false), loading = false))
            } else {
                repositoryFor(source).observe(provider, request, refresh).map { state ->
                    if (state.data != null &&
                        !state.loading &&
                        state.error == null &&
                        !state.data!!.hasNextPage &&
                        !provider.isPrivate
                    ) {
                        synchronized(finishedAt) {
                            finishedAt[cursor] = request.page
                            while (finishedAt.size > 128) finishedAt.remove(finishedAt.keys.first())
                        }
                    }
                    state.copy(error = state.error?.let { "${source.sourceName}: $it" })
                }.catch { error ->
                    if (error is CancellationException) throw error
                    emit(SectionState(loading = false, error = "${source.sourceName}: impossibile caricare la sezione"))
                }.onStart { emit(SectionState()) }
            }
        }
        combine(feeds) { states ->
            if (access != currentAccess(group.id)) {
                return@combine SectionState<SourceHomePage>(
                    loading = false,
                    error = "Le fonti della Home sono cambiate",
                )
            }
            val pages = states.mapNotNull { it.data }
            val lists = pages.map { it.items }
            val items = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { row ->
                lists.mapNotNull { it.getOrNull(row) }
            }.let(::mergeHomeCards)
            SectionState(
                data = if (pages.isEmpty()) {
                    null
                } else {
                    SourceHomePage(
                        items,
                        pages.any { it.hasNextPage },
                        pages.mapNotNull { it.title }.distinct().singleOrNull(),
                    )
                },
                loading = states.any { it.loading },
                stale = states.any { it.stale },
                error = states.mapNotNull { it.error }.distinct().joinToString("\n").takeIf { it.isNotBlank() },
            )
        }
    }
}
