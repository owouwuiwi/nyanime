package eu.kanade.tachiyomi.data.discovery

/** An ID shared by two providers establishes identity; any conflicting ID vetoes it. */
object MangaHomeMerge {
    fun samePublicWork(left: MangaHomeItem, right: MangaHomeItem): Boolean {
        val a = left.presentation?.catalogIds.orEmpty()
        val b = right.presentation?.catalogIds.orEmpty()
        val shared = a.keys intersect b.keys
        return shared.isNotEmpty() && shared.all { a[it] == b[it] }
    }

    fun merge(pages: List<MangaHomePage>, preferredSource: Long? = null): MangaHomePage {
        val entries = (0 until (pages.maxOfOrNull { it.items.size } ?: 0)).flatMap { index ->
            pages.mapNotNull { it.items.getOrNull(index) }
        }
        val result = mutableListOf<MangaHomeItem>()
        entries.forEach { incoming ->
            val entry = select(incoming, preferredSource)
            val index = result.indexOfFirst { existing ->
                existing.key == entry.key ||
                    (
                        samePublicWork(existing, entry) &&
                            (
                                existing.manga.source != entry.manga.source ||
                                    (
                                        existing.sourceVariants.isNotEmpty() &&
                                            (listOf(existing.variant()) + existing.sourceVariants).any {
                                                it.manga.source == entry.manga.source && it.manga.url == entry.manga.url
                                            }
                                        )
                                )
                        )
            }
            if (index < 0) {
                result += entry
            } else {
                val current = result[index]
                val variants = (
                    listOf(current.variant()) +
                        current.sourceVariants +
                        entry.variant() +
                        entry.sourceVariants
                    )
                    .distinctBy { it.manga.source to it.manga.url }
                val selected = variants.firstOrNull { it.manga.source == preferredSource } ?: current.variant()
                val ids = current.presentation?.catalogIds.orEmpty() + entry.presentation?.catalogIds.orEmpty()
                result[index] = MangaHomeItem(
                    manga = selected.manga,
                    presentation = (selected.presentation ?: MangaHomePresentation()).copy(catalogIds = ids),
                    sourceTitle = selected.sourceTitle,
                    sourceVariants = variants.filterNot {
                        it.manga.source == selected.manga.source &&
                            it.manga.url == selected.manga.url
                    },
                    stableKey = current.key,
                )
            }
        }
        return MangaHomePage(result, pages.any { it.hasNextPage })
    }

    private fun select(item: MangaHomeItem, preferredSource: Long?): MangaHomeItem {
        val selected = item.sourceVariants.firstOrNull { it.manga.source == preferredSource } ?: return item
        return MangaHomeItem(
            selected.manga,
            (selected.presentation ?: MangaHomePresentation()).copy(
                catalogIds = item.presentation?.catalogIds.orEmpty(),
            ),
            selected.sourceTitle,
            (listOf(item.variant()) + item.sourceVariants).filterNot {
                it.manga.source == selected.manga.source &&
                    it.manga.url == selected.manga.url
            },
            item.key,
        )
    }
}
