package eu.kanade.tachiyomi.ui.main

/** Saved navigation contains class names and is independent of library/database migrations. */
internal object NavigationRestorePolicy {
    const val KEY = "nyanime_navigation_schema"
    const val SCHEMA = 1

    fun canRestore(schema: Int?) = schema == SCHEMA
}
