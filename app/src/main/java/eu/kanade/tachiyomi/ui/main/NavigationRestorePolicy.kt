package eu.kanade.tachiyomi.ui.main

/** Saved navigation contains class names and is independent of library/database migrations. */
internal object NavigationRestorePolicy {
    const val KEY = "nyanime_navigation_schema"

    // Search screen classes changed in 0.55; older snapshots may contain retired types.
    const val SCHEMA = 2

    fun canRestore(schema: Int?) = schema == SCHEMA
}
