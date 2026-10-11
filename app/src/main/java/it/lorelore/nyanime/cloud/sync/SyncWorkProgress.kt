package it.lorelore.nyanime.cloud.sync

internal enum class SyncWorkStage { PREPARING, RECEIVING, SENDING, APPLYING, CHECKPOINT, RESTORING }
internal enum class SyncWorkUnit { PAGES, PACKETS, PARTS, ITEMS }
internal data class SyncWorkProgress(
    val stage: SyncWorkStage,
    val completed: Long = 0,
    val total: Long? = null,
    val unit: SyncWorkUnit = SyncWorkUnit.ITEMS,
    val active: Boolean = true,
    val lastActivity: Long = 0,
) {
    init {
        require(completed >= 0 && (total == null || total >= completed))
    }
}
