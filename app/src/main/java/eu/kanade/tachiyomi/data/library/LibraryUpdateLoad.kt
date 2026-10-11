package eu.kanade.tachiyomi.data.library

/** Counts queued checks, including individual seasons, not app updates or Home page reads. */
data class LibraryUpdateLoad(val source: Long, val count: Int) {
    companion object {
        fun warning(sourceIds: List<Long>, unmetered: (Long) -> Boolean): LibraryUpdateLoad? =
            sourceIds.groupingBy { it }.eachCount()
                .filterKeys { !unmetered(it) }
                .maxByOrNull { it.value }
                ?.takeIf { it.value > 60 }
                ?.let { LibraryUpdateLoad(it.key, it.value) }
    }
}
