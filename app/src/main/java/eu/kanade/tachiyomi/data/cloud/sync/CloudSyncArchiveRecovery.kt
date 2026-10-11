package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import kotlinx.coroutines.yield
import nyanime.telegram.sync.SyncArchiveBootstrap
import nyanime.telegram.sync.SyncArchiveDescriptor
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncMembership
import nyanime.telegram.sync.SyncMembershipState
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncSignedControl
import nyanime.telegram.sync.syncJson

/** Rebuilds a deleted private archive with the same signed identity and device authorizations. */
internal class CloudSyncArchiveRecovery(private val context: Context, private val client: TelegramClient) {
    suspend fun recover(session: CloudSyncSession): CloudSyncKeys {
        val old = session.keys
        check(client.state.value.ready && client.state.value.account?.id == old.account)
        check(old.contains(session.device.public))
        val root = SyncArchiveBootstrap.verify(old.root, old.account)
        val directory = CloudSyncDirectory(client)
        val candidates = directory.discover().filter { it.descriptor.space == old.space && it.chat != old.chat }
        require(candidates.size <= 1) { "Multiple replacement archives" }
        val candidate = candidates.singleOrNull()
        val existing = mutableMapOf<String, SyncDelivery>()
        suspend fun readExisting(reader: TelegramSyncTransport) {
            var cursor = 0L
            do {
                val page = reader.page(cursor, 0, setOf("root", "control")) { delivery ->
                    val prior = existing[delivery.packet.id]
                    require(prior == null || prior.packet == delivery.packet)
                    if (prior == null || delivery.receipt.messageId < prior.receipt.messageId) {
                        existing[delivery.packet.id] = delivery
                    }
                }
                cursor = page.next
                yield()
            } while (!page.complete)
        }
        if (candidate != null) {
            val reader = TelegramSyncTransport(context, client, old.account, candidate.chat, old.space)
            reader.verifyArchive()
            readExisting(reader)
        }
        val rootPacket =
            SyncPacket(kind = "root", id = root.id, space = old.space, payload = syncJson.encodeToString(old.root))
        fun verifyRoot() {
            require(existing.values.filter { it.packet.kind == "root" }.all { it.packet == rootPacket }) {
                "Replacement identity mismatch"
            }
        }
        verifyRoot()
        // Old keyrings may predate retained authorization history, while message caches are disposable.
        // Fetching a replacement is read-only: verify every original signature before creating or sending anything.
        val signedControls = CloudSyncRecoveryControls.restore(old, session.files::retainedDelivery, existing)
        val controls = signedControls.map { (epoch, signed) ->
            SyncPacket(
                kind = "control",
                id = old.history.getValue(epoch).control,
                space = old.space,
                payload = syncJson.encodeToString(signed),
            )
        }
        val archive = candidate ?: directory.create(old.space)
        val transport = TelegramSyncTransport(context, client, old.account, archive.chat, old.space)
        transport.verifyArchive()
        if (candidate == null) {
            // Another device may have created the replacement while its original chain was being verified.
            readExisting(transport)
            verifyRoot()
        }
        suspend fun publish(packet: SyncPacket): Long {
            existing[packet.id]?.let {
                require(it.packet == packet)
                return it.receipt.messageId
            }
            return transport.send(packet) {}.messageId
        }
        val rootMessage = publish(rootPacket)
        var state = SyncArchiveBootstrap.initial(root, rootMessage)
        val history = mutableMapOf(1L to state)
        for (packet in controls) {
            val message = publish(packet)
            state =
                SyncMembership.accept(
                    state,
                    syncJson.decodeFromString<SyncSignedControl>(packet.payload),
                    old.account,
                    message,
                ).first
            history[state.epoch] = state
        }
        require(state.devices == old.membership.devices && state.control == old.membership.control)
        directory.complete(archive.chat, SyncArchiveDescriptor(old.space, rootMessage))
        return old.copy(
            chat = archive.chat,
            rootMessage = rootMessage,
            membership = state,
            history = history,
            controls = signedControls,
        ).also {
            it.validate()
        }
    }
}
