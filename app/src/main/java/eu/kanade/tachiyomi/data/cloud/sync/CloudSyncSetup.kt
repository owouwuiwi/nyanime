package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.cloud.CloudRepository
import eu.kanade.tachiyomi.data.rin.RinSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import nyanime.telegram.sync.SyncArchiveBootstrap
import nyanime.telegram.sync.SyncArchiveDescriptor
import nyanime.telegram.sync.SyncCipher
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncDeviceCrypto
import nyanime.telegram.sync.SyncDeviceSecret
import nyanime.telegram.sync.SyncMembership
import nyanime.telegram.sync.SyncMembershipChange
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncPairing
import nyanime.telegram.sync.SyncPairingGrant
import nyanime.telegram.sync.SyncPairingRequest
import nyanime.telegram.sync.SyncRecoveryCode
import nyanime.telegram.sync.SyncSignedControl
import nyanime.telegram.sync.SyncSignedRoot
import nyanime.telegram.sync.syncJson
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/** Explicit enrollment boundary. Login/backup alone never creates or joins a synchronization space. */
internal class CloudSyncSetup(private val context: Context, private val manager: CloudSyncManager) {
    private val cloud = CloudRepository.get(context)
    private val client get() = cloud.client
    private val operation = Mutex()
    private val clock = CloudSyncClock()
    private val directory = CloudSyncDirectory(client)

    suspend fun discover(): List<CloudSyncArchive> = withContext(Dispatchers.IO) {
        cloud.ready()
        directory.discover()
    }

    suspend fun pendingCreationCode(): String? = withContext(Dispatchers.IO) {
        load<Creation>(CloudSyncVault(context, cloud.ready()), "setup")?.recoveryCode
    }

    suspend fun hasPendingApproval(space: String? = null): Boolean = withContext(Dispatchers.IO) {
        val pairing = load<Pairing>(CloudSyncVault(context, cloud.ready()), "pairing")
        pairing?.bundle != null &&
            !pairing.completed &&
            (space == null || pairing.bundle.membership.space == space)
    }

    suspend fun localPreview(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val anime = Injekt.get<tachiyomi.domain.entries.anime.repository.AnimeRepository>()
        val manga = Injekt.get<tachiyomi.domain.entries.manga.repository.MangaRepository>()
        val privateAnime = Injekt.get<eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState>()
        val privateManga = Injekt.get<eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState>()
        val videos = (anime.getAnimeFavorites() + anime.getWatchedAnimeNotInLibrary()).distinctBy { it.id }
            .count { it.source != 0L && !privateAnime.await(it.source) }
        val comics = (manga.getMangaFavorites() + manga.getReadMangaNotInLibrary()).distinctBy { it.id }
            .count { it.source != 0L && !privateManga.await(it.source) }
        videos to comics
    }

    /** Persisted before showing the recovery code, so rotation/restart cannot silently replace it. */
    suspend fun prepareCreation(): String = operation.withLock {
        withContext(Dispatchers.IO) {
            idle()
            val account = cloud.ready()
            val vault = CloudSyncVault(context, account)
            load<Creation>(vault, "setup")?.let { return@withContext it.recoveryCode }
            check(directory.discover().isEmpty()) { "Associate the existing archive" }
            val device = device(vault)
            val code = SyncRecoveryCode.create()
            val root = SyncArchiveBootstrap.create(account, UUID.randomUUID().toString(), device, code)
            save(vault, "setup", Creation(root, code))
            code
        }
    }

    /** Called by the final 'merge and enable' action, after the user saves the recovery code. */
    suspend fun createAndEnable() = operation.withLock {
        withContext(Dispatchers.IO) {
            idle()
            val account = cloud.ready()
            val vault = CloudSyncVault(context, account)
            var plan = requireNotNull(load<Creation>(vault, "setup"))
            val root = SyncArchiveBootstrap.verify(plan.root, account)
            val archive = if (plan.chat != 0L) {
                CloudSyncArchive(plan.chat, SyncArchiveDescriptor(root.space, plan.rootMessage))
            } else {
                directory.create(root.space).also {
                    plan = plan.copy(chat = it.chat)
                    save(vault, "setup", plan)
                }
            }
            val transport = transport(account, archive)
            transport.verifyArchive()
            if (plan.rootMessage == 0L) {
                // A lost send receipt is recovered by identity, not by creating another archive/root.
                var found: SyncDelivery? = null
                pages(transport, kinds = setOf("root")) { delivery ->
                    if (delivery.packet.id == root.id) {
                        require(delivery.packet.payload == syncJson.encodeToString(plan.root))
                        if (found == null || delivery.receipt.messageId < found!!.receipt.messageId) found = delivery
                    }
                }
                val packet = SyncPacket(
                    kind = "root",
                    id = root.id,
                    space = root.space,
                    payload = syncJson.encodeToString(plan.root),
                )
                val message = found?.receipt ?: transport.send(packet) {}
                plan = plan.copy(rootMessage = message.messageId)
                save(vault, "setup", plan)
            }
            directory.complete(archive.chat, SyncArchiveDescriptor(root.space, plan.rootMessage))
            val keys = CloudSyncKeys.first(account, archive.chat, plan.root, plan.rootMessage, device(vault))
            keys.save(vault)
            CloudSyncSafetyCopy(context).create(keys)
            manager.activate(keys)
            manager.preferences.recoverySaved().set(true)
            vault.remove("setup") // Do not retain the recovery authority's printed secret after confirmation.
        }
    }

    suspend fun pairingRequest(renew: Boolean = false): String = operation.withLock {
        withContext(Dispatchers.IO) {
            idle()
            val account = cloud.ready()
            clock.refresh(client, force = true)
            val vault = CloudSyncVault(context, account)
            val previous = load<Pairing>(vault, "pairing")
            check(previous?.bundle == null) { "Complete the already approved association" }
            val request = previous?.request?.takeIf { !renew && it.expiresAt > clock.now() }
                ?: SyncPairing.request(account, device(vault).public, clock.now()).also {
                    save(vault, "pairing", Pairing(it))
                }
            QR_PREFIX + syncJson.encodeToString(request)
        }
    }

    /** Validate before the UI asks for final physical-device approval; never publish this QR secret. */
    suspend fun inspectRequest(qr: String): SyncPairingRequest = withContext(Dispatchers.IO) {
        require(qr.startsWith(QR_PREFIX) && qr.length < 16 * 1024)
        val account = cloud.ready()
        clock.refresh(client, force = true)
        syncJson.decodeFromString<SyncPairingRequest>(qr.removePrefix(QR_PREFIX)).also {
            it.validate(clock.now(), account)
        }
    }

    suspend fun approve(qr: String) = operation.withLock {
        val request = inspectRequest(qr)
        idle()
        manager.withSession { current ->
            request.validate(clock.now(), current.keys.account)
            require(request.recipient.id != current.device.public.id)
            val stateKey = "pairing-approved/${request.id}"
            if (current.store.state(stateKey) == "true") return@withSession
            while (!current.pull()) yield()
            if (!current.changeDevices(add = request.recipient)) error("Membership changed; retry approval")
            val packetId = current.store.state("pairing-packet/${request.id}")
            val packet = if (packetId != null) {
                current.files.prepared(packetId)
            } else {
                val checkpoint = current.checkpoints.publish(current.keys)
                val bundle = CloudSyncPairingBundle(
                    account = current.keys.account, chat = current.keys.chat,
                    root = current.keys.root, rootMessage = current.keys.rootMessage,
                    membership = current.keys.membership, key = SyncCipher.encode(current.keys.key()),
                    checkpointMessage = checkpoint.receipt.messageId,
                    history = current.keys.history, epochs = current.keys.epochs,
                    controls = current.keys.recoveryControls { member ->
                        syncJson.decodeFromString<SyncSignedControl>(
                            current.files.delivery(member.messageId).packet.payload,
                        )
                    },
                )
                val clear = syncJson.encodeToString(bundle).toByteArray(Charsets.UTF_8)
                val grant = try {
                    SyncPairing.approve(request, current.keys.account, clock.now(), clear)
                } finally {
                    clear.fill(0)
                }
                SyncPacket(
                    kind = "pairing",
                    id = request.id,
                    space = current.keys.space,
                    payload = syncJson.encodeToString(grant),
                ).also {
                    current.files.prepare(it)
                    current.store.state("pairing-packet/${request.id}", it.id)
                }
            }
            request.validate(clock.now(), current.keys.account)
            if (current.store.messageFor(packet.id) == null) {
                val delivery = SyncDelivery(packet, current.transport.send(packet) {})
                current.files.stage(delivery)
                current.store.staged(delivery)
            }
            current.store.state(stateKey, "true")
            current.files.removePrepared(packet.id)
        }
    }

    /** Receiving approval does not apply library data; the next screen still confirms the merge. */
    suspend fun receiveApproval(archive: CloudSyncArchive): Boolean = operation.withLock {
        withContext(Dispatchers.IO) {
            val account = cloud.ready()
            clock.refresh(client, force = true)
            val vault = CloudSyncVault(context, account)
            val pairing = requireNotNull(load<Pairing>(vault, "pairing"))
            if (pairing.bundle != null) return@withContext true
            pairing.request.validate(clock.now(), account)
            val device = device(vault)
            var approved: CloudSyncPairingBundle? = null
            val transport = transport(account, archive)
            transport.verifyArchive()
            pages(transport, kinds = setOf("pairing")) { delivery ->
                if (delivery.packet.id != pairing.request.id) return@pages
                val grant = syncJson.decodeFromString<SyncPairingGrant>(delivery.packet.payload)
                val clear = SyncPairing.accept(pairing.request, grant, device, account, clock.now())
                val bundle = try {
                    syncJson.decodeFromString<CloudSyncPairingBundle>(clear.toString(Charsets.UTF_8))
                } finally {
                    clear.fill(0)
                }
                bundle.validate(device.public, account)
                require(bundle.chat == archive.chat && bundle.membership.space == archive.descriptor.space)
                require(bundle.rootMessage == archive.descriptor.rootMessage)
                if (approved != null) require(approved == bundle)
                approved = bundle
            }
            if (approved != null) {
                // One atomic Keystore-wrapped write consumes the nonce AND persists the granted keys.
                save(vault, "pairing", pairing.copy(bundle = approved))
            }
            approved != null
        }
    }

    suspend fun enableApproved(activate: Boolean = true) = operation.withLock {
        withContext(Dispatchers.IO) {
            idle()
            val account = cloud.ready()
            val vault = CloudSyncVault(context, account)
            val pairing = requireNotNull(load<Pairing>(vault, "pairing"))
            check(!pairing.completed)
            val bundle = requireNotNull(pairing.bundle)
            bundle.validate(device(vault).public, account)
            val transport = transport(
                account,
                CloudSyncArchive(
                    bundle.chat,
                    SyncArchiveDescriptor(bundle.membership.space, bundle.rootMessage),
                ),
            )
            val oldKeys = bundle.keys()
            val controls = mutableMapOf<Long, SyncSignedControl>()
            if (oldKeys.controls.size.toLong() != oldKeys.epoch - 1) {
                pages(transport, kinds = setOf("control")) { delivery ->
                    val member = oldKeys.history.values.firstOrNull { it.control == delivery.packet.id }
                    if (member != null) controls[member.epoch] = syncJson.decodeFromString(delivery.packet.payload)
                }
            }
            val keys = oldKeys.copy(controls = oldKeys.recoveryControls { member -> controls.getValue(member.epoch) })
            keys.save(vault)
            if (activate) {
                CloudSyncSafetyCopy(context).create(keys)
                manager.activate(keys, checkpoint = bundle.checkpointMessage)
            }
            // The tombstone remains: restarting cannot accept the same request a second time.
            save(vault, "pairing", pairing.copy(completed = activate))
        }
    }

    /** Recovery code authenticates the root and recovers every epoch, including offline-device changes. */
    suspend fun recoverAndEnable(
        archive: CloudSyncArchive,
        code: String,
        activate: Boolean = true,
    ) = operation.withLock {
        withContext(Dispatchers.IO) {
            idle()
            val account = cloud.ready()
            val transport = transport(account, archive)
            transport.verifyArchive()
            require(archive.descriptor.rootMessage > 0)
            val rootPacket = transport.message(archive.descriptor.rootMessage)
            require(rootPacket.packet.kind == "root")
            val root = syncJson.decodeFromString<SyncSignedRoot>(rootPacket.packet.payload)
            val data = SyncArchiveBootstrap.verify(root, account)
            require(data.id == rootPacket.packet.id && data.space == archive.descriptor.space)
            val recovery = SyncArchiveBootstrap.recover(root, account, code)
            val vault = CloudSyncVault(context, account)
            val local = device(vault)
            var keys: CloudSyncKeys? = null
            repeat(3) {
                if (keys?.contains(local.public) == true) return@repeat
                keys = recoveryKeys(account, archive, root, recovery, transport)
                if (keys!!.contains(local.public)) return@repeat
                val epochKey = SyncCipher.newKey()
                val signed = try {
                    SyncMembership.propose(
                        keys!!.membership,
                        recovery,
                        (keys!!.membership.devices + (local.public.id to local.public)).values,
                        account,
                        epochKey,
                    )
                } finally {
                    epochKey.fill(0)
                }
                val change = syncJson.decodeFromString<SyncMembershipChange>(signed.payload)
                transport.send(
                    SyncPacket(
                        kind = "control",
                        id = change.id,
                        space = data.space,
                        payload = syncJson.encodeToString(signed),
                    ),
                ) {}
                keys = recoveryKeys(account, archive, root, recovery, transport)
            }
            val accepted = requireNotNull(keys)
            check(accepted.contains(local.public)) { "Membership changed; retry recovery" }
            accepted.save(vault)
            if (activate) {
                CloudSyncSafetyCopy(context).create(accepted)
                manager.activate(accepted)
            }
        }
    }

    private suspend fun recoveryKeys(
        account: Long,
        archive: CloudSyncArchive,
        root: SyncSignedRoot,
        recovery: SyncDeviceSecret,
        transport: TelegramSyncTransport,
    ): CloudSyncKeys {
        val data = SyncArchiveBootstrap.verify(root, account)
        var members = SyncArchiveBootstrap.initial(data, archive.descriptor.rootMessage)
        val history = mutableMapOf(1L to members)
        val epochs = mutableMapOf(1L to SyncCipher.encode(SyncArchiveBootstrap.openKey(data, account, recovery)))
        val controls = sortedMapOf<Long, SyncDelivery>()
        val acceptedControls = mutableMapOf<Long, SyncSignedControl>()
        pages(transport, kinds = setOf("control")) { controls[it.receipt.messageId] = it }
        for ((message, delivery) in controls) {
            val signed = syncJson.decodeFromString<SyncSignedControl>(delivery.packet.payload)
            val change = syncJson.decodeFromString<SyncMembershipChange>(signed.payload)
            require(change.id == delivery.packet.id)
            if (change.previous != members.control) {
                require(change.epoch <= members.epoch)
                continue
            }
            members = SyncMembership.accept(members, signed, account, message).first
            history[members.epoch] = members
            acceptedControls[members.epoch] = signed
            val key = SyncMembership.openKey(change, account, recovery, data.recovery)
            try {
                epochs[members.epoch] = SyncCipher.encode(key)
            } finally {
                key.fill(0)
            }
        }
        return CloudSyncKeys(
            account = account,
            chat = archive.chat,
            root = root,
            rootMessage = archive.descriptor.rootMessage,
            membership = members,
            history = history,
            epochs = epochs,
            controls = acceptedControls,
        ).also { it.validate() }
    }

    private fun device(vault: CloudSyncVault): SyncDeviceSecret {
        val saved = vault.read("device")
        if (saved != null) {
            return try {
                SyncDeviceCrypto.importAfterLocalUnwrapping(saved)
            } finally {
                saved.fill(0)
            }
        }
        return SyncDeviceCrypto.generate(UUID.randomUUID().toString()).also {
            val clear = SyncDeviceCrypto.exportForLocalWrapping(it)
            try {
                vault.write("device", clear)
            } finally {
                clear.fill(0)
            }
        }
    }

    private fun transport(account: Long, archive: CloudSyncArchive) =
        TelegramSyncTransport(context, client, account, archive.chat, archive.descriptor.space)

    private suspend fun pages(
        transport: TelegramSyncTransport,
        kinds: Set<String>,
        receive: suspend (SyncDelivery) -> Unit,
    ) {
        var before = 0L
        do {
            val page = transport.page(before = before, kinds = kinds, receive = receive)
            if (page.complete) return
            check(page.next > 0 && (before == 0L || page.next < before))
            before = page.next
            yield()
        } while (true)
    }

    private fun idle() {
        check(!RinSessions.busy.value && !BackupRestoreJob.isRunning(context))
        check(!Injekt.get<BasePreferences>().incognitoMode().get())
    }

    private inline fun <reified T> load(vault: CloudSyncVault, slot: String): T? {
        val bytes = vault.read(slot) ?: return null
        return try {
            syncJson.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    private inline fun <reified T> save(vault: CloudSyncVault, slot: String, value: T) {
        val bytes = syncJson.encodeToString(value).toByteArray(Charsets.UTF_8)
        try {
            vault.write(slot, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    @Serializable
    private data class Creation(
        val root: SyncSignedRoot,
        val recoveryCode: String,
        val chat: Long = 0,
        val rootMessage: Long = 0,
    )

    @Serializable
    private data class Pairing(
        val request: SyncPairingRequest,
        val bundle: CloudSyncPairingBundle? = null,
        val completed: Boolean = false,
    )

    companion object {
        const val QR_PREFIX = "NYANIME-DEVICE-1:"
    }
}
