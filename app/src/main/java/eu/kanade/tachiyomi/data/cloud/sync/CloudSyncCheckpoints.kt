package eu.kanade.tachiyomi.data.cloud.sync

import kotlinx.serialization.Serializable
import nyanime.telegram.sync.SyncCheckpointChunk
import nyanime.telegram.sync.SyncCheckpointCommit
import nyanime.telegram.sync.SyncCheckpointPart
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncCipher
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncDeviceSecret
import nyanime.telegram.sync.SyncEnvelope
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncSignedCheckpoint
import nyanime.telegram.sync.syncJson
import java.util.UUID

/** A checkpoint becomes authoritative only AFTER all encrypted parts and its commit are confirmed. */
internal class CloudSyncCheckpoints(
    private val store: CloudSyncStore,
    private val files: CloudSyncPacketFiles,
    private val transport: TelegramSyncTransport,
    private val device: SyncDeviceSecret,
) {
    fun prepare(keys: CloudSyncKeys): Prepared {
        store.state("checkpoint-pending")?.takeIf { it.isNotEmpty() }?.let {
            val previous = syncJson.decodeFromString<Prepared>(it)
            if (previous.epoch == keys.epoch &&
                previous.exclusions == store.state("exclusions").orEmpty()
            ) {
                return previous
            }
            // Operation dots are in the database; abandoning an old encrypted snapshot loses no edits.
            previous.parts.forEach { part -> files.removePrepared(part.id) }
            files.removePrepared(previous.id)
        }
        val id = UUID.randomUUID().toString()
        val parts = mutableListOf<SyncCheckpointPart>()
        val key = keys.key()
        val sequence = try {
            store.snapshot { changes ->
                val chunk = SyncCheckpointChunk(checkpoint = id, changes = changes).also { it.validate() }
                val envelope = SyncCipher.encrypt(
                    keys.account,
                    keys.space,
                    keys.epoch,
                    UUID.randomUUID().toString(),
                    key,
                    syncJson.encodeToString(chunk).toByteArray(Charsets.UTF_8),
                )
                val data = syncJson.encodeToString(envelope)
                files.prepare(SyncPacket(kind = "checkpoint", id = envelope.id, space = keys.space, payload = data))
                parts += SyncCheckpointPart(envelope.id, SyncCheckpoints.hash(data), 0)
            }
        } finally {
            key.fill(0)
        }
        return Prepared(
            id,
            keys.epoch,
            sequence,
            store.state("processed")?.toLong() ?: 0,
            parts,
            exclusions = store.state("exclusions").orEmpty(),
        ).also(::save)
    }

    suspend fun publish(keys: CloudSyncKeys): SyncDelivery {
        check(keys.contains(device.public))
        var plan = prepare(keys)
        plan.parts.forEachIndexed { index, part ->
            if (part.messageId == 0L) {
                val receipt = send(files.prepared(part.id))
                plan = plan.copy(
                    parts = plan.parts.toMutableList().also {
                        it[index] = part.copy(messageId = receipt.receipt.messageId)
                    },
                )
                save(plan)
            }
        }
        if (!plan.commitPrepared) {
            val commit = SyncCheckpointCommit(
                id = plan.id,
                space = keys.space,
                epoch = keys.epoch,
                author = device.public.id,
                throughMessage = plan.throughMessage,
                parts = plan.parts,
            )
            val signed = SyncCheckpoints.sign(commit, keys.account, device)
            val key = keys.key()
            val envelope = try {
                SyncCipher.encrypt(
                    keys.account,
                    keys.space,
                    keys.epoch,
                    plan.id,
                    key,
                    syncJson.encodeToString(signed).toByteArray(Charsets.UTF_8),
                )
            } finally {
                key.fill(0)
            }
            files.prepare(
                SyncPacket(
                    kind = "commit",
                    id = plan.id,
                    space = keys.space,
                    payload = syncJson.encodeToString(envelope),
                ),
            )
            plan = plan.copy(commitPrepared = true)
            save(plan)
        }
        val delivery = send(files.prepared(plan.id))
        store.checkpointConfirmed(
            plan.sequence,
            plan.id,
            delivery.receipt.messageId,
            delivery.receipt.serverDate,
        )
        store.state("checkpoint-pending", "")
        store.state("checkpoint-message", delivery.receipt.messageId.toString())
        store.state("compact-through", plan.throughMessage.toString())
        plan.parts.forEach { files.removePrepared(it.id) }
        files.removePrepared(plan.id)
        return delivery
    }

    /** The caller must finish a full history reconciliation before retrying an uncertain send. */
    private suspend fun send(packet: SyncPacket): SyncDelivery {
        val queueKey = "checkpoint-send/${packet.id}"
        store.messageFor(packet.id)?.let { message ->
            return files.delivery(message).also {
                require(it.packet == packet) { "Checkpoint identity reused" }
            }
        }
        val queued: (Long) -> Unit = { store.state(queueKey, it.toString()) }
        val temporary = store.state(queueKey)?.toLongOrNull() ?: 0L
        val receipt = transport.resume(packet, temporary, queued) ?: transport.send(packet, queued)
        return SyncDelivery(packet, receipt).also {
            files.stage(it)
            store.staged(it)
            store.state(queueKey, "")
        }
    }

    suspend fun restore(delivery: SyncDelivery, keys: CloudSyncKeys): Long {
        require(delivery.packet.kind == "commit")
        val envelope = syncJson.decodeFromString<SyncEnvelope>(delivery.packet.payload)
        val key = keys.key(envelope.epoch)
        try {
            val signed = syncJson.decodeFromString<SyncSignedCheckpoint>(
                SyncCipher.decrypt(keys.account, keys.space, key, envelope).toString(Charsets.UTF_8),
            )
            val members = keys.history[envelope.epoch] ?: error("Unknown checkpoint membership")
            val commit = SyncCheckpoints.verify(signed, keys.account, members)
            if (keys.membershipAt(delivery.receipt.messageId).devices[commit.author] !=
                members.devices[commit.author]
            ) {
                return 0
            }
            require(commit.id == envelope.id && envelope.id == delivery.packet.id)
            require(commit.throughMessage < delivery.receipt.messageId)
            commit.parts.forEach { part ->
                require(part.messageId < delivery.receipt.messageId)
                val found = store.messageFor(part.id)
                if (found == null) {
                    val received = transport.message(part.messageId)
                    require(received.packet.id == part.id)
                    files.stage(received)
                    store.staged(received)
                }
                chunk(part, commit, keys, key) // Validate the complete set before any database projection.
            }
            store.restoreSnapshot(
                commit.id,
                delivery.receipt.messageId,
                commit.parts.asSequence().map { chunk(it, commit, keys, key).changes },
            )
            store.state("checkpoint-message", delivery.receipt.messageId.toString())
            store.state("compact-through", commit.throughMessage.toString())
            return commit.throughMessage
        } finally {
            key.fill(0)
        }
    }

    private fun chunk(
        part: SyncCheckpointPart,
        commit: SyncCheckpointCommit,
        keys: CloudSyncKeys,
        key: ByteArray,
    ): SyncCheckpointChunk {
        val received = files.delivery(requireNotNull(store.messageFor(part.id)))
        require(received.packet.kind == "checkpoint")
        val envelope = syncJson.decodeFromString<SyncEnvelope>(received.packet.payload)
        require(envelope.epoch == commit.epoch && envelope.space == commit.space)
        return SyncCheckpoints.verifyPart(
            part,
            envelope,
            commit.id,
            SyncCipher.decrypt(keys.account, keys.space, key, envelope),
        )
    }

    private fun save(prepared: Prepared) = store.state("checkpoint-pending", syncJson.encodeToString(prepared))

    @Serializable
    data class Prepared(
        val id: String,
        val epoch: Long,
        val sequence: Long,
        val throughMessage: Long,
        val parts: List<SyncCheckpointPart>,
        val commitPrepared: Boolean = false,
        val exclusions: String = "",
    )
}
