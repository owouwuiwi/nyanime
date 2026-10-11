package eu.kanade.tachiyomi.ui.search

import eu.kanade.tachiyomi.data.discovery.MangaGenreLabels
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.SourceHomeSection
import tachiyomi.domain.discovery.SourceHomeSource
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.asMangaCover
import tachiyomi.domain.search.SearchMatchKind
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchResultRanker
import tachiyomi.domain.search.SearchResultRelevance
import tachiyomi.domain.search.SearchTitle
import tachiyomi.domain.search.mergeSearchAliases
import tachiyomi.domain.search.searchAliases

data class UnifiedSearchTarget(val id: Long, val source: Long, val medium: SearchMedium, val title: String)

data class UnifiedSearchEntry(
    val key: String,
    val medium: SearchMedium,
    val source: Long,
    val url: String,
    val title: String,
    val category: String,
    val categoryTitle: String,
    val anime: Anime? = null,
    val manga: Manga? = null,
    val tvChannel: nyanime.tv.api.TvChannel? = null,
    val catalogIds: Map<String, Long> = emptyMap(),
    val aliases: List<String> = emptyList(),
    val targets: List<UnifiedSearchTarget> = emptyList(),
) {
    val artwork: Any? get() = tvChannel?.logoUrl ?: anime?.asAnimeCover()
        ?: manga?.asMangaCover()

    companion object {
        fun live(channel: nyanime.tv.api.TvChannel): UnifiedSearchEntry = UnifiedSearchEntry(
            "tv:${channel.ref.sourceId}:${channel.ref.id}",
            SearchMedium.VIDEO,
            channel.ref.sourceId,
            channel.ref.id,
            channel.name,
            eu.kanade.tachiyomi.ui.discovery.DiscoveryTab.TV_CATEGORY,
            "TV",
            tvChannel = channel,
        )

        fun video(anime: Anime, category: String, label: String): UnifiedSearchEntry {
            val presentation = anime.homePresentation
            val hints = SourceTrackingHints.from(anime)
            val ids = buildMap {
                hints?.anilistId?.let { put("anilist", it) }
                hints?.malId?.let { put("myanimelist", it) }
                putAll(presentation?.catalogIds.orEmpty())
            }
            return UnifiedSearchEntry(
                "video:${anime.source}:${anime.url}", SearchMedium.VIDEO, anime.source, anime.url, anime.title,
                category, label, anime = anime, catalogIds = ids,
                aliases = mergeSearchAliases(presentation?.aliases.orEmpty(), searchAliases(anime.memo)),
                targets =
                presentation?.choices?.map {
                    UnifiedSearchTarget(
                        it.animeId,
                        it.sourceId,
                        SearchMedium.VIDEO,
                        it.title,
                    )
                }
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf(UnifiedSearchTarget(anime.id, anime.source, SearchMedium.VIDEO, anime.title)),
            )
        }

        fun manga(item: MangaHomeItem, category: String, label: String): UnifiedSearchEntry = UnifiedSearchEntry(
            "manga:${item.manga.source}:${item.manga.url}", SearchMedium.MANGA, item.manga.source,
            item.manga.url, item.sourceTitle, category, label, manga = item.manga,
            catalogIds = item.presentation?.catalogIds.orEmpty(),
            aliases = mergeSearchAliases(item.searchAliases),
            targets = (listOf(item.manga) + item.alternateSources).map {
                UnifiedSearchTarget(it.id, it.source, SearchMedium.MANGA, it.title)
            }.distinctBy { it.id },
        )
    }
}

data class UnifiedSearchCard(
    val key: String,
    val variants: List<UnifiedSearchEntry>,
    val relevance: SearchResultRelevance = SearchResultRelevance(),
) {
    val entry get() = variants.first()
    val targets get() = variants.flatMap { it.targets }.distinctBy { it.medium to it.id }
}

data class UnifiedSearchResults(val cards: List<UnifiedSearchCard>, val otherCount: Int = 0) {
    val narrowed get() = otherCount > 0
}

object UnifiedSearchCards {
    /** Keep every source answer accessible; only narrow when the title evidence is convincing. */
    fun results(cards: List<UnifiedSearchCard>, includeOthers: Boolean = false): UnifiedSearchResults {
        val confident = cards.any { it.relevance.kind.ordinal <= SearchMatchKind.WORDS.ordinal }
        if (includeOthers || !confident) return UnifiedSearchResults(cards)
        val pertinent = cards.filter { it.relevance.kind != SearchMatchKind.OTHER }
        return UnifiedSearchResults(pertinent, cards.size - pertinent.size)
    }

    fun merge(
        entries: List<UnifiedSearchEntry>,
        previous: List<UnifiedSearchCard> = emptyList(),
    ): List<UnifiedSearchCard> {
        val groups = mutableListOf<MutableList<UnifiedSearchEntry>>()
        entries.groupBy {
            listOf(it.medium, it.source, it.url, it.tvChannel != null)
        }.values.map { versions ->
            versions.first().copy(
                aliases = mergeSearchAliases(
                    versions.flatMap {
                        it.aliases
                    },
                    versions.map { it.title }.filterNot { it == versions.first().title },
                ),
            )
        }.forEach { incoming ->
            val group = groups.firstOrNull { members ->
                members.all { !conflicts(it, incoming) } && members.any { same(it, incoming) }
            }
            if (group == null) {
                groups.add(mutableListOf(incoming))
            } else {
                group.add(incoming)
                val compatible = groups.filter { other ->
                    other !== group &&
                        other.any { same(it, incoming) } &&
                        other.all { member -> group.all { !conflicts(member, it) } }
                }
                compatible.forEach { other ->
                    if (other.all { member -> group.all { !conflicts(member, it) } }) {
                        group.addAll(other)
                        groups.remove(other)
                    }
                }
            }
        }
        val assigned = mutableSetOf<String>()
        val owners = groups.flatMapIndexed { index, members -> members.map { it.key to index } }.toMap()
        return groups.mapIndexed { index, members ->
            val keys = members.mapTo(mutableSetOf()) { it.key }
            val retained = previous.firstOrNull { card ->
                card.key !in assigned &&
                    (owners[card.key] == null || owners[card.key] == index) &&
                    card.variants.any { it.key in keys }
            }?.key
            val key = retained ?: members.first { it.key !in assigned }.key
            assigned += key
            UnifiedSearchCard(key, members.toList())
        }
    }

    /** Entries already follow configured route and source-page order, independent of arrival. */
    fun ranked(
        query: String,
        cards: List<UnifiedSearchCard>,
        entries: List<UnifiedSearchEntry>,
        ranker: SearchResultRanker,
    ): List<UnifiedSearchCard> {
        if (query.isBlank()) return cards
        val positions = buildMap { entries.forEachIndexed { index, entry -> putIfAbsent(entry.key, index) } }
        val relevance = ranker.rank(
            query,
            cards.flatMap { card ->
                card.variants.map { SearchTitle(it.key, it.title, it.medium, it.aliases, sourceId = it.source) }
            },
        )
        fun match(entry: UnifiedSearchEntry) = relevance[entry.key] ?: SearchResultRelevance()
        val order = compareBy<UnifiedSearchEntry> { match(it) }.thenBy { positions[it.key] ?: Int.MAX_VALUE }
        return cards.map { card ->
            val variants = card.variants.sortedWith(order)
            card.copy(variants = variants, relevance = match(variants.first()))
        }
            .sortedWith(
                compareBy<UnifiedSearchCard> { match(it.entry) }.thenBy {
                    positions[it.entry.key]
                        ?: Int.MAX_VALUE
                },
            )
    }

    private fun conflicts(a: UnifiedSearchEntry, b: UnifiedSearchEntry) =
        a.medium != b.medium ||
            (a.tvChannel != null) != (b.tvChannel != null) ||
            (a.catalogIds.keys intersect b.catalogIds.keys).any { a.catalogIds[it] != b.catalogIds[it] }

    private fun same(a: UnifiedSearchEntry, b: UnifiedSearchEntry): Boolean {
        if (a.medium != b.medium ||
            (a.tvChannel != null) != (b.tvChannel != null)
        ) {
            return false
        }
        if (a.source == b.source && a.url == b.url) return true
        val common = a.catalogIds.keys intersect b.catalogIds.keys
        return common.isNotEmpty() && common.all { a.catalogIds[it] == b.catalogIds[it] }
    }
}

data class UnifiedSearchRoute(
    val key: String,
    val medium: SearchMedium,
    val source: Long,
    val name: String,
    val language: String,
    val category: String,
    val categoryTitle: String,
    val home: SourceHomeSource? = null,
    val tv: Boolean = false,
)

data class UnifiedSearchGenre(val key: String, val label: String, val sections: Map<String, SourceHomeSection>)

data class UnifiedSearchSelection(
    val category: String?,
    val genres: List<UnifiedSearchGenre>,
    val selected: Set<String>,
) {
    companion object {
        /** Restored selections are validated only after extension initialization has completed. */
        fun resolve(
            category: String?,
            selected: Set<String>,
            categories: Set<String?>,
            routes: List<UnifiedSearchRoute>,
            initializing: Boolean,
        ): UnifiedSearchSelection {
            val scope = category?.takeIf { it.isNotBlank() && (initializing || it in categories) }
            val genres = UnifiedSearchFilters.genres(routes.filter { scope == null || it.category == scope })
            val kept = if (initializing) selected else selected.intersect(genres.map { it.key }.toSet())
            return UnifiedSearchSelection(scope, genres, kept)
        }
    }
}

object UnifiedSearchFilters {
    fun genres(routes: List<UnifiedSearchRoute>): List<UnifiedSearchGenre> = routes.flatMap { route ->
        route.home?.categories.orEmpty().map { Triple(MangaGenreLabels.key(it.title), route.key, it) }
    }.groupBy { it.first }.map { (key, members) ->
        UnifiedSearchGenre(key, members.first().third.title, members.associate { it.second to it.third })
    }

    /** Every selected control must be honored. A provider with no declaration is never guessed. */
    fun section(route: UnifiedSearchRoute, selected: List<UnifiedSearchGenre>): SourceHomeSection? {
        val request = request(route, selected) ?: return null
        val home = route.home ?: return null
        val section = if (request.sectionId == SourceHomeRequest.SEARCH) {
            home.search
        } else {
            home.categories.firstOrNull { it.id == request.sectionId }
        }
        return section?.copy(
            selections = section.moreSelections ?: section.selections,
            browseValues = section.browseValues + request.filters,
        )
    }

    fun request(route: UnifiedSearchRoute, selected: List<UnifiedSearchGenre>): SourceHomeRequest? {
        if (selected.isEmpty()) return SourceHomeRequest(SourceHomeRequest.SEARCH, browse = true)
        val sections = selected.map { it.sections[route.key] ?: return null }
        if (sections.size == 1) {
            val section = sections.single()
            return SourceHomeRequest(section.id, browse = true)
        }
        if (route.medium == SearchMedium.MANGA || sections.any { it.browseValues.isEmpty() }) return null
        val filters = sections.flatMap { it.browseValues.entries }.groupBy { it.key }
            .mapValues { (_, values) -> values.flatMap { it.value }.distinct() }
        if (!filters.all { (name, values) ->
                route.home?.browseFilters.orEmpty().any {
                    it.name == name &&
                        it.accepts(values)
                }
            }
        ) {
            return null
        }
        return SourceHomeRequest(SourceHomeRequest.SEARCH, filters = filters, browse = true)
    }
}

/** Disposable, bounded public Home snapshots. Never loads feeds or stores private browsing. */
object UnifiedSearchExploreCache {
    private val entries = LinkedHashMap<String, UnifiedSearchEntry>()
    private val mutableEntries = MutableStateFlow<List<UnifiedSearchEntry>>(emptyList())
    val snapshots = mutableEntries.asStateFlow()

    @Synchronized
    fun offer(items: List<UnifiedSearchEntry>) {
        items.take(60).forEach { incoming ->
            val key = incoming.category + ":" + incoming.key
            entries[key] =
                incoming.copy(aliases = mergeSearchAliases(incoming.aliases, entries[key]?.aliases.orEmpty()))
        }
        while (entries.size > 160) entries.remove(entries.keys.first())
        mutableEntries.value = entries.values.toList()
    }
}
