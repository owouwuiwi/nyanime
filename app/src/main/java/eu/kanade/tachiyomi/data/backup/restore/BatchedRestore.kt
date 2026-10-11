package eu.kanade.tachiyomi.data.backup.restore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A failed transaction is retried entry by entry, preserving healthy entries and reporting the bad ones. */
internal suspend fun <T> restoreInBatches(
    entries: List<T>,
    batchSize: Int = 100,
    restoreBatch: suspend (List<T>) -> Unit,
    restoreEntry: suspend (T) -> Unit,
    onError: (T, Exception) -> Unit,
    onBatchComplete: suspend (List<T>) -> Unit,
) {
    require(batchSize > 0)
    for (chunk in entries.chunked(batchSize)) {
        currentCoroutineContext().ensureActive()
        val restored = try {
            restoreBatch(chunk)
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            false
        }
        if (!restored) {
            for (entry in chunk) {
                currentCoroutineContext().ensureActive()
                try {
                    restoreEntry(entry)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    currentCoroutineContext().ensureActive()
                    onError(entry, error)
                }
            }
        }
        onBatchComplete(chunk)
    }
}
