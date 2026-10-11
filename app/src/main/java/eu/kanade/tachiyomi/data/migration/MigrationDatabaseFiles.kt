package eu.kanade.tachiyomi.data.migration

/** SQLite journals belong to their database, including journals absent from the incoming snapshot. */
internal object MigrationDatabaseFiles {
    private val suffixes = listOf("-wal", "-shm", "-journal")

    fun transactionPaths(paths: Set<String>): Set<String> = buildSet {
        addAll(paths)
        paths.filter { it.startsWith("databases/") && it.split('/').size == 2 }.forEach { path ->
            val base = suffixes.firstOrNull(path::endsWith)?.let(path::removeSuffix) ?: path
            add(base)
            suffixes.forEach { add(base + it) }
        }
    }
}
