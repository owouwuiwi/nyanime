package eu.kanade.tachiyomi.data.track

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Copy lookup and optional chapter metadata finish independently, without reordering cards. */
internal class ContinuityProgress<T>(
    initial: List<T>,
    private val id: (T) -> Long,
    private val publish: suspend (List<T>, Set<Long>) -> Unit,
) {
    private val mutex = Mutex()
    private val choices = initial.associateByTo(linkedMapOf(), id)
    private val resolving = choices.keys.toMutableSet()

    suspend fun emit() = mutex.withLock {
        currentCoroutineContext().ensureActive()
        publish(choices.values.toList(), resolving.toSet())
    }

    suspend fun update(key: Long, lookupFinished: Boolean = false, transform: (T) -> T) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        choices[key]?.let { choices[key] = transform(it) }
        if (lookupFinished) resolving.remove(key)
        publish(choices.values.toList(), resolving.toSet())
    }

    suspend fun result(): List<T> = mutex.withLock { choices.values.toList() }
}

/** Shared across both directions; cancellation is never turned into a missing destination. */
internal object ContinuityRequests {
    private val permits = Semaphore(3)
    private val metadataPermits = Semaphore(2)

    suspend fun <T> source(block: suspend () -> T): T? = permits.withPermit {
        optional(20_000, block)
    }

    suspend fun <T> metadata(block: suspend () -> T): T? = metadataPermits.withPermit {
        optional(14_000, block)
    }

    suspend fun <T> optional(timeoutMillis: Long = 8_000, block: suspend () -> T): T? =
        withTimeoutOrNull(timeoutMillis) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
}
