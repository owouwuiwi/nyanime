package eu.kanade.tachiyomi.data.releases

/** Missing IDs are not catalog failures. Availability checks remain independent. */
data class AiringIssue(val entryId: Long, val title: String, val cover: Any?, val reason: Reason) {
    enum class Reason { PENDING, UNVERIFIED_ID, UNAVAILABLE }

    companion object {
        internal fun reason(cache: AiringCache?, hasId: Boolean): Reason? {
            if (!hasId) return null
            if (cache == null) return Reason.PENDING
            return when (cache.status) {
                "UNRESOLVED" -> if (cache.attemptedAt == 0L) Reason.PENDING else Reason.UNVERIFIED_ID
                "UNAVAILABLE" -> Reason.UNAVAILABLE
                else -> null
            }
        }
    }
}
