package eu.kanade.tachiyomi.data.cloud.sync

import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncSignedControl
import nyanime.telegram.sync.syncJson

/** Replacement packets supplement missing local copies; the complete original signed chain remains authoritative. */
internal object CloudSyncRecoveryControls {
    fun restore(
        keys: CloudSyncKeys,
        retained: (Long) -> SyncDelivery?,
        replacement: Map<String, SyncDelivery>,
    ): Map<Long, SyncSignedControl> = keys.recoveryControls { member ->
        val delivery = retained(member.messageId) ?: replacement[member.control]
            ?: error("Signed authorization history is unavailable")
        val packet = delivery.packet
        require(packet.kind == "control" && packet.id == member.control && packet.space == keys.space)
        syncJson.decodeFromString<SyncSignedControl>(packet.payload)
    }
}
