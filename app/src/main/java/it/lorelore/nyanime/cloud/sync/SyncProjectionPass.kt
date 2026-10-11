package it.lorelore.nyanime.cloud.sync

/** One local pass spans all database pages; unchanged unresolved rows are retried quietly. */
internal class SyncProjectionPass {
    var cursor: String = ""
        private set
    private var running = false
    private var generation: String? = null
    private var completedGeneration: String? = null
    private var checked = 0L
    private var total = 0L
    private var retryAt = 0L
    private var visible = false

    fun begin(inputGeneration: String, pending: Long, nowMillis: Long, force: Boolean = false): Boolean {
        require(pending >= 0 && nowMillis >= 0)
        if (running && generation == inputGeneration && !force) return true
        if (!force && !running && generation == inputGeneration && nowMillis < retryAt) return false
        generation = inputGeneration
        cursor = ""
        checked = 0
        total = pending
        visible = force || completedGeneration != inputGeneration
        running = pending > 0
        if (!running) retryAt = nowMillis + RETRY_MILLIS
        return running
    }

    fun checked(key: String, nowSeconds: Long, applied: Boolean): SyncWorkProgress? {
        check(running)
        cursor = key
        checked++
        total = maxOf(total, checked)
        visible = visible || applied
        return progress(nowSeconds, active = true)
    }

    fun finish(nowMillis: Long, nowSeconds: Long): SyncWorkProgress? {
        running = false
        cursor = ""
        retryAt = nowMillis + RETRY_MILLIS
        completedGeneration = generation
        // Only the rows actually visited belong to this completed pass.
        total = checked
        return progress(nowSeconds, active = false)
    }

    private fun progress(nowSeconds: Long, active: Boolean): SyncWorkProgress? =
        if (visible && checked > 0) {
            SyncWorkProgress(SyncWorkStage.APPLYING, checked, total, active = active, lastActivity = nowSeconds)
        } else {
            null
        }

    companion object {
        private const val RETRY_MILLIS = 30_000L
    }
}
