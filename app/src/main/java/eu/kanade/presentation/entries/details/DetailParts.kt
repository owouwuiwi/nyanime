package eu.kanade.presentation.entries.details

/** Presentation-only data. Identity is the local item ID, never a rounded episode number. */
internal data class DetailPart(
    val id: Long,
    val number: String,
    val title: String,
    val information: String,
    val completed: Boolean,
    val bookmarked: Boolean,
    val offline: Boolean,
    val fresh: Boolean,
    val badge: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    val progress: Float = 0f,
    val preview: String? = null,
    val summary: String? = null,
    val group: tachiyomi.domain.items.episode.model.EpisodeGroup? = null,
)

internal enum class DetailFilter { CONTINUE, NEW, INDEX, OFFLINE, BOOKMARKS }

internal object DetailParts {
    const val GROUP_SIZE = 20

    fun groupCount(size: Int): Int = ((size + GROUP_SIZE - 1) / GROUP_SIZE).coerceAtLeast(1)

    fun groupFor(parts: List<DetailPart>, id: Long?): Int =
        parts.indexOfFirst { it.id == id }.coerceAtLeast(0) / GROUP_SIZE

    fun visible(
        parts: List<DetailPart>,
        filter: DetailFilter,
        query: String,
        group: Int,
        currentId: Long?,
    ): List<DetailPart> {
        val search = query.trim()
        // Search covers the entire filtered source list, including specials and fractional numbers.
        if (search.isNotEmpty()) {
            val candidates = when (filter) {
                DetailFilter.NEW -> parts.filter { it.fresh && !it.completed }
                DetailFilter.OFFLINE -> parts.filter { it.offline }
                DetailFilter.BOOKMARKS -> parts.filter { it.bookmarked }
                else -> parts
            }
            return DetailParts.search(candidates, search)
        }
        return when (filter) {
            DetailFilter.CONTINUE -> parts.drop(parts.indexOfFirst { it.id == currentId }.coerceAtLeast(0)).take(5)
            DetailFilter.NEW -> parts.filter { it.fresh && !it.completed }
            DetailFilter.INDEX -> parts.drop(
                group.coerceIn(0, groupCount(parts.size) - 1) * GROUP_SIZE,
            ).take(GROUP_SIZE)
            DetailFilter.OFFLINE -> parts.filter { it.offline }
            DetailFilter.BOOKMARKS -> parts.filter { it.bookmarked }
        }
    }

    /** Searches the entire native-filtered list without changing ordering or item identity. */
    fun search(parts: List<DetailPart>, query: String): List<DetailPart> {
        val text = query.trim()
        return if (text.isEmpty()) {
            parts
        } else {
            parts.filter {
                it.number.contains(text, ignoreCase = true) || it.title.contains(text, ignoreCase = true)
            }
        }
    }

    /** Only explicitly visible IDs are added; changing a page never selects invisible items. */
    fun selectVisible(selected: Set<Long>, visible: List<DetailPart>): Set<Long> =
        selected + visible.map { it.id }

    fun range(parts: List<DetailPart>, anchor: Long?, end: Long): Set<Long> {
        val first = parts.indexOfFirst { it.id == anchor }
        val last = parts.indexOfFirst { it.id == end }
        if (last < 0) return emptySet()
        if (first < 0) return setOf(end)
        return parts.subList(minOf(first, last), maxOf(first, last) + 1).mapTo(mutableSetOf()) { it.id }
    }
}

internal enum class DetailListSection(val key: String) {
    HERO("hero"),
    PROGRESS("progress"),
    NOTICE("notice"),
    TABS("section_tabs"),
    EXTRAS("extra_parts"),
}

/** The UI and navigation use the same fixed prefix, including optional zero-height sections. */
internal object DetailListLayout {
    const val EMPTY_ROW = "empty_parts"
    val TABS = DetailListSection.TABS.ordinal
    val FIRST_ROW = DetailListSection.entries.size

    fun rowIndex(parts: List<DetailPart>, id: Long?, columns: Int = 1): Int? =
        parts.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
            FIRST_ROW + it / columns.coerceAtLeast(1)
        }

    fun groupTarget(parts: List<DetailPart>, group: Int): Long? =
        parts.getOrNull(group.coerceIn(0, DetailParts.groupCount(parts.size) - 1) * DetailParts.GROUP_SIZE)?.id

    /** Keeps short search results anchored without adding scroll space to a complete long list. */
    fun viewportFillHeight(
        viewport: Int,
        header: Int,
        extras: Int,
        padding: Int,
        minimumRow: Int,
        rowKeys: List<String>,
        measured: Map<String, Int>,
    ): Int {
        val available = (
            viewport.coerceAtLeast(0).toLong() -
                header.coerceAtLeast(0) -
                extras.coerceAtLeast(0) -
                padding.coerceAtLeast(0)
            ).coerceAtLeast(0)
        val minimum = minimumRow.coerceAtLeast(0)
        if (rowKeys.size.toLong() * minimum >= available) return 0
        val occupied = rowKeys.sumOf { (measured[it] ?: minimum).coerceAtLeast(minimum).toLong() }
        return (available - occupied).coerceAtLeast(0).toInt()
    }
}
