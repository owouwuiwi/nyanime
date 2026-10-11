package it.lorelore.nyanime.attention.core

/** Optional counters are unknown when absent. Only the camera workload is ever reduced. */
class AttentionLoadGuard {
    private var at = -1L
    private var drops: Long? = null
    private var overloaded = 0
    var intervalMs: Long = 200
        private set
    var suspended = false
        private set

    fun sample(now: Long, frame: AttentionPlayback) {
        if (at >= 0 && now - at < 1_000) return
        val previous = drops
        drops = frame.renderDrops
        at = now
        if (!frame.playing ||
            frame.buffering ||
            previous == null ||
            frame.renderDrops == null ||
            frame.renderDrops < previous
        ) {
            overloaded = 0
            return
        }
        if (frame.renderDrops > previous) overloaded++ else overloaded = 0
        if (overloaded >= 2) intervalMs = 500
        if (overloaded >= 4) suspended = true
    }
}
