package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.ui.discovery.manga.MangaHomeRowState
import tachiyomi.domain.discovery.SourceHomeSection

/** Every provider's featured section participates, even when its section ID is different. */
internal object MangaHomeFeatured {
    data class Content(val items: List<MangaHomeItem>, val loading: Boolean)

    fun collect(sections: List<SourceHomeSection>, rows: Map<String, MangaHomeRowState>): Content {
        val featured = sections.filter { it.layout == "featured" }.distinctBy { it.id }
        val chosen = featured.ifEmpty { sections.take(1) }
        val items = MangaHomeMerge.merge(chosen.mapNotNull { rows[it.id]?.page }).items
        val loading = items.isEmpty() &&
            chosen.any {
                val row = rows[it.id]
                row?.page == null && row?.error == null && row?.loading != false
            }
        return Content(if (featured.isEmpty()) items.take(8) else items, loading)
    }
}
