package eu.kanade.tachiyomi.data.library.anime

/** One source check per recently viewed title, shared by its detail and Continue Watching cards. */
internal object AnimeForegroundRefreshGate {
    private const val SUCCESS_INTERVAL_MS = 10 * 60 * 1000L
    private const val FAILURE_RETRY_MS = 60 * 1000L
    private const val MAX_ENTRIES = 256

    private data class RefreshState(
        var lastAttemptMs: Long,
        var lastAttemptSucceeded: Boolean = false,
        var inFlight: Boolean = false,
    )

    private val states = LinkedHashMap<Long, RefreshState>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun tryBegin(animeId: Long, nowMs: Long): Boolean {
        val state = states[animeId]
        if (state != null) {
            if (state.inFlight) return false
            val interval = if (state.lastAttemptSucceeded) SUCCESS_INTERVAL_MS else FAILURE_RETRY_MS
            if (nowMs - state.lastAttemptMs < interval) return false
        }
        states[animeId] = (state ?: RefreshState(nowMs)).apply {
            lastAttemptMs = nowMs
            inFlight = true
        }
        trim()
        return true
    }

    @Synchronized
    fun finish(animeId: Long, nowMs: Long, successful: Boolean, cancelled: Boolean = false) {
        val state = states[animeId] ?: return
        if (cancelled) {
            states.remove(animeId)
        } else {
            state.inFlight = false
            if (successful) {
                state.lastAttemptMs = nowMs
                state.lastAttemptSucceeded = true
            } else {
                state.lastAttemptSucceeded = false
            }
        }
    }

    @Synchronized
    fun recordSuccess(animeId: Long, nowMs: Long) {
        val state = states[animeId] ?: RefreshState(nowMs)
        state.lastAttemptMs = nowMs
        state.lastAttemptSucceeded = true
        states[animeId] = state
        trim()
    }

    private fun trim() {
        while (states.size > MAX_ENTRIES) {
            val eldest = states.entries.firstOrNull { !it.value.inFlight } ?: break
            states.remove(eldest.key)
        }
    }
}
