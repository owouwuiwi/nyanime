package eu.kanade.tachiyomi.ui.library

/** Media are already ordered by normalized title. For ties, keep media before novels, as in the catalogue. */
internal inline fun <T> mergeLibraryTitles(
    media: List<Pair<String, T>>,
    novels: List<Pair<String, T>>,
    matchesMedia: (T) -> Boolean,
): List<T> {
    val sortedNovels = novels.sortedBy { it.first }
    return buildList {
        var nextNovel = 0
        for ((title, entry) in media) {
            if (!matchesMedia(entry)) continue
            while (nextNovel < sortedNovels.size && sortedNovels[nextNovel].first < title) {
                add(sortedNovels[nextNovel++].second)
            }
            add(entry)
        }
        while (nextNovel < sortedNovels.size) {
            add(sortedNovels[nextNovel++].second)
        }
    }
}
