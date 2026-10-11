package eu.kanade.tachiyomi.data.discovery

import tachiyomi.domain.discovery.SourceHomeGroup
import tachiyomi.domain.discovery.SourceHomeListing
import tachiyomi.domain.discovery.SourceHomeSource

/** Extension-declared reading categories share the same navigation and search identity. */
object MangaHomeCategories {
    const val DEFAULT_HOME = "manga"
    const val DEFAULT_CATEGORY = "nyanime:manga"
    private const val PREFIX = "nyanime:manga-home:"

    fun key(homeId: String): String = if (homeId == DEFAULT_HOME) DEFAULT_CATEGORY else PREFIX + homeId

    fun homeId(category: String?): String? = when {
        category == DEFAULT_CATEGORY -> DEFAULT_HOME
        category?.startsWith(PREFIX) == true -> category.removePrefix(PREFIX).takeIf(String::isNotEmpty)
        else -> null
    }

    fun groups(listing: SourceHomeListing): List<SourceHomeGroup> {
        val declared = listing.groups.map { it.copy(id = key(it.id)) }
        return if (!listing.nativeFallback || declared.any { it.id == DEFAULT_CATEGORY }) {
            declared
        } else {
            listOf(SourceHomeGroup(DEFAULT_CATEGORY, "Manga", emptyList())) + declared
        }
    }

    fun sources(listing: SourceHomeListing, homeId: String): List<SourceHomeSource> =
        listing.homes.filter { it.homeId == homeId }

    fun includesSource(listing: SourceHomeListing, homeId: String, sourceId: Long): Boolean {
        return includesSource(homeId, listing.homes.filter { it.id == sourceId }.map { it.homeId }.toSet())
    }

    fun includesSource(homeId: String, declaredHomeIds: Set<String>): Boolean =
        if (declaredHomeIds.isEmpty()) homeId == DEFAULT_HOME else homeId in declaredHomeIds
}
