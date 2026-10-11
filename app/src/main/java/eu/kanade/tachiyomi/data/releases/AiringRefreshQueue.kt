package eu.kanade.tachiyomi.data.releases

/** New local schedules get a complete first pass, without an endless retry chain for failures. */
internal object AiringRefreshQueue {
    const val BATCH_SIZE = 6

    data class Candidate(
        val entryId: Long,
        val cache: AiringCache,
        val hasUpcoming: Boolean,
        val scheduleDue: Boolean = false,
        val scheduleCold: Boolean = false,
    )

    fun batch(candidates: List<Candidate>, now: Long): List<Candidate> = candidates
        .filter { it.scheduleDue || AiringRefreshPolicy.shouldRefresh(it.cache, now, it.hasUpcoming) }
        .sortedWith(compareBy<Candidate> { if (it.scheduleCold) 0L else it.cache.attemptedAt }.thenBy { it.entryId })
        .take(BATCH_SIZE)

    fun hasColdBacklog(candidates: List<Candidate>, processed: Set<Long>): Boolean = candidates.any {
        it.entryId !in processed && (it.cache.attemptedAt == 0L || it.scheduleCold)
    }
}
