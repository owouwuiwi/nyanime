package eu.kanade.tachiyomi.data.releases

enum class ReleaseMedium { ANIME, MANGA }

enum class FollowMode { AUTO, FOLLOW, IGNORE }

data class ReleaseSubscription(
    val mode: FollowMode = FollowMode.AUTO,
    val availability: Boolean = true,
    val reminder: Boolean = true,
)

data class ReleaseCheckState(
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val nextCheck: Long = 0,
    val failures: Int = 0,
    val metadataAt: Long = 0,
)

/** All values are durations, not predicted publication dates. */
object ReleasePolicy {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    fun interval(now: Long, airingAt: Long?, missingEpisode: Boolean, completed: Boolean): Long = when {
        airingAt != null && now in (airingAt - 15 * MINUTE)..(airingAt + 6 * HOUR) -> 15 * MINUTE
        airingAt != null && missingEpisode && now in (airingAt + 6 * HOUR)..(airingAt + 2 * DAY) -> HOUR
        completed -> 7 * DAY
        airingAt != null && airingAt > now -> minOf(6 * HOUR, maxOf(15 * MINUTE, airingAt - 15 * MINUTE - now))
        else -> 6 * HOUR
    }

    fun retryDelay(failures: Int): Long = when (failures) {
        0, 1 -> MINUTE
        2 -> 5 * MINUTE
        3 -> 15 * MINUTE
        else -> HOUR
    }

    fun isDue(state: ReleaseCheckState, now: Long, foreground: Boolean = false): Boolean =
        if (state.failures > 0 && now < state.nextCheck) {
            false
        } else if (foreground) {
            now >= state.nextCheck || now - state.lastSuccess >= 10 * MINUTE
        } else {
            now >= state.nextCheck
        }
}
