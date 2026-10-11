package nyanime.tv.api

/** Retry state belongs to one selection generation. A stale retry can never change a new channel. */
class TvRecovery {
    private var selection = 0L
    private var attempt = 0
    private var lastReadyAt = 0L

    fun select(generation: Long) {
        require(generation > 0)
        selection = generation
        attempt = 0
        lastReadyAt = 0L
    }

    fun ready(generation: Long, now: Long) {
        if (generation == selection) lastReadyAt = now
    }

    /** Buffering/pausing is not stable playback and must not replenish the retry budget. */
    fun interrupted(generation: Long) {
        if (generation == selection) lastReadyAt = 0L
    }

    fun retry(generation: Long, now: Long, userPaused: Boolean): Long? {
        if (generation != selection || userPaused) return null
        if (lastReadyAt > 0L && now - lastReadyAt >= 30_000) attempt = 0
        lastReadyAt = 0L
        if (attempt >= 6) return null
        return DELAYS[attempt++]
    }

    val attempts: Int get() = attempt

    companion object {
        private val DELAYS = longArrayOf(1_000, 2_000, 4_000, 8_000, 12_000, 20_000)
    }
}
