package eu.kanade.tachiyomi.data.news

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Waiting for one publisher does not occupy another publisher's request slot. */
internal class NewsRequestGate(limit: Int = 3) {
    private val slots = Semaphore(limit)
    private val publishers = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> run(source: String, request: suspend () -> T): T =
        publishers.getOrPut(source) { Mutex() }.withLock {
            slots.withPermit { request() }
        }
}
