package it.lorelore.nyanime.presentation.library

/** Presentation-only predicates: never fetch titles or change persistent native filters. */
internal enum class LibraryQuickFilter {
    ALL,
    CONTINUE,
    NEWS,
    ;

    fun includes(unviewed: Long, completed: Long, lastAccess: Long, releases: Int): Boolean = when (this) {
        ALL -> true
        CONTINUE -> unviewed > 0 && (completed > 0 || lastAccess > 0)
        NEWS -> releases > 0
    }
}
