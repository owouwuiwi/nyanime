package nyanime.telegram.sync

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class SyncCheckpointChunk(
    val version: Int = 1,
    val checkpoint: String,
    val changes: List<SyncChange>,
) {
    fun validate() {
        require(version == 1 && changes.size in 1..1024)
        requireSyncId(checkpoint)
        changes.forEach(SyncChange::validate)
        require(changes.map { it.dot }.distinct().size == changes.size)
    }
}

@Serializable
data class SyncCheckpointPart(val id: String, val hash: String, val messageId: Long)

@Serializable
data class SyncCheckpointCommit(
    val version: Int = 1,
    val id: String,
    val space: String,
    val epoch: Long,
    val author: String,
    val throughMessage: Long,
    val parts: List<SyncCheckpointPart>,
    /** Optional signed capability; version-1 clients ignore this field and retain their wire format. */
    val maintenanceVersion: Int = 0,
) {
    fun validate() {
        require(version == 1 && epoch > 0 && throughMessage >= 0)
        require(maintenanceVersion >= 0)
        requireSyncId(id)
        requireSyncId(space)
        requireSyncId(author)
        require(parts.size <= 4096 && parts.map { it.id }.distinct().size == parts.size)
        parts.forEach {
            requireSyncId(it.id)
            require(it.messageId > 0 && it.hash.matches(Regex("[a-f0-9]{64}")))
        }
    }
}

@Serializable
data class SyncSignedCheckpoint(val payload: String, val signature: String)

object SyncCheckpoints {
    fun sign(commit: SyncCheckpointCommit, account: Long, device: SyncDeviceSecret): SyncSignedCheckpoint {
        commit.validate()
        require(commit.author == device.public.id)
        val payload = syncJson.encodeToString(commit)
        return SyncSignedCheckpoint(payload, SyncDeviceCrypto.sign(device, binding(account, payload)))
    }

    fun verify(
        signed: SyncSignedCheckpoint,
        account: Long,
        membership: SyncMembershipState,
    ): SyncCheckpointCommit {
        require(signed.payload.length <= 1024 * 1024)
        val commit = syncJson.decodeFromString<SyncCheckpointCommit>(signed.payload)
        commit.validate()
        require(commit.space == membership.space && commit.epoch == membership.epoch)
        val author = membership.devices[commit.author] ?: error("Checkpoint author is no longer authorized")
        require(SyncDeviceCrypto.verify(author, binding(account, signed.payload), signed.signature))
        return commit
    }

    fun verifyPart(
        part: SyncCheckpointPart,
        envelope: SyncEnvelope,
        checkpoint: String,
        clear: ByteArray,
    ): SyncCheckpointChunk {
        require(envelope.id == part.id && hash(syncJson.encodeToString(envelope)) == part.hash)
        return syncJson.decodeFromString<SyncCheckpointChunk>(clear.toString(Charsets.UTF_8)).also {
            it.validate()
            require(it.checkpoint == checkpoint)
        }
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun binding(account: Long, payload: String): ByteArray {
        require(account > 0)
        return "nyanime-sync-checkpoint|1|$account|$payload".toByteArray(Charsets.UTF_8)
    }
}
