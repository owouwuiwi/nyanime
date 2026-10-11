package nyanime.telegram.sync

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SyncMembershipState(
    val space: String,
    val epoch: Long,
    val control: String,
    val messageId: Long,
    val devices: Map<String, SyncDevicePublic>,
    val recovery: SyncDevicePublic? = null,
)

@Serializable
data class SyncMembershipChange(
    val version: Int = 1,
    val id: String,
    val space: String,
    val previous: String,
    val epoch: Long,
    val author: String,
    val devices: Map<String, SyncDevicePublic>,
    val keys: Map<String, String>,
)

/** Signed bytes are preserved exactly; verification does not reserialize untrusted JSON. */
@Serializable
data class SyncSignedControl(val payload: String, val signature: String)

object SyncMembership {
    /**
     * A sole-owner Telegram archive supplies a canonical server order for membership changes.
     * Concurrent changes must rebase on the first confirmed change; an old epoch cannot authorize
     * itself again. Deltas use separate causal reconciliation and do not depend on arrival order.
     */
    fun propose(
        current: SyncMembershipState,
        author: SyncDeviceSecret,
        devices: Collection<SyncDevicePublic>,
        account: Long,
        key: ByteArray,
    ): SyncSignedControl {
        require(current.devices[author.public.id] == author.public || current.recovery == author.public)
        require(key.size == 32 && devices.size in 1..127)
        val next = devices.associateBy { it.id }
        require(next.size == devices.size)
        next.values.forEach(SyncDevicePublic::validate)
        val id = UUID.randomUUID().toString()
        val epoch = Math.addExact(current.epoch, 1)
        val change = SyncMembershipChange(
            id = id,
            space = current.space,
            previous = current.control,
            epoch = epoch,
            author = author.public.id,
            devices = next,
            keys = (next + listOfNotNull(current.recovery).associateBy { it.id }).mapValues { (_, recipient) ->
                SyncDeviceCrypto.seal(recipient, key, grantBinding(account, current.space, epoch, id, recipient.id))
            },
        )
        val payload = syncJson.encodeToString(change)
        return SyncSignedControl(payload, SyncDeviceCrypto.sign(author, signingBinding(account, payload)))
    }

    fun accept(
        current: SyncMembershipState,
        signed: SyncSignedControl,
        account: Long,
        messageId: Long,
    ): Pair<SyncMembershipState, SyncMembershipChange> {
        require(signed.payload.length <= 1024 * 1024 && messageId > current.messageId)
        val change = syncJson.decodeFromString<SyncMembershipChange>(signed.payload)
        require(change.version == 1 && change.space == current.space)
        require(change.previous == current.control && change.epoch == current.epoch + 1)
        requireSyncId(change.id)
        val author = current.devices[change.author]
            ?: current.recovery?.takeIf { it.id == change.author }
            ?: error("Device no longer authorized")
        require(SyncDeviceCrypto.verify(author, signingBinding(account, signed.payload), signed.signature))
        require(change.devices.size in 1..127)
        require(current.recovery?.id !in change.devices.keys)
        require(change.keys.keys == change.devices.keys + listOfNotNull(current.recovery?.id))
        change.devices.forEach { (id, device) ->
            require(id == device.id)
            device.validate()
        }
        return SyncMembershipState(
            current.space,
            change.epoch,
            change.id,
            messageId,
            change.devices,
            current.recovery,
        ) to change
    }

    fun openKey(
        change: SyncMembershipChange,
        account: Long,
        device: SyncDeviceSecret,
        recovery: SyncDevicePublic? = null,
    ): ByteArray {
        require(change.devices[device.public.id] == device.public || recovery == device.public)
        val wrapped = change.keys[device.public.id] ?: error("Device removed")
        return SyncDeviceCrypto.open(
            device,
            wrapped,
            grantBinding(account, change.space, change.epoch, change.id, device.public.id),
        ).also { require(it.size == 32) }
    }

    private fun signingBinding(account: Long, payload: String): ByteArray {
        require(account > 0)
        return "nyanime-sync-membership|1|$account|$payload".toByteArray(Charsets.UTF_8)
    }

    private fun grantBinding(account: Long, space: String, epoch: Long, id: String, recipient: String): ByteArray =
        "nyanime-sync-epoch|1|$account|$space|$epoch|$id|$recipient".toByteArray(Charsets.UTF_8)
}

@Serializable
data class SyncSignedBatch(val payload: String, val signature: String)

object SyncBatchSignature {
    fun sign(batch: SyncBatch, account: Long, epoch: Long, device: SyncDeviceSecret): SyncSignedBatch {
        batch.validate()
        require(batch.device == device.public.id)
        val payload = syncJson.encodeToString(batch)
        return SyncSignedBatch(payload, SyncDeviceCrypto.sign(device, binding(account, epoch, payload)))
    }

    fun verify(signed: SyncSignedBatch, account: Long, epoch: Long, members: SyncMembershipState): SyncBatch {
        require(signed.payload.length <= SyncEnvelope.MAX_CLEAR_SIZE && epoch == members.epoch)
        val batch = syncJson.decodeFromString<SyncBatch>(signed.payload)
        batch.validate()
        require(batch.space == members.space)
        val device = members.devices[batch.device] ?: error("Device no longer authorized")
        require(SyncDeviceCrypto.verify(device, binding(account, epoch, signed.payload), signed.signature))
        return batch
    }

    private fun binding(account: Long, epoch: Long, payload: String): ByteArray {
        require(account > 0 && epoch > 0)
        return "nyanime-sync-batch|1|$account|$epoch|$payload".toByteArray(Charsets.UTF_8)
    }
}
