package eu.kanade.tachiyomi.ui.player

/** Coalesces MPV metadata bursts before performing any native property reads on the UI thread. */
class Anime4KMediaRefresh(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val refresh: () -> Unit,
) {
    private var generation = 0L
    private var pending = false

    fun request() {
        if (pending) return
        pending = true
        val requestGeneration = generation
        schedule(1000L) {
            if (generation != requestGeneration) return@schedule
            pending = false
            refresh()
        }
    }

    fun reset() {
        generation++
        pending = false
    }
}
