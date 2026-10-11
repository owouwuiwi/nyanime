package it.lorelore.nyanime.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

@Serializable
internal data class DetailRefreshStamp(
    val revision: String,
    val attemptedAt: Long,
    val succeededAt: Long = 0,
    val failures: Int = 0,
)

internal interface DetailFreshnessStorage {
    fun read(key: String): DetailRefreshStamp?
    fun write(key: String, stamp: DetailRefreshStamp)
}

/** Only explicit detail opens enter this queue. No library enumeration or startup network work. */
internal class DetailRefreshCoordinator(
    private val storage: DetailFreshnessStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = Array(256) { Mutex() }
    private val sources = Array(64) { Semaphore(2) }

    suspend fun refresh(
        key: String,
        sourceKey: String,
        revision: String,
        force: Boolean = false,
        persistent: Boolean = true,
        current: () -> Boolean = { true },
        fetch: suspend () -> Boolean,
    ): Boolean = entries[key.hashCode() and 255].withLock {
        val before = if (persistent) storage.read(key) else null
        if (!force && !due(before, revision, clock())) return@withLock false
        sources[sourceKey.hashCode() and 63].withPermit {
            if (!current()) throw SourceReplacedException()
            try {
                val success = fetch()
                if (!current()) throw SourceReplacedException()
                if (persistent) {
                    val now = clock()
                    storage.write(
                        key,
                        DetailRefreshStamp(
                            revision,
                            now,
                            if (success) now else 0,
                            if (success) {
                                0
                            } else {
                                ((before?.takeIf { it.revision == revision }?.failures ?: 0) + 1)
                                    .coerceAtMost(5)
                            },
                        ),
                    )
                }
                success
            } catch (cancelled: CancellationException) {
                // An abandoned screen or replaced source neither poisons the cache nor marks it fresh.
                throw cancelled
            }
        }
    }

    companion object {
        const val SUCCESS_INTERVAL = 10 * 60_000L
        fun due(stamp: DetailRefreshStamp?, revision: String, now: Long): Boolean {
            if (stamp == null || stamp.revision != revision || stamp.attemptedAt > now) return true
            val wait = if (stamp.failures == 0 && stamp.succeededAt > 0) {
                SUCCESS_INTERVAL
            } else {
                (60_000L shl (stamp.failures - 1).coerceIn(0, 4)).coerceAtMost(15 * 60_000L)
            }
            return now - stamp.attemptedAt >= wait
        }
    }
}
