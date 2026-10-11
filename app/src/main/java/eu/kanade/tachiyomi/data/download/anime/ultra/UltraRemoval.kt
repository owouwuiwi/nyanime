package eu.kanade.tachiyomi.data.download.anime.ultra

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Once confirmed, finish cleanup even if its screen closes. Never report success before storage agrees. */
internal object UltraRemoval {
    suspend fun <T> batch(
        items: List<T>,
        remove: suspend (T) -> Unit,
        onProgress: (Int) -> Unit,
    ): List<Pair<T, Exception>> = withContext(NonCancellable) {
        val failures = mutableListOf<Pair<T, Exception>>()
        items.forEachIndexed { index, item ->
            try {
                remove(item)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += item to error
            }
            onProgress(index + 1)
        }
        failures
    }

    suspend fun run(
        stopWriter: suspend () -> Unit,
        removeFiles: suspend () -> Unit,
        removeTemporary: suspend () -> Unit,
        recordCompletion: suspend () -> Unit,
    ) = withContext(NonCancellable) {
        stopWriter()
        removeFiles()
        removeTemporary()
        recordCompletion()
    }
}
