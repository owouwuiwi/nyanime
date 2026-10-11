package eu.kanade.tachiyomi.data.download.transfer

/** Keep ongoing items first; fill remaining slots fairly across sources. */
internal fun <T> selectDownloads(
    queue: List<T>,
    slots: Int,
    source: (T) -> Long,
    active: (T) -> Boolean,
): List<T> {
    val maximum = if (slots == 0) 3 else slots.coerceIn(1, 5)
    val result = queue.filter(active).take(maximum).toMutableList()
    val groups = queue.filterNot { it in result }.groupBy(source).values.map { it.iterator() }
    while (result.size < maximum) {
        var added = false
        groups.forEach {
            if (result.size < maximum && it.hasNext()) {
                result += it.next()
                added = true
            }
        }
        if (!added) break
    }
    return result
}
