package eu.kanade.domain.ui

/** A monotonic, screen-local gesture. Nothing is persisted until all five activations succeed. */
class ClassicThemeUnlock {
    private var lastTap: Long? = null
    private var taps = 0

    fun tap(elapsedMillis: Long): Boolean {
        val last = lastTap
        if (last == null || elapsedMillis - last !in 0..2_000) taps = 0
        lastTap = elapsedMillis
        taps++
        if (taps < 5) return false
        reset()
        return true
    }

    fun reset() {
        lastTap = null
        taps = 0
    }
}
