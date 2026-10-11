package eu.kanade.tachiyomi.ui.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** A new query invalidates every old result, including non-cooperative extension calls. */
class SourceSearchRunner<K>(
    private val scope: CoroutineScope,
    concurrency: Int = 5,
    private val timeoutMillis: Long = 30_000,
    sharedRequests: Semaphore? = null,
) {
    private val permits = sharedRequests ?: Semaphore(concurrency)
    private var generation = 0L
    private val jobs = mutableMapOf<K, Job>()

    @Synchronized
    fun reset() {
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    @Synchronized
    fun <T> submit(key: K, fetch: suspend () -> T, publish: (Result<T>) -> Unit) {
        jobs.remove(key)?.cancel()
        val ticket = generation
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                Result.success(permits.withPermit { withTimeout(timeoutMillis) { fetch() } })
            } catch (e: TimeoutCancellationException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            synchronized(this@SourceSearchRunner) {
                if (ticket == generation && jobs[key] === job && job.isActive) {
                    publish(result)
                    jobs.remove(key)
                }
            }
        }
        jobs[key] = job
        job.start()
    }
}
