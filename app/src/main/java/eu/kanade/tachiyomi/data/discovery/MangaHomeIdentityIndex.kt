package eu.kanade.tachiyomi.data.discovery

/** Shared across sections and searches. Source URLs are references, never identity signals. */
internal class MangaHomeIdentityIndex(private val capacity: Int = 512) {
    private val entries = LinkedHashMap<Pair<Long, String>, MangaHomeItem>()
    private var revisions = emptyMap<Long, String>()

    @Synchronized
    fun configure(current: Map<Long, String>): Boolean {
        val removed = entries.keys.removeAll { key ->
            current[key.first] == null ||
                revisions[key.first] != current[key.first]
        }
        revisions = current
        return removed
    }

    @Synchronized
    fun remember(item: MangaHomeItem): Boolean {
        if (item.presentation?.catalogIds.isNullOrEmpty() || item.manga.source !in revisions) return false
        val value = item.copy(sourceVariants = emptyList(), stableKey = null)
        val key = item.manga.source to item.manga.url
        if (entries[key] == value) return false
        entries[key] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return true
    }

    @Synchronized
    fun get(source: Long, url: String): MangaHomeItem? = entries[source to url]

    @Synchronized
    fun attach(item: MangaHomeItem, preferredSource: Long? = null): MangaHomeItem {
        val stored = entries[item.manga.source to item.manga.url]?.takeIf { it.sourceTitle == item.sourceTitle }
        val cachedIds = stored?.presentation?.catalogIds.orEmpty()
        val originalIds = item.presentation?.catalogIds.orEmpty()
        val current = if (cachedIds.isNotEmpty() &&
            (cachedIds.keys intersect originalIds.keys).all { cachedIds[it] == originalIds[it] }
        ) {
            item.copy(
                presentation = (item.presentation ?: MangaHomePresentation()).copy(
                    catalogIds =
                    cachedIds + originalIds,
                ),
            )
        } else {
            item
        }
        val alternatives = entries.values.filter {
            it.manga.source != current.manga.source &&
                MangaHomeMerge.samePublicWork(current, it)
        }
        val filtered = current.copy(sourceVariants = current.sourceVariants.filter { it.manga.source in revisions })
        return MangaHomeMerge.merge(
            listOf(MangaHomePage(listOf(filtered), false), MangaHomePage(alternatives, false)),
            preferredSource,
        ).items.first()
    }
}
