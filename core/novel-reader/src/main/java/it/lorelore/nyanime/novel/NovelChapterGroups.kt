package it.lorelore.nyanime.novel

/**
 * Presentation groups only: labels come from the source and never change chapter identity/order.
 */
data class NovelChapterGroup<T>(val label: String?, val entries: List<T>) {
    val key: String
        get() = label.orEmpty()
}

object NovelChapterGroups {
    /**
     * First appearance orders groups; the source's relative order is retained inside each group.
     */
    fun <T> of(entries: List<T>, label: (T) -> String?): List<NovelChapterGroup<T>> =
        entries
            .groupBy { label(it)?.trim()?.takeIf(String::isNotEmpty) }
            .map { (name, values) -> NovelChapterGroup(name, values) }

    fun <T> initial(groups: List<NovelChapterGroup<T>>, current: (T) -> Boolean): String? =
        (groups.firstOrNull { group -> group.entries.any(current) } ?: groups.firstOrNull())?.key
}
