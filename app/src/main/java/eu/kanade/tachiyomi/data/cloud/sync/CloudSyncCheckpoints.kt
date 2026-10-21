package eu.kanade.tachiyomi.data.cloud.sync

import it.lorelore.nyanime.cloud.sync.SyncCheckpointPack
import it.lorelore.nyanime.cloud.sync.SyncCheckpointPackIndex
import it.lorelore.nyanime.cloud.sync.SyncMaintenancePolicy
import it.lorelore.nyanime.cloud.sync.SyncWorkProgress
import it.lorelore.nyanime.cloud.sync.SyncWorkStage
import it.lorelore.nyanime.cloud.sync.SyncWorkUnit
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
import nyanime.telegram.sync.SyncReaderCapabilities
import nyanime.telegram.sync.SyncSignedCheckpoint
import nyanime.telegram.sync.SyncSignedReaderCapability
import nyanime.telegram.sync.syncJson
import java.util.UUID

internal data class VerifiedSyncSupport(
    val author: String,
    val epoch: Long,
    val maintenanceVersion: Int,
    val packingVersion: Int,
    val proof: SyncSignedReaderCapability? = null,
)

/** A checkpoint becomes authoritative only AFTER all encrypted parts and its commit are confirmed. */
internal class CloudSyncCheckpoints(
    private val store: CloudSyncStore,
    private val files: CloudSyncPacketFiles,
    private val transport: TelegramSyncTransport,
    private val device: SyncDeviceSecret,
) {
    var onProgress: (SyncWorkProgress) -> Unit = {}

    fun prepare(keys: CloudSyncKeys): Prepared {
        store.state("checkpoint-pending")?.takeIf { it.isNotEmpty() }?.let {
            val previous = syncJson.decodeFromString<Prepared>(it)
            if (previous.epoch == keys.epoch &&
                previous.exclusions == store.state("exclusions").orEmpty() &&
                (!previous.packed || packingReady(keys))
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
        var prepared = 0L
        onProgress(SyncWorkProgress(SyncWorkStage.PREPARING, unit = SyncWorkUnit.PARTS))
        val sequence = try {
            store.snapshot { changes ->
                require(parts.size < 4096) { "Recovery checkpoint exceeds its logical block limit" }
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
                prepared++
                onProgress(
                    SyncWorkProgress(
                        SyncWorkStage.PREPARING,
                        prepared,
                        unit = SyncWorkUnit.PARTS,
                        lastActivity = System.currentTimeMillis() / 1000,
                    ),
                )
            }
        } finally {
            key.fill(0)
        }
        val packed = packingReady(keys)
        val publishedParts = if (packed && parts.isNotEmpty()) pack(keys, id, parts) else parts
        return Prepared(
            id,
            keys.epoch,
            sequence,
            store.state("processed")?.toLong() ?: 0,
            publishedParts,
            exclusions = store.state("exclusions").orEmpty(),
            packed = packed,
        ).also(::save)
    }

    suspend fun publish(keys: CloudSyncKeys): SyncDelivery {
        check(keys.contains(device.public))
        var plan = prepare(keys)
        if (plan.packed) {
            check(packingReady(keys))
            transport.enableCheckpointPacking()
            relayCapabilities(keys)
        }
        fun report() = onProgress(
            SyncWorkProgress(
                SyncWorkStage.CHECKPOINT,
                plan.parts.count { it.messageId > 0 }.toLong(),
                plan.parts.size.toLong(),
                SyncWorkUnit.PARTS,
                lastActivity = System.currentTimeMillis() / 1000,
            ),
        )
        report()
        plan.parts.forEachIndexed { index, part ->
            if (part.messageId == 0L) {
                val receipt = send(files.prepared(part.id))
                plan = plan.copy(
                    parts = plan.parts.toMutableList().also {
                        it[index] = part.copy(messageId = receipt.receipt.messageId)
                    },
                )
                save(plan)
                report()
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
                maintenanceVersion = SyncMaintenancePolicy.VERSION,
                packingVersion = SyncCheckpointPack.VERSION,
                formatVersion = if (plan.packed) 2 else 1,
                readerCapability = ownReaderProof(keys),
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
        val confirmed = requireNotNull(verifiedCommit(delivery, keys))
        if (confirmed.maintenanceVersion >= SyncMaintenancePolicy.VERSION) {
            store.state("maintenance-announced", keys.membership.control)
        }
        // Resuming an immutable commit prepared by an older app does not announce new reader support.
        if (confirmed.packingVersion >= SyncCheckpointPack.VERSION && confirmed.readerCapability != null) {
            store.state("packing-announced", keys.membership.control)
        }
        registerCapability(delivery, keys)
        recordPacking(confirmed)
        plan.parts.forEach { files.removePrepared(it.id) }
        files.removePrepared(plan.id)
        return delivery
    }

    /** No expiry/last-seen shortcut: every authorized member must have signed current-epoch support. */
    fun packingReady(keys: CloudSyncKeys): Boolean = keys.membership.devices.isNotEmpty() &&
        keys.membership.devices.keys.all {
            (store.state("packing-capability/${keys.epoch}/$it")?.toLongOrNull() ?: 0) > 0 &&
                readerProof(keys, it) != null
        }

    fun packingStatus(keys: CloudSyncKeys) = it.lorelore.nyanime.cloud.sync.SyncPackingStatus(
        supportedDevices = keys.membership.devices.keys.count {
            (store.state("packing-capability/${keys.epoch}/$it")?.toLongOrNull() ?: 0) > 0 &&
                readerProof(keys, it) != null
        },
        totalDevices = keys.membership.devices.size,
        format = store.state("packing-format")?.toIntOrNull() ?: 1,
        documents = store.state("packing-documents")?.toIntOrNull() ?: 0,
        chunks = store.state("packing-chunks")?.toIntOrNull() ?: 0,
    )

    private fun readerProof(keys: CloudSyncKeys, author: String): SyncSignedReaderCapability? =
        store.state("packing-proof/${keys.epoch}/$author")?.takeIf { it.isNotEmpty() }?.let { raw ->
            val signed = syncJson.decodeFromString<SyncSignedReaderCapability>(raw)
            require(SyncReaderCapabilities.verify(signed, keys.account, keys.membership).author == author)
            signed
        }

    private fun ownReaderProof(keys: CloudSyncKeys): SyncSignedReaderCapability = readerProof(keys, device.public.id)
        ?: SyncReaderCapabilities.sign(keys.account, keys.membership, device).also {
            store.state("packing-proof/${keys.epoch}/${device.public.id}", syncJson.encodeToString(it))
        }

    /** A compact carrier takes priority over daily checkpoint declarations, keeping anchors small. */
    fun registerCapability(delivery: SyncDelivery, keys: CloudSyncKeys): Boolean {
        val support = verifiedCapability(delivery, keys) ?: return false
        if (support.epoch != keys.epoch) return false
        fun register(prefix: String) {
            val name = "$prefix/${keys.epoch}/${support.author}"
            val old = store.state(name)?.toLongOrNull()?.let(files::retainedDelivery)
            if (old?.packet?.kind != "capability" || delivery.packet.kind == "capability") {
                store.state(name, delivery.receipt.messageId.toString())
            }
        }
        if (support.maintenanceVersion >= SyncMaintenancePolicy.VERSION) register("maintenance-capability")
        if (support.packingVersion >= SyncCheckpointPack.VERSION && support.proof != null) {
            store.state("packing-proof/${keys.epoch}/${support.author}", syncJson.encodeToString(support.proof))
            register("packing-capability")
        }
        return true
    }

    /** Portable signatures may be relayed by another member; they never become a data checkpoint. */
    fun verifiedCapability(delivery: SyncDelivery, keys: CloudSyncKeys): VerifiedSyncSupport? {
        if (delivery.packet.kind == "commit") {
            return verifiedCommit(delivery, keys)?.let {
                VerifiedSyncSupport(it.author, it.epoch, it.maintenanceVersion, it.packingVersion, it.readerCapability)
            }
        }
        require(delivery.packet.kind == "capability" && delivery.packet.space == keys.space)
        val envelope = syncJson.decodeFromString<SyncEnvelope>(delivery.packet.payload)
        require(envelope.id == delivery.packet.id && envelope.epoch <= keys.epoch && envelope.space == keys.space)
        val key = keys.key(envelope.epoch)
        try {
            val signed = syncJson.decodeFromString<SyncSignedReaderCapability>(
                SyncCipher.decrypt(keys.account, keys.space, key, envelope).toString(Charsets.UTF_8),
            )
            val members = keys.history.getValue(envelope.epoch)
            val reader = SyncReaderCapabilities.verify(signed, keys.account, members)
            if (keys.membershipAt(delivery.receipt.messageId).devices[reader.author] !=
                members.devices[reader.author]
            ) {
                return null
            }
            return VerifiedSyncSupport(
                reader.author,
                reader.epoch,
                reader.maintenanceVersion,
                reader.packingVersion,
                signed,
            )
        } finally {
            key.fill(0)
        }
    }

    private suspend fun relayCapabilities(keys: CloudSyncKeys) {
        for (author in keys.membership.devices.keys) {
            val anchor = store.state("packing-capability/${keys.epoch}/$author")?.toLongOrNull()
            if (anchor?.let(files::retainedDelivery)?.packet?.kind == "capability") continue
            val proof = requireNotNull(readerProof(keys, author))
            val pending = "packing-pending/${keys.epoch}/$author"
            val previous = store.state(pending)?.takeIf { it.isNotEmpty() }
            val packet = if (previous != null) {
                files.prepared(previous)
            } else {
                val key = keys.key()
                val envelope = try {
                    SyncCipher.encrypt(
                        keys.account,
                        keys.space,
                        keys.epoch,
                        UUID.randomUUID().toString(),
                        key,
                        syncJson.encodeToString(proof).toByteArray(Charsets.UTF_8),
                    )
                } finally {
                    key.fill(0)
                }
                SyncPacket(
                    kind = "capability",
                    id = envelope.id,
                    space = keys.space,
                    payload = syncJson.encodeToString(envelope),
                ).also {
                    files.prepare(it)
                    store.state(pending, it.id)
                }
            }
            require(registerCapability(send(packet), keys))
            store.state(pending, "")
            files.removePrepared(packet.id)
        }
    }

    fun packingMigrationDue(keys: CloudSyncKeys): Boolean = packingReady(keys) &&
        store.messagesOfKind("commit", limit = 2).mapNotNull {
            verifiedCommit(files.delivery(it), keys)
        }.let { commits -> commits.size < 2 || commits.any { it.formatVersion != 2 } }

    private fun recordPacking(commit: SyncCheckpointCommit) {
        store.state("packing-format", commit.formatVersion.toString())
        store.state("packing-documents", commit.parts.size.toString())
        store.state("packing-chunks", commit.parts.sumOf { it.chunks }.toString())
    }

    private fun pack(
        keys: CloudSyncKeys,
        checkpoint: String,
        children: List<SyncCheckpointPart>,
    ): List<SyncCheckpointPart> {
        val groups = mutableListOf<List<SyncCheckpointPart>>()
        var group = mutableListOf<SyncCheckpointPart>()
        var bytes = 0L
        children.forEach { child ->
            val size = SyncCheckpointPack.entry(files.prepared(child.id), 0).bytes + 4L
            if (group.isNotEmpty() &&
                (
                    group.size == SyncCheckpointPack.MAX_CHUNKS ||
                        bytes + size > SyncCheckpointPack.MAX_BYTES - SyncCheckpointPack.MAX_HEADER_BYTES - 128
                    )
            ) {
                groups += group
                group = mutableListOf()
                bytes = 0
            }
            group += child
            bytes += size
        }
        if (group.isNotEmpty()) groups += group
        val key = keys.key()
        try {
            val result = groups.map { parts ->
                var offset = 0L
                val entries = parts.map {
                    SyncCheckpointPack.entry(files.prepared(it.id), offset).also { entry -> offset += 4L + entry.bytes }
                }
                val index = SyncCheckpointPackIndex(checkpoint = checkpoint, chunks = entries).also { it.validate() }
                val envelope = SyncCipher.encrypt(
                    keys.account,
                    keys.space,
                    keys.epoch,
                    UUID.randomUUID().toString(),
                    key,
                    syncJson.encodeToString(index).toByteArray(Charsets.UTF_8),
                )
                val packet = SyncPacket(
                    kind = "checkpoint-pack",
                    id = envelope.id,
                    space = keys.space,
                    payload = syncJson.encodeToString(envelope),
                )
                files.preparePack(packet, parts.map { it.id })
                SyncCheckpointPart(packet.id, SyncCheckpoints.hash(packet.payload), 0, parts.size)
            }
            children.forEach { files.removePrepared(it.id) }
            return result
        } finally {
            key.fill(0)
        }
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
            if (packet.kind == "checkpoint-pack") {
                require(
                    files.stagePack(requireNotNull(files.packFile(packet.id, prepared = true)), packet.id) == packet,
                )
            }
            files.stage(it)
            store.staged(it)
            store.state(queueKey, "")
        }
    }

    suspend fun restore(delivery: SyncDelivery, keys: CloudSyncKeys): Long {
        require(delivery.packet.kind == "commit")
        return run {
            val commit = verifiedCommit(delivery, keys) ?: return 0
            val total = commit.parts.sumOf { it.chunks }.toLong()
            var verified = 0L
            onProgress(
                SyncWorkProgress(
                    SyncWorkStage.RESTORING,
                    total = total,
                    unit = SyncWorkUnit.PARTS,
                ),
            )
            commit.parts.forEach { part ->
                require(part.messageId < delivery.receipt.messageId)
                val found = store.messageFor(part.id)
                if (found == null) {
                    val received = transport.message(part.messageId)
                    require(received.packet.id == part.id)
                    files.stage(received)
                    store.staged(received)
                }
                repeat(part.chunks) { position ->
                    verifiedChunk(
                        files.delivery(requireNotNull(store.messageFor(part.id))),
                        part,
                        commit,
                        keys,
                        position,
                    )
                    verified++
                    onProgress(
                        SyncWorkProgress(
                            SyncWorkStage.RESTORING,
                            verified,
                            total,
                            SyncWorkUnit.PARTS,
                            lastActivity = System.currentTimeMillis() / 1000,
                        ),
                    )
                    kotlinx.coroutines.yield()
                } // Validate the complete set before any database projection.
            }
            store.restoreSnapshot(
                commit.id,
                delivery.receipt.messageId,
                commit.parts.asSequence().flatMap { part ->
                    (0 until part.chunks).asSequence().map { position ->
                        verifiedChunk(
                            files.delivery(requireNotNull(store.messageFor(part.id))),
                            part,
                            commit,
                            keys,
                            position,
                        ).changes
                    }
                },
                onChunkApplied = { applied ->
                    if (eu.kanade.tachiyomi.data.rin.RinSessions.busy.value) throw CloudSyncTransferDeferred()
                    onProgress(
                        SyncWorkProgress(
                            SyncWorkStage.APPLYING,
                            applied,
                            total,
                            SyncWorkUnit.PARTS,
                            lastActivity = System.currentTimeMillis() / 1000,
                        ),
                    )
                },
            )
            store.state("checkpoint-message", delivery.receipt.messageId.toString())
            store.state(
                "archive-last-checkpoint",
                maxOf(
                    store.state("archive-last-checkpoint")?.toLongOrNull() ?: 0,
                    delivery.receipt.serverDate,
                ).toString(),
            )
            store.state("compact-through", commit.throughMessage.toString())
            recordPacking(commit)
            commit.throughMessage
        }
    }

    /** Shared by recovery and maintenance. Does not acknowledge, project or modify any journal. */
    fun verifiedCommit(delivery: SyncDelivery, keys: CloudSyncKeys): SyncCheckpointCommit? {
        require(delivery.packet.kind == "commit" && delivery.packet.space == keys.space)
        val envelope = syncJson.decodeFromString<SyncEnvelope>(delivery.packet.payload)
        envelope.validate()
        require(envelope.epoch <= keys.epoch && envelope.space == keys.space)
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
                return null
            }
            require(commit.id == envelope.id && envelope.id == delivery.packet.id)
            require(commit.throughMessage < delivery.receipt.messageId)
            require(commit.parts.all { it.messageId < delivery.receipt.messageId })
            return commit
        } finally {
            key.fill(0)
        }
    }

    fun verifiedChunk(
        delivery: SyncDelivery,
        part: SyncCheckpointPart,
        commit: SyncCheckpointCommit,
        keys: CloudSyncKeys,
        position: Int = 0,
    ): SyncCheckpointChunk {
        require(delivery.packet.kind in setOf("checkpoint", "checkpoint-pack") && delivery.packet.space == keys.space)
        require(delivery.receipt.messageId == part.messageId)
        val envelope = syncJson.decodeFromString<SyncEnvelope>(delivery.packet.payload)
        require(envelope.epoch == commit.epoch && envelope.space == commit.space)
        val key = keys.key(commit.epoch)
        return try {
            require(envelope.id == part.id && SyncCheckpoints.hash(delivery.packet.payload) == part.hash)
            if (delivery.packet.kind == "checkpoint-pack") {
                require(commit.formatVersion == 2)
                val index = syncJson.decodeFromString<SyncCheckpointPackIndex>(
                    SyncCipher.decrypt(keys.account, keys.space, key, envelope).toString(Charsets.UTF_8),
                ).also { it.validate() }
                require(index.checkpoint == commit.id && index.chunks.size == part.chunks)
                val packet = SyncCheckpointPack.packet(
                    requireNotNull(files.packFile(part.id)),
                    delivery.packet,
                    index,
                    position,
                )
                val child = syncJson.decodeFromString<SyncEnvelope>(packet.payload)
                require(child.epoch == commit.epoch && child.id == packet.id && child.space == commit.space)
                val descriptor = index.chunks[position]
                return SyncCheckpoints.verifyPart(
                    SyncCheckpointPart(descriptor.id, descriptor.hash, part.messageId),
                    child,
                    commit.id,
                    SyncCipher.decrypt(keys.account, keys.space, key, child),
                )
            }
            require(position == 0 && part.chunks == 1)
            SyncCheckpoints.verifyPart(
                part,
                envelope,
                commit.id,
                SyncCipher.decrypt(keys.account, keys.space, key, envelope),
            )
        } finally {
            key.fill(0)
        }
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
        val packed: Boolean = false,
    )
}
