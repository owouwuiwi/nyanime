package eu.kanade.presentation.entries.details

/** Includes unloaded seasons: an absent episode cache is not an empty season. */
internal data class DetailEpisodeSection(
    val key: String,
    val title: String,
    val parts: List<DetailPart>,
    val total: Int? = parts.size,
    val completed: Int = parts.count { it.completed },
    val loaded: Boolean = true,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val canOpen: Boolean = false,
    val directOpen: Boolean = false,
)

internal sealed interface DetailEpisodeRow {
    val key: String
    data class Header(val section: DetailEpisodeSection) : DetailEpisodeRow {
        override val key = "episode-section:${section.key}"
    }
    data class Item(val part: DetailPart, val sectionKey: String) : DetailEpisodeRow {
        override val key = "part:${part.id}"
    }
    data class Status(val section: DetailEpisodeSection) : DetailEpisodeRow {
        override val key = "episode-section-status:${section.key}"
    }
}

internal object DetailEpisodeSections {
    fun fromParts(
        parts: List<DetailPart>,
        otherTitle: String,
        visibleParts: List<DetailPart> = parts,
    ): List<DetailEpisodeSection> {
        if (parts.none { it.group != null }) return emptyList()
        val visibleByGroup = visibleParts.groupBy { it.group?.id }
        return parts.groupBy { it.group?.id }.entries.sortedWith(
            compareBy<Map.Entry<String?, List<DetailPart>>> { it.value.first().group?.order ?: Int.MAX_VALUE },
        ).map { (id, items) ->
            DetailEpisodeSection(
                key = if (id == null) "ungrouped" else "declared:${id.length}:$id",
                title = items.first().group?.title ?: otherTitle,
                parts = visibleByGroup[id].orEmpty(),
                total = items.size,
                completed = items.count { it.completed },
            )
        }
    }

    fun initial(sections: List<DetailEpisodeSection>, currentId: Long?, preferred: String?): String? =
        sections.firstOrNull { section -> section.parts.any { it.id == currentId } }?.key
            ?: preferred?.takeIf { key -> sections.any { it.key == key } }
            ?: sections.firstOrNull()?.key

    fun rows(
        sections: List<DetailEpisodeSection>,
        expanded: Set<String>,
        query: String,
        collapsedSearch: Set<String> = emptySet(),
    ): List<DetailEpisodeRow> =
        buildList {
            sections.forEach { section ->
                val titleMatch = query.isNotBlank() && section.title.contains(query.trim(), ignoreCase = true)
                val matches = if (titleMatch) section.parts else DetailParts.search(section.parts, query)
                if (query.isNotBlank() && matches.isEmpty() && section.loaded && !titleMatch) return@forEach
                add(DetailEpisodeRow.Header(section))
                val open = if (query.isNotBlank()) section.key !in collapsedSearch else section.key in expanded
                if (!section.directOpen && open) {
                    matches.forEach { add(DetailEpisodeRow.Item(it, section.key)) }
                    if (matches.isEmpty() || section.loading || section.failed) add(DetailEpisodeRow.Status(section))
                }
            }
        }

    fun index(rows: List<DetailEpisodeRow>, id: Long): Int? =
        rows.indexOfFirst { it is DetailEpisodeRow.Item && it.part.id == id }.takeIf { it >= 0 }
            ?.plus(DetailListLayout.FIRST_ROW)
}
