package eu.kanade.tachiyomi.ui.reader.model

/** Split pages belong to their original reader chapter, never to an index shared by another chapter. */
internal class SplitPageCache {
    private var entries = emptyList<InsertPage>()

    fun put(page: InsertPage) {
        entries = entries.filterNot { it.chapter === page.chapter && it.index == page.index } + page
    }

    fun forChapter(chapter: ReaderChapter): List<InsertPage> = entries.filter {
        it.chapter === chapter && chapter.pages?.getOrNull(it.index) === it.parent
    }

    fun retainAdjacent(previous: ReaderChapter?, next: ReaderChapter?) {
        entries = entries.filter { it.chapter === previous || it.chapter === next }
    }
}
