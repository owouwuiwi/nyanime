package tachiyomi.domain.discovery

import kotlinx.coroutines.flow.first

/**
 * Evidence for a combined listing, not an episode remapping.
 * Only an unambiguous chain of completed TV/ONA prequels with known counts qualifies.
 */
class CatalogSeriesEvidence(private val catalog: AnimeCatalogRepository) {
    suspend fun minimumCombinedEpisodes(anime: CatalogAnime): Int? {
        if (anime.format !in serialFormats) return null
        var total = anime.episodes?.takeIf { it > 0 } ?: return null
        var current = anime
        val visited = mutableSetOf(anime.id)
        repeat(8) {
            val prequels = current.relations.filter { it.relationship == "PREQUEL" }
            if (prequels.isEmpty()) return if (visited.size > 1) total else null
            val id = prequels.singleOrNull()?.id ?: return null
            if (!visited.add(id)) return null
            val result = catalog.observeDetails(id).first { !it.loading }
            val prequel = result.data?.takeIf { result.error == null && !result.stale } ?: return null
            if (prequel.format !in serialFormats || prequel.status != "FINISHED") return null
            val count = prequel.episodes?.takeIf { it > 0 } ?: return null
            val roots = (listOf(anime.title) + anime.alternateTitles).map(SmartTitleMatcher::baseTitle).toSet()
            if ((listOf(prequel.title) + prequel.alternateTitles).none {
                    SmartTitleMatcher.baseTitle(it) in roots
                }
            ) {
                return null
            }
            total = Math.addExact(total, count)
            if (total > 10_000) return null
            current = prequel
        }
        return null
    }

    private companion object {
        val serialFormats = setOf("TV", "ONA")
    }
}
