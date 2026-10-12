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
) {
    /** Keep routine empty checks quiet; a manual check or actual queued work remains visible. */
    fun receiving(explicit: Boolean, captured: Long): CloudSyncPhase = when {
        explicit || captured > 0 || pending > 0 || projections > 0 -> CloudSyncPhase.RECEIVING
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
