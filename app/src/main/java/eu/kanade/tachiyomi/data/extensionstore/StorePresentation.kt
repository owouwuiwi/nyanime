package eu.kanade.tachiyomi.data.extensionstore

import java.util.Locale

enum class StoreShelf { OWNED, DISCOVER }
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
    ): List<StoreEntry> = entries.asSequence().filter {
        it.installed == (shelf == StoreShelf.OWNED) &&
            (medium == null || it.medium == medium) &&
            (
                shelf != StoreShelf.OWNED ||
                    activation == StoreActivation.ALL ||
                    it.enabled == (activation == StoreActivation.ACTIVE)
                ) &&
            (it.installed || adult || !it.nsfw) &&
            it.matchesLanguages(languages) &&
            it.matchesQuery(query)
    }.sortedWith(
        compareBy<StoreEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id },
    ).toList()
}
