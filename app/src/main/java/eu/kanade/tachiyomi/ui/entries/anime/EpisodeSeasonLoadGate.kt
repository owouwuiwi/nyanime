package eu.kanade.tachiyomi.ui.entries.anime

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Expanded sections share one source lane across simultaneously retained detail screens. */
internal object EpisodeSeasonLoadGate {
    private val gates = ConcurrentHashMap<Long, Mutex>()
    fun forSource(id: Long): Mutex = gates.getOrPut(id) { Mutex() }
}
