package tachiyomi.domain.discovery

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.entries.anime.model.Anime

/** Source identities stay separate from public catalogue identities. */
data class SourceHomeSection(
    val id: String,
    val title: String,
    val selections: Map<String, String>,
    val layout: String = "posters",
    val group: SourceHomeSectionGroup? = null,
    val dateFilter: String? = null,
    val moreSelections: Map<String, String>? = null,
    val browseValues: Map<String, List<String>> = emptyMap(),
) : java.io.Serializable

/** Optional presentation only; requests and caches still use the concrete section ID. */
data class SourceHomeSectionGroup(val id: String, val title: String, val tab: String) : java.io.Serializable

data class SourceHomeSource(
    val id: Long,
    val revision: String,
    val sections: List<SourceHomeSection>,
    val categories: List<SourceHomeSection>,
    val key: String = id.toString(),
    val title: String = "Home",
    val sourceName: String = "",
    val language: String = "",
    val search: SourceHomeSection? = null,
    val homeId: String = key,
    val primary: Boolean = false,
    val browseFilters: List<SourceHomeFilter> = emptyList(),
    val random: SourceHomeSection? = null,
    val randomEpisode: SourceHomeSection? = null,
)

data class SourceHomeListing(val loading: Boolean = true, val homes: List<SourceHomeSource> = emptyList()) {
    val groups: List<SourceHomeGroup> get() = homes.groupBy { it.homeId }.map { (id, providers) ->
        val ordered = providers.sortedWith(compareByDescending<SourceHomeSource> { it.primary }.thenBy { it.key })
        SourceHomeGroup(id, ordered.first().title, ordered)
    }.sortedWith(compareBy({ it.title }, { it.id }))
}

/** A content kind can be provided by several extensions; concrete source identities never get merged. */
data class SourceHomeGroup(val id: String, val title: String, val providers: List<SourceHomeSource>) {
    data class Section(
        val id: String,
        val title: String,
        val layout: String = "posters",
        val group: SourceHomeSectionGroup? = null,
        val supportsDate: Boolean = false,
    )

    data class Row(val id: String, val title: String, val sections: List<Section>) {
        fun selected(id: String?) = sections.firstOrNull { it.id == id } ?: sections.first()
    }

    val primary get() = providers.any { it.primary }
    val sections get() = providers.flatMap {
        it.sections
    }.distinctBy { it.id }.map { first ->
        Section(
            first.id,
            first.title,
            first.layout,
            first.group,
            providers.any { source ->
                source.sections.any { it.id == first.id && it.dateFilter != null }
            },
        )
    }

    // Insertion order preserves the first occurrence of each row, even across providers.
    val rows get() = sections.groupBy { it.group?.let { group -> "group:${group.id}" } ?: "section:${it.id}" }
        .map { (id, variants) ->
            val first = variants.first()
            Row(id, first.group?.title?.takeIf { variants.size > 1 } ?: first.title, variants)
        }
    val categories get() = providers.flatMap { it.categories }.distinctBy { it.id }.filter { category ->
        category.browseValues.all { (name, values) ->
            browseFilters.any { it.name == name && it.accepts(values) }
        }
    }.map { Section(it.id, it.title) }

    // Expose the union; a filtered request only visits providers that can honor every selection.
    val browseFilters get(): List<SourceHomeFilter> {
        val searchable = providers.filter { it.search != null }
        return searchable.flatMap { it.browseFilters }.groupBy { it.name }.mapNotNull { (_, definitions) ->
            val first = definitions.first()
            if (definitions.any { it.kind != first.kind }) return@mapNotNull null
            first.copy(
                options = definitions.flatMap { it.options }.distinct().take(200),
                defaults = definitions.map { it.defaults }.distinct().singleOrNull().orEmpty(),
            )
        }
    }
    val searchable get() = providers.any { it.search != null }
    val hasRandom get() = providers.any { it.random != null }
    val hasRandomEpisode get() = providers.any { it.randomEpisode != null }
    val sourceIds get() = providers.map { it.id }.toSet()
    fun sourceLabel(sourceId: Long) = providers.firstOrNull { it.id == sourceId }?.let {
        "${it.sourceName} · ${it.language.uppercase()}"
    }.orEmpty()
}

data class SourceHomeGroupAccess(
    val group: SourceHomeGroup? = null,
    val providers: List<SourceHomeAccess> = emptyList(),
    val loading: Boolean = false,
    val offline: Boolean = false,
)

interface SourceHomeGroupRepository {
    fun observe(
        access: SourceHomeGroupAccess,
        request: SourceHomeRequest,
        refresh: Boolean = false,
    ): Flow<SectionState<SourceHomePage>>
}

data class SourceHomeAccess(
    val source: SourceHomeSource? = null,
    val loading: Boolean = false,
    val offline: Boolean = false,
    val isPrivate: Boolean = false,
    val error: String? = null,
)

data class SourceHomeRequest(
    val sectionId: String,
    val page: Int = 1,
    val query: String = "",
    val date: String? = null,
    val filters: Map<String, List<String>> = emptyMap(),
    val browse: Boolean = false,
) {
    init {
        require(page > 0)
        require(
            filters.size <= 24 &&
                filters.all { (label, values) ->
                    label.length in 1..100 && values.size <= 200 && values.all { it.length <= 300 }
                },
        )
        require(
            date == null ||
                (
                    date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
                        runCatching { java.time.LocalDate.parse(date) }.isSuccess
                    ),
        )
    }

    val cacheSection: String get() = date?.let { "$sectionId:date:$it" } ?: sectionId

    companion object {
        const val SEARCH = "search"
        const val RANDOM = "random"
        const val RANDOM_EPISODE = "random-episode"
    }
}

data class SourceHomePage(val items: List<Anime>, val hasNextPage: Boolean, val title: String? = null)

interface SourceHomeGateway {
    fun observeAccess(): Flow<SourceHomeAccess>
    fun currentAccess(): SourceHomeAccess
    suspend fun fetch(access: SourceHomeAccess, request: SourceHomeRequest): SourceHomePage
}

interface SourceHomeRepository {
    fun observe(
        access: SourceHomeAccess,
        request: SourceHomeRequest,
        refresh: Boolean = false,
    ): Flow<SectionState<SourceHomePage>>
}
