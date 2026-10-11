package eu.kanade.tachiyomi.data.download.anime.ultra

import java.util.concurrent.atomic.AtomicInteger

/** GPU conversion yields to the player, including picture-in-picture and shared playback. */
internal object UltraPlaybackGuard {
    private val players = AtomicInteger()
    val playerActive: Boolean get() = players.get() > 0
    fun enterPlayer() {
        players.incrementAndGet()
    }
    fun leavePlayer() {
        players.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
    class YieldToPlayer : Exception()
}
