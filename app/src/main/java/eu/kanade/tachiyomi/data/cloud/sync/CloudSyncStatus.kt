package eu.kanade.tachiyomi.data.cloud.sync

internal enum class CloudSyncPhase {
    OFF,
    CONNECTING,
    PREPARING,
    RECEIVING,
    SENDING,
    CURRENT,
    PAUSED,
    NETWORK,
    RATE_LIMIT,
    WAITING_IDLE,
    APPLYING,
    WAITING_CONTENT,
    LOCKED,
    OTHER_ACCOUNT,
    REMOVED,
    ERROR,
}

internal data class CloudSyncStatus(
    val phase: CloudSyncPhase = CloudSyncPhase.OFF,
    val pending: Long = 0,
    val projections: Long = 0,
    val lastConfirmed: Long = 0,
    val lastVerified: Long = 0,
    val retryAt: Long = 0,
    val devices: List<CloudSyncDevice> = emptyList(),
    val maintenance: it.lorelore.nyanime.cloud.sync.SyncMaintenanceStatus =
        it.lorelore.nyanime.cloud.sync.SyncMaintenanceStatus(),
) {
    /** Checking again is not a change of data state. The action's spinner shows a manual check. */
    fun receiving(explicit: Boolean, captured: Long): CloudSyncPhase = when {
        captured > 0 || pending > 0 -> CloudSyncPhase.RECEIVING
        lastVerified > 0 && phase in setOf(CloudSyncPhase.CURRENT, CloudSyncPhase.WAITING_CONTENT) -> phase
        explicit || projections > 0 -> CloudSyncPhase.RECEIVING
        phase in setOf(
            CloudSyncPhase.CURRENT,
            CloudSyncPhase.NETWORK,
            CloudSyncPhase.RATE_LIMIT,
            CloudSyncPhase.ERROR,
        ) -> phase
        else -> CloudSyncPhase.RECEIVING
    }
}

internal data class CloudSyncDevice(val id: String, val name: String, val thisDevice: Boolean)
