package eu.kanade.tachiyomi.data.cloud.sync

import kotlinx.serialization.Serializable
import nyanime.telegram.sync.SyncArchiveBootstrap
import nyanime.telegram.sync.SyncCipher
import nyanime.telegram.sync.SyncDevicePublic
import nyanime.telegram.sync.SyncDeviceSecret
import nyanime.telegram.sync.SyncMembership
import nyanime.telegram.sync.SyncMembershipState
import nyanime.telegram.sync.SyncSignedControl
import nyanime.telegram.sync.SyncSignedRoot
import nyanime.telegram.sync.syncJson

/** The whole keyring is atomically wrapped by Android Keystore; it is never part of .nyabk. */
@Serializable
internal data class CloudSyncKeys(
    val version: Int = 1,
    val account: Long,
    val chat: Long,
    val root: SyncSignedRoot,
    val rootMessage: Long,
    val membership: SyncMembershipState,
    val history: Map<Long, SyncMembershipState>,
    val epochs: Map<Long, String>,
    val approvedRequests: Set<String> = emptySet(),
    val controls: Map<Long, SyncSignedControl> = emptyMap(),
) {
    val space get() = membership.space
    val epoch get() = membership.epoch

    fun key(epoch: Long = this.epoch): ByteArray =
        SyncCipher.decode(epochs[epoch] ?: error("Synchronization key is unavailable")).also { require(it.size == 32) }

    fun contains(device: SyncDevicePublic): Boolean = membership.devices[device.id] == device

    fun membershipAt(message: Long): SyncMembershipState = history.values
        .filter { it.messageId <= message }.maxByOrNull { it.messageId }
        ?: error("Membership history is incomplete")

    fun accept(control: SyncSignedControl, message: Long, device: SyncDeviceSecret): CloudSyncKeys {
        val (next, change) = SyncMembership.accept(membership, control, account, message)
        val nextKey = if (next.devices[device.public.id] == device.public) {
            SyncCipher.encode(SyncMembership.openKey(change, account, device))
        } else {
            null
        }
        return copy(
            membership = next,
            history = history + (next.epoch to next),
            epochs = if (nextKey != null) epochs + (next.epoch to nextKey) else epochs,
            controls = controls + (next.epoch to control),
        )
    }

    /** Keep signed authorization history independently of disposable Telegram message cursors. */
    fun recoveryControls(read: (SyncMembershipState) -> SyncSignedControl): Map<Long, SyncSignedControl> {
        val data = SyncArchiveBootstrap.verify(root, account)
        var verified = SyncArchiveBootstrap.initial(data, rootMessage)
        require(history[1] == verified && history.size.toLong() == epoch)
        val signed = history.toSortedMap().filterKeys { it > 1 }.mapValues { (number, member) ->
            val control = controls[number] ?: read(member)
            verified = SyncMembership.accept(verified, control, account, member.messageId).first
            require(verified == member)
            control
        }
        require(verified == membership)
        return signed
    }

    fun save(vault: CloudSyncVault) {
        validate()
        val clear = syncJson.encodeToString(this).toByteArray(Charsets.UTF_8)
        try {
            vault.write(space, clear)
        } finally {
            clear.fill(0)
        }
    }

    fun validate() {
        require(version == 1 && account > 0 && chat < 0 && rootMessage > 0)
        val rootData = SyncArchiveBootstrap.verify(root, account)
        require(rootData.space == space && membership.recovery == rootData.recovery)
        require(epoch > 0 && history[epoch] == membership)
        history.forEach { (number, state) ->
            require(number == state.epoch && state.space == space && state.recovery == rootData.recovery)
        }
        epochs.forEach { (number, value) ->
            require(number > 0 && number in history && SyncCipher.decode(value).size == 32)
        }
    }

    companion object {
        fun load(vault: CloudSyncVault, space: String): CloudSyncKeys? {
            val clear = vault.read(space) ?: return null
            return try {
                syncJson.decodeFromString<CloudSyncKeys>(clear.toString(Charsets.UTF_8)).also { it.validate() }
            } finally {
                clear.fill(0)
            }
        }

        fun first(
            account: Long,
            chat: Long,
            root: SyncSignedRoot,
            message: Long,
            device: SyncDeviceSecret,
        ): CloudSyncKeys {
            val data = SyncArchiveBootstrap.verify(root, account)
            val state = SyncArchiveBootstrap.initial(data, message)
            return CloudSyncKeys(
                account = account,
                chat = chat,
                root = root,
                rootMessage = message,
                membership = state,
                history = mapOf(1L to state),
                epochs = mapOf(1L to SyncCipher.encode(SyncArchiveBootstrap.openKey(data, account, device))),
            ).also { it.validate() }
        }
    }
}

@Serializable
internal data class CloudSyncPairingBundle(
    val version: Int = 1,
    val account: Long,
    val chat: Long,
    val root: SyncSignedRoot,
    val rootMessage: Long,
    val membership: SyncMembershipState,
    val key: String,
    val checkpointMessage: Long,
    val history: Map<Long, SyncMembershipState>,
    val epochs: Map<Long, String>,
    val controls: Map<Long, SyncSignedControl> = emptyMap(),
) {
    fun validate(device: SyncDevicePublic, account: Long) {
        require(version == 1 && this.account == account && account > 0 && chat < 0)
        require(rootMessage > 0 && checkpointMessage > membership.messageId)
        val data = SyncArchiveBootstrap.verify(root, account)
        require(membership.space == data.space && membership.recovery == data.recovery)
        require(membership.devices[device.id] == device)
        require(SyncCipher.decode(key).size == 32)
        require(epochs[membership.epoch] == key && history[membership.epoch] == membership)
        keys().validate()
    }

    fun keys() = CloudSyncKeys(
        account = account,
        chat = chat,
        root = root,
        rootMessage = rootMessage,
        membership = membership,
        history = history,
        epochs = epochs,
        controls = controls,
    )
}
