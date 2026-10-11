package eu.kanade.tachiyomi.data.migration

/** A killed exporter cannot block launch for ten minutes; clock changes expire the short lease. */
internal object MigrationLease {
    const val DURATION = 10_000L
    fun active(wallDeadline: Long, elapsedDeadline: Long, wallNow: Long, elapsedNow: Long): Boolean =
        wallDeadline > wallNow &&
            wallDeadline <= wallNow + DURATION &&
            elapsedDeadline > elapsedNow &&
            elapsedDeadline <= elapsedNow + DURATION
}
