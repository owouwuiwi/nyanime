package it.lorelore.nyanime.novel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Accepted writes and publication releases have one explicit lifetime, independent of an Activity.
 */
internal class NovelSessionTasks(private val onFailure: (Exception) -> Unit) {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.IO)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val gate = Any()
    private var closing = false
    private val releases = mutableSetOf<Job>()
    private val writer = scope.launch {
        for (operation in queue) {
            try {
                operation()
            } catch (cancel: CancellationException) {
                // An individual accepted write cannot cancel unrelated accepted writes.
                if (!lifetime.isActive) throw cancel
                onFailure(cancel)
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }
    }

    fun commit(operation: suspend () -> Unit) =
        synchronized(gate) {
            check(!closing && queue.trySend(operation).isSuccess)
        }

    fun release(operation: suspend () -> Unit) =
        synchronized(gate) {
            check(!closing)
            val job = scope.launch {
                try {
                    operation()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    onFailure(failure)
                }
            }
            releases += job
            job.invokeOnCompletion { synchronized(gate) { releases -= job } }
        }

    /**
     * The owner has already cancelled/joined all consumers before calling this. No timer is used.
     */
    suspend fun drain() {
        val pending =
            synchronized(gate) {
                closing = true
                queue.close()
                releases.toList()
            }
        writer.join()
        pending.forEach { it.join() }
        lifetime.cancelAndJoin()
    }
}
