package eu.kanade.tachiyomi.source

/** Optional extension capability. Only dates explicitly announced by the publisher belong here. */
interface ReleaseScheduleProvider {
    suspend fun getReleaseSchedule(entryUrl: String): List<ScheduledRelease>
}

data class ScheduledRelease(
    val number: Double,
    /** UTC epoch milliseconds, never an estimated date calculated from upload intervals. */
    val releaseAt: Long,
    val name: String = "",
)
