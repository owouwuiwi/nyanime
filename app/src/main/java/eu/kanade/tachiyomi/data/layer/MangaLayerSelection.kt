package eu.kanade.tachiyomi.data.layer

/** A capability can have one owner; dependencies form a graph across the selected layers. */
internal object MangaLayerSelection {
    data class Offer(val packageName: String, val target: MangaLayerTarget)

    fun resolve(
        offers: Collection<Offer>,
        preferred: Map<String, String>,
    ): Map<String, Offer> {
        val selected = offers.flatMap { it.target.capabilities }.distinct().mapNotNull { capability ->
            val candidates = offers.filter { capability in it.target.capabilities }
            val choice = preferred[capability]
            val winner = if (choice == null) candidates.singleOrNull() else candidates.singleOrNull { it.packageName == choice }
            winner?.let { capability to it }
        }.toMap()

        fun usable(capability: String, visited: Set<String>): Boolean {
            if (capability in visited) return false
            val offer = selected[capability] ?: return false
            return offer.target.requires[capability].orEmpty().all { usable(it, visited + capability) }
        }

        return selected.filterKeys { usable(it, emptySet()) }
    }
}
