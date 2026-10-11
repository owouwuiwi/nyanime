package eu.kanade.tachiyomi.ui.player

/** Bounds exceptional timing snapshots; normal playback has no extra polling loop. */
internal class PlaybackDiagnosticGate {
    private var generation = -1L
    private var lastAt = Long.MIN_VALUE
    private var captures = 0

    @Synchronized
    fun accept(currentGeneration: Long, now: Long): Boolean {
        if (currentGeneration < generation) return false
        if (currentGeneration != generation) {
            generation = currentGeneration
            captures = 0
            lastAt = Long.MIN_VALUE
        }
        if (captures >= 32 || lastAt != Long.MIN_VALUE && now - lastAt < 2_000) return false
        captures++
        lastAt = now
        return true
    }
}
