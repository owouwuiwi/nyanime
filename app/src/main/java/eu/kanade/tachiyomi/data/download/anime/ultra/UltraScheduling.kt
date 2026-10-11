package eu.kanade.tachiyomi.data.download.anime.ultra

/** A resource wait is a fresh delayed check, never an ever-growing WorkManager failure backoff. */
internal object UltraScheduling {
    const val POLICY_TAG = "ultra-admission-v2"
    const val RECHECK_SECONDS = 30L

    fun needsRefresh(task: UltraTask, workId: String, tags: Set<String>, running: Boolean, finished: Boolean) =
        task.active && task.workId == workId && !running && !finished && POLICY_TAG !in tags

    /** Caller serializes this with pause, resume and deletion, and masks cancellation for this short handoff. */
    suspend fun handOff(
        store: UltraTaskStore,
        key: String,
        workerId: String,
        nextId: String,
        schedule: suspend () -> Unit,
    ) {
        val previous = store.tasks.value[key]?.takeIf { it.active && it.workId == workerId } ?: return
        store.updateWorker(key, workerId) {
            // The task flow can emit before WorkManager exposes the new request to observers.
            it.copy(workId = nextId, phase = UltraPhase.WAITING, updatedAt = System.currentTimeMillis())
        }
        try {
            schedule()
        } catch (error: Exception) {
            // Leave the current attempt retryable if Android could not persist the successor.
            store.updateWorker(key, nextId) { previous }
            throw error
        }
    }
}
