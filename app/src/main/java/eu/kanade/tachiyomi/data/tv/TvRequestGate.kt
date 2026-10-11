package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Leases include queued callers, so a lock cannot be evicted while another request awaits it. */
internal class TvRequestGate {
    private data class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> run(key: String, block: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}
