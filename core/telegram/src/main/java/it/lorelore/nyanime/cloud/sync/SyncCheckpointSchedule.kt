package it.lorelore.nyanime.cloud.sync

/** A burst drains as ordinary deltas; it is not a reason to upload the entire library again. */
object SyncCheckpointSchedule {
    const val INTERVAL_SECONDS = 86_400L
    fun due(now: Long, lastComplete: Long, pending: Long): Boolean =
        pending > 0 && now >= lastComplete && now - lastComplete >= INTERVAL_SECONDS
}
