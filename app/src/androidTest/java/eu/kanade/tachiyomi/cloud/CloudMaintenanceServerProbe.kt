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
            val older = first.checkpoints.publish(first.keys)
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
            second.checkpoints.publish(second.keys)
            stage = "pull-then-publish-second-checkpoint"
            stage = "server-step-15"
            while (!first.pull()) delay(25)
            stage = "server-step-16"
            first.checkpoints.publish(first.keys)
            stage = "server-step-17"
            while (!first.pull()) delay(25)
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
            check((first.maintenanceIndex.value("removed")?.toLongOrNull() ?: 0) >= 3)
            stage = "verify-redundant-data-removed"
            check(transport.maintenanceMessage(redundantData) == null)
            stage = "verify-obsolete-checkpoint-removed"
            check(transport.maintenanceMessage(older.receipt.messageId) == null)
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
            second.store.capture(ref, mapOf("position" to JsonPrimitive(3)), 5)
            stage = "revocation"
            check(first.changeDevices(remove = deviceB.public.id))
            stage = "server-step-22"
            while (!second.pull()) delay(25)
            check(!second.keys.contains(deviceB.public))
            check(second.store.pendingCount() > 0)
            stage = "completed"
            "PASS: real private test archive; document transport, two checkpoint coverage, pinned and unrelated " +
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
