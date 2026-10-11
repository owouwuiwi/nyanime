package eu.kanade.tachiyomi.data.extensionstore

import java.util.Locale

enum class StoreShelf { OWNED, DISCOVER, CATALOGUE }
enum class StoreActivation { ALL, ACTIVE, INACTIVE }

/** A single catalogue model for every viewport; no network or source initialization while filtering. */
internal object StorePresentation {
    fun select(
        entries: List<StoreEntry>,
        shelf: StoreShelf,
        medium: StoreMedium?,
        activation: StoreActivation,
        query: String,
        languages: Set<String>,
        adult: Boolean,
        bridges: Map<String, List<StoreBridge>> = emptyMap(),
    ): List<StoreEntry> {
        val terms = storeQueryTerms(query)
        val matching = entries.filter {
            (shelf == StoreShelf.CATALOGUE || it.installed == (shelf == StoreShelf.OWNED)) &&
                (medium == null || it.medium == medium) &&
                (
                    shelf == StoreShelf.DISCOVER || !it.installed ||
                        activation == StoreActivation.ALL ||
                        it.enabled == (activation == StoreActivation.ACTIVE)
                    ) &&
                (it.installed || adult || !it.nsfw) &&
                it.matchesLanguages(languages) &&
                (it.matchesQuery(terms) || bridges[it.id].orEmpty().any { bridge -> bridge.entry.matchesQuery(terms) })
        }
        val attached = matching.flatMap { bridges[it.id].orEmpty() }.map { it.entry.id }.toSet()
        return matching.filter {
            !it.privateOnly || medium == StoreMedium.MANGA_LAYER || when (shelf) {
                StoreShelf.OWNED -> true
                StoreShelf.DISCOVER -> query.isNotBlank()
                StoreShelf.CATALOGUE -> it.action == StoreAction.UPDATE || it.id !in attached
            }
        }.sortedWith(
            compareBy<StoreEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id },
        )
    }
}
