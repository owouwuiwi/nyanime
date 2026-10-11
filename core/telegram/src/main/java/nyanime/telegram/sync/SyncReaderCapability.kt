package nyanime.telegram.sync

import kotlinx.serialization.Serializable
import java.util.UUID

/** Portable proof of reader support. It neither contains nor grants authority over application data. */
@Serializable
data class SyncReaderCapability(
    val version: Int = 1,
    val id: String,
    val space: String,
    val epoch: Long,
    val control: String,
    val author: String,
    val maintenanceVersion: Int = 1,
    val packingVersion: Int = 1,
) {
    fun validate() {
        require(version == 1 && epoch > 0 && maintenanceVersion >= 1 && packingVersion >= 1)
        listOf(id, space, control, author).forEach(::requireSyncId)
    }
}

@Serializable
data class SyncSignedReaderCapability(val payload: String, val signature: String)

object SyncReaderCapabilities {
    fun sign(account: Long, membership: SyncMembershipState, device: SyncDeviceSecret): SyncSignedReaderCapability {
        require(membership.devices[device.public.id] == device.public)
        val capability = SyncReaderCapability(
            id = UUID.randomUUID().toString(),
            space = membership.space,
            epoch = membership.epoch,
            control = membership.control,
            author = device.public.id,
        ).also { it.validate() }
        val payload = syncJson.encodeToString(capability)
        return SyncSignedReaderCapability(payload, SyncDeviceCrypto.sign(device, binding(account, payload)))
    }

    fun verify(
        signed: SyncSignedReaderCapability,
        account: Long,
        membership: SyncMembershipState,
    ): SyncReaderCapability {
        require(signed.payload.length in 1..8192)
        val capability = syncJson.decodeFromString<SyncReaderCapability>(signed.payload).also { it.validate() }
        require(capability.space == membership.space && capability.epoch == membership.epoch)
        require(capability.control == membership.control)
        val author = membership.devices[capability.author] ?: error("Reader is no longer authorized")
        require(SyncDeviceCrypto.verify(author, binding(account, signed.payload), signed.signature))
        return capability
    }

    private fun binding(account: Long, payload: String): ByteArray {
        require(account > 0)
        return "nyanime-sync-reader|1|$account|$payload".toByteArray(Charsets.UTF_8)
    }
}
