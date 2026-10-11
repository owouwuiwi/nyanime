package eu.kanade.tachiyomi.data.extensionstore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One operation per Android package, even when several catalogue cards refer to it. */
class StoreInstallCoordinator(private val scope: CoroutineScope) {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    fun submit(packageName: String, operation: suspend () -> Unit): Job = synchronized(lock) {
        jobs[packageName]?.takeUnless { it.isCompleted }?.let { return@synchronized it }
        // Register before starting: an immediately completed flow cannot leave a ghost busy entry.
        val job = scope.launch(start = CoroutineStart.LAZY) { operation() }
        jobs[packageName] = job
        job.invokeOnCompletion {
            synchronized(lock) { if (jobs[packageName] === job) jobs.remove(packageName) }
        }
        job.start()
        job
    }

    fun isCurrent(packageName: String, job: Job): Boolean = synchronized(lock) { jobs[packageName] === job }

    fun cancel(packageName: String) {
        val job = synchronized(lock) { jobs.remove(packageName) }
        job?.cancel()
    }
}
