package eu.kanade.tachiyomi.data.releases

/** The monitor and title screen must agree on when a saved schedule can be reused. */
internal object AiringRefreshPolicy {
    fun shouldRefresh(cache: AiringCache, now: Long, hasUpcoming: Boolean, force: Boolean = false): Boolean {
        if (force) return true
        val sinceAttempt = now - cache.attemptedAt
        if (cache.attemptedAt > 0 && sinceAttempt in 0 until 5 * ReleasePolicy.MINUTE) return false
        // A failure never inherits the freshness of the previous successful request.
        if (cache.status != "AVAILABLE" && cache.status != "UNANNOUNCED") return true
        val sinceVerification = now - cache.verifiedAt
        val fresh = cache.verifiedAt > 0 && sinceVerification in 0 until 6 * ReleasePolicy.HOUR
        return !fresh || (!hasUpcoming && cache.status != "UNANNOUNCED")
    }
}
