package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import it.lorelore.nyanime.cloud.sync.SyncMaintenanceIndex
import it.lorelore.nyanime.cloud.sync.SyncMaintenancePolicy
import it.lorelore.nyanime.cloud.sync.SyncWorkProgress
import it.lorelore.nyanime.cloud.sync.SyncWorkStage
import it.lorelore.nyanime.cloud.sync.SyncWorkUnit
import kotlinx.coroutines.yield
import nyanime.telegram.TelegramException
import nyanime.telegram.sync.SyncArchiveBootstrap
import nyanime.telegram.sync.SyncBatchSignature
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncCipher
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncDevicePublic
import nyanime.telegram.sync.SyncDeviceSecret
import nyanime.telegram.sync.SyncEnvelope
import nyanime.telegram.sync.SyncMembership
import nyanime.telegram.sync.SyncMembershipChange
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncSignedBatch
import nyanime.telegram.sync.SyncSignedControl
import nyanime.telegram.sync.SyncSignedRoot
import nyanime.telegram.sync.syncJson

/** Network/crypto coordinator. It has no player, reader, source implementation or UI references. */
internal class CloudSyncSession(
    context: Context,
    client: TelegramClient,
    initial: CloudSyncKeys,
    val device: SyncDeviceSecret,
    private val vault: CloudSyncVault,
    transportContext: Context = context,
) {
    var keys = initial
        private set
    val store = CloudSyncStore(context, keys.account, keys.space).also { it.bindDevice(device.public.id) }
    val files = CloudSyncPacketFiles(context, keys.account, keys.space)
    val transport = TelegramSyncTransport(
        transportContext,
        client,
        keys.account,
        keys.chat,
        keys.space,
        files::retainedDelivery,
    )
    val checkpoints = CloudSyncCheckpoints(store, files, transport, device)
    val maintenanceIndex = SyncMaintenanceIndex(context, keys.account, keys.space, keys.chat)

    var onProgress: (SyncWorkProgress) -> Unit = {}

    /** Persist a page at a time, then consume in server order; interruption never skips a gap. */
    suspend fun pull(maxPages: Int = 4): Boolean {
        require(maxPages in 1..100)
        var before = store.state("pull-before")?.toLongOrNull() ?: 0
        val after = store.state("received")?.toLongOrNull() ?: 0
        var newest = store.state("pull-top")?.toLongOrNull() ?: after
        var pages = if (before == 0L) 0L else store.state("progress-receive-pages")?.toLongOrNull() ?: 0
        if (before > 0) onProgress(SyncWorkProgress(SyncWorkStage.RECEIVING, pages, unit = SyncWorkUnit.PAGES))
        repeat(maxPages) {
            val page = transport.page(before, after) { delivery ->
                files.stage(delivery)
                store.staged(delivery)
            }
            pages++
            store.state("progress-receive-pages", pages.toString())
            if (pages > 1 || page.newest > after || !page.complete) {
                onProgress(
                    SyncWorkProgress(
                        SyncWorkStage.RECEIVING,
                        pages,
                        unit = SyncWorkUnit.PAGES,
                        lastActivity = System.currentTimeMillis() / 1000,
                    ),
                )
            }
            newest = maxOf(newest, page.newest)
            if (page.complete) {
                store.state("received", newest.toString())
                store.state("pull-before", "0")
                store.state("pull-top", "0")
                consumeStaged()
                return true
            }
            require(page.next > 0 && (before == 0L || page.next < before))
            before = page.next
            store.state("pull-before", before.toString())
            store.state("pull-top", newest.toString())
            yield()
        }
        return false
    }

    internal suspend fun consumeStaged() {
        val through = store.state("received")?.toLongOrNull() ?: return
        // Complete authorization first, then restore the newest complete checkpoint. Older
        // commits can already be staged locally when another upgraded phone removes their parts.
        // Replaying every historical checkpoint would strand that phone on a deleted document.
        var authorization = store.state("maintenance-authorization")?.toLongOrNull() ?: 0
        while (true) {
            val id = store.nextMessageOfKind("control", authorization)?.takeIf { it <= through } ?: break
            consume(files.delivery(id))
            authorization = id
            store.state("maintenance-authorization", id.toString())
            yield()
        }
        if (!keys.contains(device.public)) return
        var latest = store.messagesOfKind("commit", limit = 1).firstOrNull()?.takeIf { it <= through }
        while (latest != null && checkpoints.verifiedCommit(files.delivery(latest), keys) == null) {
            latest = store.messagesOfKind("commit", before = latest, limit = 1).firstOrNull()
        }
        var floor = store.state("maintenance-floor")?.toLongOrNull() ?: 0
        if (latest != null && latest > floor) {
            val delivery = files.delivery(latest)
            if (checkpoints.verifiedCommit(delivery, keys) != null) {
                if (store.state("checkpoint") != delivery.packet.id) checkpoints.restore(delivery, keys)
                floor = latest
                store.state("maintenance-floor", floor.toString())
            }
        }
        var cursor = store.state("processed")?.toLongOrNull() ?: 0
        while (true) {
            val next = store.nextMessage(cursor)?.takeIf { it <= through } ?: break
            val delivery = files.delivery(next)
            require(delivery.receipt.messageId == next)
            if (delivery.packet.kind == "commit") {
                val commit = checkpoints.verifiedCommit(delivery, keys)
                if (commit?.epoch == keys.epoch && commit.maintenanceVersion >= SyncMaintenancePolicy.VERSION) {
                    store.state("maintenance-capability/${keys.epoch}/${commit.author}", next.toString())
                    if (maintenanceIndex.plan() == null) maintenanceIndex.put("next", "0")
                }
            }
            try {
                // Checkpoints may omit locally excluded fields. Merge ALL retained snapshots;
                // the newest checkpoint alone is not proof that an older field is redundant.
                consume(delivery)
            } catch (error: TelegramException) {
                val superseded = delivery.packet.kind == "commit" &&
                    next < floor &&
                    error.code == 404 &&
                    transport.maintenanceMessage(next) == null &&
                    transport.checkpointMaintenanceEnabled()
                if (!superseded) throw error
            }
            cursor = next
            store.state("processed", cursor.toString())
            if (!keys.contains(device.public)) break
            yield()
        }
    }

    private suspend fun consume(delivery: SyncDelivery) {
        val packet = delivery.packet
        require(packet.space == keys.space)
        when (packet.kind) {
            "root" -> {
                val signed = syncJson.decodeFromString<SyncSignedRoot>(packet.payload)
                val root = SyncArchiveBootstrap.verify(signed, keys.account)
                require(root.id == packet.id && signed == keys.root)
            }
            "control" -> {
                val signed = syncJson.decodeFromString<SyncSignedControl>(packet.payload)
                val change = syncJson.decodeFromString<SyncMembershipChange>(signed.payload)
                require(change.id == packet.id && change.space == keys.space)
                if (keys.history.values.any { it.control == change.id }) return
                if (change.previous != keys.membership.control) {
                    // A concurrent proposal lost the canonical server ordering. Its sender must rebase.
                    require(change.epoch <= keys.epoch)
                    return
                }
                val next = keys.accept(signed, delivery.receipt.messageId, device)
                next.save(vault)
                keys = next
                store.rebaseOutbox()
            }
            "data" -> {
                val envelope = envelope(packet)
                require(envelope.epoch <= keys.epoch)
                val members = keys.history[envelope.epoch] ?: error("Unknown synchronization epoch")
                val key = keys.key(envelope.epoch)
                val clear = try {
                    SyncCipher.decrypt(keys.account, keys.space, key, envelope)
                } finally {
                    key.fill(0)
                }
                val signed = syncJson.decodeFromString<SyncSignedBatch>(clear.toString(Charsets.UTF_8))
                val batch = SyncBatchSignature.verify(signed, keys.account, envelope.epoch, members)
                // A still-authorized phone may race with a key rotation. Preserve its signed edit.
                // Removed devices cannot introduce edits after their confirmed removal.
                if (keys.membershipAt(delivery.receipt.messageId).devices[batch.device] !=
                    members.devices[batch.device]
                ) {
                    return
                }
                require(batch.id == packet.id)
                store.receive(batch, SyncCheckpoints.hash(packet.payload), delivery.receipt.messageId)
            }
            "commit" -> {
                val envelope = envelope(packet)
                require(envelope.epoch <= keys.epoch)
                if (store.state("checkpoint") != packet.id) checkpoints.restore(delivery, keys)
            }
            "checkpoint", "pairing", "recovery" -> Unit // Parts become data only via a verified complete commit.
            else -> error("Unsupported synchronization packet")
        }
    }

    suspend fun sendChanges(): Boolean {
        check(keys.contains(device.public))
        if (store.pending()?.envelope?.epoch?.let { it != keys.epoch } == true) store.rebaseOutbox()
        val pending = store.prepare(keys.space) { batch ->
            val signed = SyncBatchSignature.sign(batch, keys.account, keys.epoch, device)
            val key = keys.key()
            try {
                SyncCipher.encrypt(
                    keys.account,
                    keys.space,
                    keys.epoch,
                    batch.id,
                    key,
                    syncJson.encodeToString(signed).toByteArray(Charsets.UTF_8),
                )
            } finally {
                key.fill(0)
            }
        } ?: return false
        val packet = SyncPacket.data(pending.envelope)
        // pull() must have completed before this method; history resolves an uncertain receipt.
        val previous = store.messageFor(packet.id)
        val receipt = if (previous != null) {
            files.delivery(previous).also { require(it.packet == packet) }.receipt
        } else {
            store.sending(pending.copy(attempted = true))
            val queued: (Long) -> Unit = { temporary ->
                store.sending(pending.copy(messageId = temporary, attempted = true))
            }
            (transport.resume(packet, pending.messageId, queued) ?: transport.send(packet, queued)).also { receipt ->
                val delivery = SyncDelivery(packet, receipt)
                files.stage(delivery)
                store.staged(delivery)
            }
        }
        store.confirm(packet.id, receipt.messageId, receipt.serverDate)
        onProgress(
            SyncWorkProgress(
                SyncWorkStage.SENDING,
                1,
                1,
                SyncWorkUnit.PACKETS,
                lastActivity = receipt.serverDate,
            ),
        )
        return true
    }

    suspend fun changeDevices(add: SyncDevicePublic? = null, remove: String? = null): Boolean {
        require((add == null) != (remove == null))
        check(keys.contains(device.public))
        while (!pull()) yield()
        resumeMembershipChange()
        val devices = keys.membership.devices.toMutableMap()
        if (add != null) {
            add.validate()
            val previous = devices[add.id]
            require(previous == null || previous == add)
            if (previous == add) return true
            devices[add.id] = add
        } else {
            if (remove !in devices) return true
            devices.remove(remove)
        }
        val key = SyncCipher.newKey()
        val signed = try {
            SyncMembership.propose(keys.membership, device, devices.values, keys.account, key)
        } finally {
            key.fill(0)
        }
        val change = syncJson.decodeFromString<SyncMembershipChange>(signed.payload)
        val packet = SyncPacket(
            kind = "control",
            id = change.id,
            space = keys.space,
            payload = syncJson.encodeToString(signed),
        )
        files.prepare(packet)
        store.state("membership-pending", packet.id)
        return resumeMembershipChange()
    }

    /** Resume the exact signed proposal after a lost receipt or process death. */
    suspend fun resumeMembershipChange(): Boolean {
        val id = store.state("membership-pending")?.takeIf { it.isNotEmpty() } ?: return true
        val packet = files.prepared(id)
        val signed = syncJson.decodeFromString<SyncSignedControl>(packet.payload)
        val change = syncJson.decodeFromString<SyncMembershipChange>(signed.payload)
        if (store.messageFor(id) == null && change.previous == keys.membership.control) {
            check(keys.contains(device.public))
            val delivery = SyncDelivery(packet, transport.send(packet) {})
            files.stage(delivery)
            store.staged(delivery)
        }
        while (!pull()) yield()
        val accepted = keys.history.values.any { it.control == id }
        // A concurrent accepted proposal wins; the caller can explicitly retry against that state.
        store.state("membership-pending", "")
        files.removePrepared(id)
        return accepted
    }

    private fun envelope(packet: SyncPacket): SyncEnvelope =
        syncJson.decodeFromString<SyncEnvelope>(packet.payload).also {
            it.validate()
            require(it.id == packet.id && it.space == packet.space)
        }

    fun close() {
        maintenanceIndex.close()
        store.close()
    }
}
