package eu.kanade.tachiyomi.cloud

import android.content.Context
import android.content.ContextWrapper
import eu.kanade.tachiyomi.data.cloud.CloudRepository
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncDirectory
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncKeys
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncSession
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncVault
import eu.kanade.tachiyomi.data.cloud.sync.TelegramSyncTransport
import it.lorelore.nyanime.cloud.sync.CloudSyncMaintenance
import it.lorelore.nyanime.cloud.sync.SyncMaintenancePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.flag
import nyanime.telegram.number
import nyanime.telegram.objectOrNull
import nyanime.telegram.sync.SyncArchiveBootstrap
import nyanime.telegram.sync.SyncArchiveDescriptor
import nyanime.telegram.sync.SyncDeviceCrypto
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncRecoveryCode
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson
import nyanime.telegram.tdRequest
import nyanime.telegram.text
import nyanime.telegram.type
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** Explicit opt-in server probe. Only the newly created, nonce-bound private test archive is mutated. */
internal class CloudMaintenanceServerProbe(private val context: Context) {
    var stage = "connect"
        private set
    var cleanupFailure: String? = null
        private set
    var engineIdentity = "unknown"
        private set
    suspend fun run(): String = withTimeout(8 * 60_000) {
        check(eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE)
        val cloud = CloudRepository.get(context)
        val client = cloud.client
        val lease = client.retain()
        val space = UUID.randomUUID().toString()
        val rootDirectory = File(context.noBackupFilesDir, "maintenance-probe-$space")
        val sessions = mutableListOf<CloudSyncSession>()
        var createdChat = 0L
        var cleaned = false
        try {
            val account = cloud.ready()
            // Restored authorization can be ready before the network handshake finishes.
            withTimeout(45_000) { client.state.first { it.online } }
            engineIdentity = listOf("version", "commit_hash").map { option ->
                client.request(tdRequest("getOption", "name" to JsonPrimitive(option))).text("value")
                    .filter { it.isLetterOrDigit() || it in ".-_" }.take(80)
            }.joinToString("/")
            val descriptor = SyncArchiveDescriptor(space, 0)
            val created = client.request(
                tdRequest(
                    "createNewSupergroupChat",
                    "title" to JsonPrimitive("Nyanime maintenance test ${space.take(8)}"),
                    "is_channel" to JsonPrimitive(true),
                    "is_forum" to JsonPrimitive(false),
                    "description" to JsonPrimitive(descriptor.encode()),
                    "message_auto_delete_time" to JsonPrimitive(0),
                ),
            )
            createdChat = created.number("id")
            check(createdChat < 0)
            CloudProbeArchives.created(context, account, createdChat, space)
            stage = "bootstrap"
            val deviceA = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
            val deviceB = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
            val root = SyncArchiveBootstrap.create(account, space, deviceA, SyncRecoveryCode.create())
            val transport = TelegramSyncTransport(context, client, account, createdChat, space)
            transport.verifyArchive()
            val rootId = transport.send(
                SyncPacket(
                    kind = "root",
                    id = SyncArchiveBootstrap.verify(root, account).id,
                    space = space,
                    payload = syncJson.encodeToString(root),
                ),
            ) {}.messageId
            CloudSyncDirectory(client).complete(createdChat, SyncArchiveDescriptor(space, rootId))
            fun isolated(name: String): Context = object : ContextWrapper(context) {
                override fun getNoBackupFilesDir() = File(rootDirectory, name).apply { mkdirs() }
            }
            val firstContext = isolated("a")
            val first = CloudSyncSession(
                firstContext,
                client,
                CloudSyncKeys.first(account, createdChat, root, rootId, deviceA),
                deviceA,
                CloudSyncVault(firstContext, account),
                context,
            ).also(sessions::add)
            check(first.changeDevices(add = deviceB.public))
            stage = "checkpoints-and-offline-edits"
            val secondContext = isolated("b")
            val second = CloudSyncSession(
                secondContext,
                client,
                first.keys,
                deviceB,
                CloudSyncVault(secondContext, account),
                context,
            ).also(sessions::add)
            val ref = SyncRef("probe-item", 31, "/test-title", "/test-item")
            // Pseudorandom text does not collapse to a tiny compressed text packet: exercise document transport.
            val random = Random(419)
            val note = buildString { repeat(12_000) { append(('A'.code + random.nextInt(52)).toChar()) } }
            first.store.capture(ref, mapOf("position" to JsonPrimitive(100), "note" to JsonPrimitive(note)), 1)
            stage = "send-first-document"
            stage = "server-step-1"
            check(first.sendChanges())
            stage = "pull-first-document"
            stage = "server-step-2"
            while (!first.pull()) delay(25)
            val pinnedData = first.store.messagesOfKind("data", limit = 1).single()
            stage = "pin-first-document"
            client.request(
                tdRequest(
                    "pinChatMessage",
                    "chat_id" to JsonPrimitive(createdChat),
                    "message_id" to JsonPrimitive(pinnedData),
                    "disable_notification" to JsonPrimitive(true),
                ),
            )
            stage = "publish-first-checkpoint"
            stage = "server-step-3"
            val rewritten = SyncRef("probe-rewritten", 31, "/rewritten")
            for (position in listOf(10, 20, 30)) {
                first.store.capture(rewritten, mapOf("position" to JsonPrimitive(position)), 1)
            }
            repeat(220) { row ->
                first.store.capture(
                    SyncRef("probe-packed", 31, "/item/$row"),
                    mapOf(
                        "position" to JsonPrimitive(row),
                        "read" to JsonPrimitive(false),
                        "bookmark" to JsonPrimitive(false),
                        "name" to JsonPrimitive("Synthetic item $row"),
                        "seenAt" to JsonPrimitive(1000L + row),
                    ),
                    1,
                )
            }
            check(!first.checkpoints.packingReady(first.keys)) // The offline member has not announced support.
            val older = first.checkpoints.publish(first.keys)
            check(requireNotNull(first.checkpoints.verifiedCommit(older, first.keys)).formatVersion == 1)
            stage = "pull-then-publish-second-checkpoint"
            stage = "server-step-4"
            while (!first.pull()) delay(25)
            stage = "server-step-5"
            first.checkpoints.publish(first.keys)
            stage = "server-step-6"
            while (!first.pull()) delay(25)
            first.store.capture(ref, mapOf("position" to JsonPrimitive(20)), 2)
            stage = "server-step-7"
            check(first.sendChanges())
            val redundantData = first.store.messagesOfKind("data", limit = 1).single()
            stage = "server-step-8"
            while (!first.pull()) delay(25)
            // This field exists ONLY in an older snapshot: later privacy exclusions must not erase it.
            val extraRef = SyncRef("probe-extra", 31, "/extra")
            first.store.capture(extraRef, mapOf("favorite" to JsonPrimitive(true)), 2)
            stage = "server-step-9"
            first.checkpoints.publish(first.keys)
            stage = "server-step-10"
            while (!first.pull()) delay(25)
            first.store.configureExclusions("probe-private") { it == extraRef }
            second.store.configureExclusions("probe-private") { it == extraRef }
            // Offline edits exist BEFORE the receiver learns the latest checkpoint.
            second.store.capture(ref, mapOf("position" to JsonPrimitive(5)), 3)
            stage = "server-step-11"
            while (!second.pull()) delay(25)
            check(second.store.record(ref)!!.fields.getValue("position").versions.size == 2)
            stage = "server-step-12"
            check(second.sendChanges())
            stage = "server-step-13"
            while (!second.pull()) delay(25)
            stage = "server-step-14"
            val peerLegacy = second.checkpoints.publish(second.keys)
            check(requireNotNull(second.checkpoints.verifiedCommit(peerLegacy, second.keys)).formatVersion == 1)
            stage = "pull-then-publish-second-checkpoint"
            stage = "server-step-15"
            while (!first.pull()) delay(25)
            stage = "server-step-16"
            check(first.checkpoints.packingReady(first.keys))
            val packedFirst = first.checkpoints.publish(first.keys)
            val firstPack = requireNotNull(first.checkpoints.verifiedCommit(packedFirst, first.keys))
            check(firstPack.formatVersion == 2 && firstPack.parts.size == 1 && firstPack.parts.sumOf { it.chunks } > 1)
            stage = "server-step-17"
            while (!first.pull()) delay(25)
            stage = "second-packed-copy-and-portable-proof-anchors"
            val packedSecond = first.checkpoints.publish(first.keys)
            val secondPack = requireNotNull(first.checkpoints.verifiedCommit(packedSecond, first.keys))
            check(secondPack.formatVersion == 2 && secondPack.parts.size == 1 && secondPack.id != firstPack.id)
            while (!first.pull()) delay(25)
            val carriers = first.store.messagesOfKind("capability", limit = 128)
            check(carriers.size >= 2)
            for (message in carriers) {
                val value = client.request(
                    tdRequest(
                        "getMessage",
                        "chat_id" to JsonPrimitive(createdChat),
                        "message_id" to JsonPrimitive(message),
                    ),
                )
                check(value.objectOrNull("content")?.type() == "messageText")
            }
            stage = "server-step-18"
            val personal = client.request(
                tdRequest(
                    "sendMessage",
                    "chat_id" to JsonPrimitive(createdChat),
                    "input_message_content" to tdRequest(
                        "inputMessageText",
                        "text" to tdRequest("formattedText", "text" to JsonPrimitive("Unrelated personal test note")),
                        "clear_draft" to JsonPrimitive(false),
                    ),
                ),
            )
            // Resolve the temporary receipt before later assertions.
            val temporary = personal.number("id")
            val unrelated = withTimeout(20_000) {
                var confirmed = personal
                while (confirmed["sending_state"] is kotlinx.serialization.json.JsonObject ||
                    confirmed.number("id") <= 0 ||
                    confirmed.number("date") <= 0
                ) {
                    delay(100)
                    client.receipts.value[temporary]?.objectOrNull("message")?.let { confirmed = it }
                }
                confirmed.number("id")
            }
            check(unrelated > 0)
            stage = "server-step-19"
            val orphan = transport.send(
                SyncPacket(
                    kind = "checkpoint",
                    id = UUID.randomUUID().toString(),
                    space = space,
                    payload = "Uncommitted test part; never authoritative",
                ),
            ) {}
            stage = "server-step-20"
            val server = client.request(tdRequest("getOption", "name" to JsonPrimitive("unix_time"))).number("value")
            suspend fun archiveInventory(): Map<Long, Long> {
                val ids = mutableMapOf<Long, Long>()
                var before = 0L
                repeat(30) {
                    val page = client.request(
                        tdRequest(
                            "getChatHistory",
                            "chat_id" to JsonPrimitive(createdChat),
                            "from_message_id" to JsonPrimitive(before),
                            "offset" to JsonPrimitive(0),
                            "limit" to JsonPrimitive(100),
                            "only_local" to JsonPrimitive(false),
                        ),
                    )
                    val values = (page["messages"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                        .filterIsInstance<kotlinx.serialization.json.JsonObject>()
                    val next = values.minOfOrNull { it.number("id") } ?: 0L
                    if (next == 0L || next == before) return ids
                    values.forEach { value ->
                        ids[value.number("id")] = value.objectOrNull("content")?.objectOrNull("document")
                            ?.objectOrNull("document")?.number("size") ?: 0
                    }
                    before = next
                }
                error("Probe archive unexpectedly large")
            }
            val beforeCleanup = archiveInventory()
            // Accelerate the grace period ONLY in this test engine, never the phone/server clock.
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val cleaner = CloudSyncMaintenance(first, first.maintenanceIndex, {
                server + 8 * 86_400 + (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000
            }, { true })
            for (attempt in 0 until 160) {
                cleaner.step(idleBatch = true)
                if (cleaner.status.phase == SyncMaintenancePhase.RETRY) stage = "maintenance/${cleaner.lastFailureCode}"
                check(cleaner.status.phase != SyncMaintenancePhase.RETRY) { "Maintenance rejected a server proof" }
                if (first.maintenanceIndex.plan()?.complete == true) break
                // Pace this real-server probe; the production coordinator separately bounds its worker batches.
                delay(3_000)
            }
            check(first.maintenanceIndex.plan()?.complete == true)
            val afterCleanup = archiveInventory()
            val removedIds = beforeCleanup.keys - afterCleanup.keys
            val actualRemoved = removedIds.size.toLong()
            val documentBytesRemoved = removedIds.sumOf { beforeCleanup.getValue(it) }
            val beforeDocuments = beforeCleanup.count { it.value > 0 }
            val afterDocuments = afterCleanup.count { it.value > 0 }
            check(afterDocuments < beforeDocuments)
            check((firstPack.parts + secondPack.parts).all { afterCleanup.getValue(it.messageId) > 0 })
            check(documentBytesRemoved > 0)
            check(actualRemoved >= 3)
            check(first.maintenanceIndex.value("removed")?.toLongOrNull() == actualRemoved)
            check(cleaner.status.removed == actualRemoved)
            check(cleaner.status.verifiedParts == cleaner.status.totalParts)
            check(cleaner.status.examined > 0)
            stage = "verify-redundant-data-removed"
            check(transport.maintenanceMessage(redundantData) == null)
            stage = "verify-obsolete-checkpoint-removed"
            check(transport.maintenanceMessage(older.receipt.messageId) == null)
            check(transport.maintenanceMessage(peerLegacy.receipt.messageId) == null)
            stage = "verify-protected-messages"
            check(transport.maintenanceMessage(pinnedData)?.pinned == true)
            check(transport.message(rootId).packet.kind == "root")
            check(transport.message(orphan.messageId).packet.kind == "checkpoint")
            stage = "verify-unrelated-confirmed-message"
            check(
                client.request(
                    tdRequest(
                        "getMessage",
                        "chat_id" to JsonPrimitive(createdChat),
                        "message_id" to JsonPrimitive(unrelated),
                    ),
                ).number("id") == unrelated,
            )
            // A newly approved receiver already holding an obsolete commit must still recover.
            stage = "new-device-and-staged-obsolete-commit"
            val deviceC = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
            check(first.changeDevices(add = deviceC.public))
            val thirdContext = isolated("c")
            val third = CloudSyncSession(
                thirdContext,
                client,
                first.keys,
                deviceC,
                CloudSyncVault(thirdContext, account),
                context,
            ).also(sessions::add)
            third.store.capture(ref, mapOf("position" to JsonPrimitive(7)), 4)
            third.files.stage(older)
            third.store.staged(older)
            stage = "server-step-21"
            while (!third.pull()) delay(25)
            val recovered = third.store.record(ref)!!
            check(recovered.fields.getValue("note").resolved()!!.value == JsonPrimitive(note))
            check(recovered.fields.getValue("position").versions.size == 3)
            check(third.store.record(extraRef)!!.fields.getValue("favorite").resolved()!!.value == JsonPrimitive(true))
            check(third.store.pendingCount() == 1L)
            val recoveredPosition = third.store.record(rewritten)!!.fields.getValue("position").versions.single().value
            check(recoveredPosition == JsonPrimitive(30))
            check(
                third.store.record(SyncRef("probe-packed", 31, "/item/219"))!!
                    .fields.getValue("position").resolved()!!.value == JsonPrimitive(219),
            )
            second.store.capture(ref, mapOf("position" to JsonPrimitive(3)), 5)
            stage = "revocation"
            check(first.changeDevices(remove = deviceB.public.id))
            stage = "server-step-22"
            while (!second.pull()) delay(25)
            check(!second.keys.contains(deviceB.public))
            check(second.store.pendingCount() > 0)
            stage = "completed"
            "PASS: actualRemoved=$actualRemoved; documentBytesRemoved=$documentBytesRemoved; " +
                "beforeDocuments=$beforeDocuments; afterDocuments=$afterDocuments; packedRecoveryDocuments=2; " +
                "portableProofsAreText=true; overwrittenValueRecovered=30; offlineLegacyAnchorRetired=true; " +
                "examined=${cleaner.status.examined}; " +
                "verifiedParts=${cleaner.status.verifiedParts}/${cleaner.status.totalParts}; " +
                "real private test archive; document transport, two checkpoint coverage, pinned and unrelated " +
                "posts, orphan part, legacy fence, stale staged commit, new device recovery with offline edits, " +
                "revocation and retained unsent edits. No user sync archive or library mutated."
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    withTimeout(30_000) {
                        sessions.forEach { it.close() }
                        if (createdChat < 0 && client.state.value.online) {
                            val chat = client.request(tdRequest("getChat", "chat_id" to JsonPrimitive(createdChat)))
                            val group = chat.objectOrNull("type")!!.number("supergroup_id")
                            val info = client.request(
                                tdRequest("getSupergroupFullInfo", "supergroup_id" to JsonPrimitive(group)),
                            )
                            check(SyncArchiveDescriptor.parse(info.text("description"))?.space == space)
                            check(chat.flag("can_be_deleted_for_all_users"))
                            client.request(tdRequest("deleteChat", "chat_id" to JsonPrimitive(createdChat)))
                            cleaned = true
                            CloudProbeArchives.removed(context, space)
                        }
                        check(createdChat == 0L || cleaned) { "Temporary test archive cleanup needs retry" }
                    }
                } catch (error: Exception) {
                    cleanupFailure = if (error is nyanime.telegram.TelegramException) {
                        "${error.failure}/${error.code}/retry=${error.retryAfterSeconds}"
                    } else {
                        error.javaClass.simpleName
                    }
                } finally {
                    client.release(lease)
                    check(rootDirectory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
                    if (rootDirectory.exists()) rootDirectory.deleteRecursively()
                }
            }
        }
    }
}
