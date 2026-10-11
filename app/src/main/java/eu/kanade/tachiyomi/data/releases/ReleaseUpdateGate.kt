package eu.kanade.tachiyomi.data.releases

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialize the whole fetch-and-commit operation, not just its network request. */
object ReleaseUpdateGate {
    private val locks = Array(256) { Mutex() }
    suspend fun <T> withEntry(medium: ReleaseMedium, id: Long, block: suspend () -> T): T =
        locks[(31 * medium.ordinal + id.hashCode()) and 255].withLock { block() }
}
