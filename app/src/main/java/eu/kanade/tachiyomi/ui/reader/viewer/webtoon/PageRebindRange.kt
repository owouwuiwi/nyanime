package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

internal fun pageRebindRange(position: Int, count: Int): IntRange? {
    if (position < 0 || count <= 0) return null
    val center = position.coerceAtMost(count - 1)
    return (center - 3).coerceAtLeast(0)..(center + 3).coerceAtMost(count - 1)
}
