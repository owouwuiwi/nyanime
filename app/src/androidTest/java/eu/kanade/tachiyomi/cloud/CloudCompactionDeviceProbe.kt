package eu.kanade.tachiyomi.cloud

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncCheckpoints
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncKeys
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncSession
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncVault
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import it.lorelore.nyanime.cloud.sync.SyncCheckpointPackIndex
import it.lorelore.nyanime.cloud.sync.SyncWorkStage
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.sync.SyncArchiveBootstrap
import nyanime.telegram.sync.SyncBatchSignature
import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncCheckpointChunk
import nyanime.telegram.sync.SyncCheckpointCommit
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncCipher
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncDeviceCrypto
import nyanime.telegram.sync.SyncDeviceSecret
import nyanime.telegram.sync.SyncEnvelope
import nyanime.telegram.sync.SyncMembership
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncReaderCapabilities
import nyanime.telegram.sync.SyncReceipt
import nyanime.telegram.sync.SyncRecoveryCode
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.random.Random

/** Actual SQLite/Keystore/AtomicFile, synthetic isolated data and receipts. No network calls. */
internal class CloudCompactionDeviceProbe(private val context: Context) {
    var stage = "start"
        private set
    suspend fun run(): String = withTimeout(10 * 60_000) {
        check(eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE)
        val started = SystemClock.elapsedRealtime()
        val account = Long.MAX_VALUE - 123
        val space = UUID.randomUUID().toString()
        val root = File(context.noBackupFilesDir, "compaction-probe-$space")
        val sessions = mutableListOf<CloudSyncSession>()
        fun id() = UUID.randomUUID().toString()
        fun isolated(name: String) = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(root, name).apply { check(isDirectory || mkdirs()) }
        }
        val owner = SyncDeviceCrypto.generate(id())
        val reader = SyncDeviceCrypto.generate(id())
        val initial = CloudSyncKeys.first(
            account,
            -100,
            SyncArchiveBootstrap.create(account, space, owner, SyncRecoveryCode.create()),
            1,
            owner,
        )
        val newKey = SyncCipher.newKey()
        val control = try {
            SyncMembership.propose(initial.membership, owner, listOf(owner.public, reader.public), account, newKey)
        } finally {
            newKey.fill(0)
        }
        val keys = initial.accept(control, 2, owner)
        val receiverKeys = initial.accept(control, 2, reader)
        val sourceContext = isolated("source")
        val client = TelegramClient.get(context)
        fun open(target: Context, localKeys: CloudSyncKeys, device: SyncDeviceSecret) =
            CloudSyncSession(target, client, localKeys, device, CloudSyncVault(target, account)).also(sessions::add)
        fun hash(file: File): String = file.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        var maximumHeap = 0L
        fun heap() {
            val runtime = Runtime.getRuntime()
            maximumHeap = maxOf(maximumHeap, runtime.totalMemory() - runtime.freeMemory())
        }
        try {
            var source = open(sourceContext, keys, owner)
            stage = "resume-old-prepared-commit"
            val oldCommit = SyncCheckpointCommit(
                id = id(),
                space = space,
                epoch = keys.epoch,
                author = owner.public.id,
                throughMessage = 2,
                parts = emptyList(),
                maintenanceVersion = 1,
            )
            val oldSigned = SyncCheckpoints.sign(oldCommit, account, owner)
            val oldEnvelope = SyncCipher.encrypt(
                account,
                space,
                keys.epoch,
                oldCommit.id,
                keys.key(),
                syncJson.encodeToString(oldSigned).toByteArray(),
            )
            val oldDelivery = SyncDelivery(
                SyncPacket(
                    kind = "commit",
                    id = oldCommit.id,
                    space = space,
                    payload = syncJson.encodeToString(oldEnvelope),
                ),
                SyncReceipt(3, 1),
            )
            source.files.prepare(oldDelivery.packet)
            source.files.stage(oldDelivery)
            source.store.staged(oldDelivery)
            source.store.state(
                "checkpoint-pending",
                syncJson.encodeToString(
                    CloudSyncCheckpoints.Prepared(oldCommit.id, keys.epoch, 0, 2, emptyList(), commitPrepared = true),
                ),
            )
            check(source.checkpoints.publish(keys) == oldDelivery)
            check(source.store.state("packing-announced").isNullOrEmpty())
            check(!source.checkpoints.packingReady(keys))
            val position = SyncRef("probe-item", 31, "/position")
            stage = "coalesce-unsent"
            for (value in listOf(10, 20, 30)) {
                source.store.capture(position, mapOf("position" to JsonPrimitive(value)), 1)
            }
            var captured = emptyList<SyncChange>()
            val pending = requireNotNull(
                source.store.prepare(space) { batch ->
                    captured = batch.changes
                    val signedBatch = SyncBatchSignature.sign(batch, account, keys.epoch, owner)
                    val clear = syncJson.encodeToString(signedBatch).toByteArray()
                    SyncCipher.encrypt(account, space, keys.epoch, batch.id, keys.key(), clear)
                },
            )
            check(captured.size == 1 && captured.single().value == JsonPrimitive(30))
            check(source.store.pendingCount() == 1L)
            source.store.capture(position, mapOf("position" to JsonPrimitive(40)), 2)
            source.store.capture(position, mapOf("bookmark" to JsonPrimitive(true)), 2)
            val unchanged = source.store.prepare(space) { error("Uncertain envelope must remain unchanged") }
            check(unchanged == pending)
            source.store.confirm(pending.envelope.id, 4, 1)
            source.store.prepare(space) { batch ->
                check(batch.changes.size == 2 && batch.changes.any { it.value == JsonPrimitive(40) })
                check(batch.changes.any { it.field == "bookmark" })
                val signedBatch = SyncBatchSignature.sign(batch, account, keys.epoch, owner)
                val clear = syncJson.encodeToString(signedBatch).toByteArray()
                SyncCipher.encrypt(account, space, keys.epoch, batch.id, keys.key(), clear)
            }
            stage = "large-fixture"
            val random = Random(71023)
            repeat(2048) { row ->
                val fields = (0 until 8).associate { field ->
                    "field$field" to JsonPrimitive(
                        buildString {
                            repeat(96) { append(('A'.code + random.nextInt(26)).toChar()) }
                        },
                    )
                }
                source.store.capture(SyncRef("probe-item", 31, "/synthetic/$row"), fields, 3)
                if (row % 128 == 0) heap()
            }
            stage = "legacy-reference"
            val reference = File(root, "reference-chunks").apply { mkdirs() }
            val legacyId = id()
            var legacyBlocks = 0
            var legacyBytes = 0L
            source.store.snapshot { changes ->
                val chunk = SyncCheckpointChunk(checkpoint = legacyId, changes = changes)
                val envelope = SyncCipher.encrypt(
                    account,
                    space,
                    keys.epoch,
                    id(),
                    keys.key(),
                    syncJson.encodeToString(chunk).toByteArray(),
                )
                val packet = SyncPacket(
                    kind = "checkpoint",
                    id = envelope.id,
                    space = space,
                    payload = syncJson.encodeToString(envelope),
                )
                val bytes = syncJson.encodeToString(packet).toByteArray()
                File(reference, "${legacyBlocks++}.nysync").writeBytes(bytes)
                legacyBytes += bytes.size
            }
            check(legacyBlocks > 1)
            stage = "signed-capability-migration"
            for ((index, member) in listOf(owner, reader).withIndex()) {
                val proof = SyncReaderCapabilities.sign(account, keys.membership, member)
                val commit = SyncCheckpointCommit(
                    id = id(), space = space, epoch = keys.epoch,
                    author = member.public.id, throughMessage = 0, parts = emptyList(),
                    maintenanceVersion = 1, packingVersion = 1, readerCapability = proof,
                )
                val encrypted = SyncCipher.encrypt(
                    account,
                    space,
                    keys.epoch,
                    commit.id,
                    keys.key(),
                    syncJson.encodeToString(SyncCheckpoints.sign(commit, account, member)).toByteArray(),
                )
                val delivery = SyncDelivery(
                    SyncPacket(
                        kind = "commit",
                        id = commit.id,
                        space = space,
                        payload = syncJson.encodeToString(encrypted),
                    ),
                    SyncReceipt(10L + index, 1),
                )
                source.files.stage(delivery)
                source.store.staged(delivery)
            }
            source.store.state("received", "11")
            source.consumeStaged()
            check(source.checkpoints.packingReady(keys))
            source.checkpoints.onProgress = { heap() }
            stage = "pack-and-reopen"
            val plan = source.checkpoints.prepare(keys)
            check(plan.packed && plan.parts.size < legacyBlocks)
            check(plan.parts.sumOf { it.chunks } == legacyBlocks)
            val hashes = plan.parts.associate { it.id to hash(requireNotNull(source.files.packFile(it.id, true))) }
            stage = "interrupted-upload-file"
            val uploadPart = plan.parts.first()
            val upload = File(root, "uploads/${uploadPart.id}.nysync")
            source.files.materializePack(uploadPart.id, upload)
            check(hash(upload) == hashes[uploadPart.id])
            check(source.files.materializePack(uploadPart.id, upload) == upload)
            upload.copyTo(File(upload.path + ".bak"))
            upload.writeText("Interrupted atomic upload copy")
            source.files.materializePack(uploadPart.id, upload)
            check(hash(upload) == hashes[uploadPart.id] && !File(upload.path + ".bak").exists())
            File(upload.path + ".new").writeText("Interrupted new-style atomic copy")
            source.files.materializePack(uploadPart.id, upload)
            check(hash(upload) == hashes[uploadPart.id])
            upload.writeText("Unrecognized changed upload identity")
            val unknown = hash(upload)
            check(runCatching { source.files.materializePack(uploadPart.id, upload) }.isFailure)
            check(hash(upload) == unknown) // Never silently overwrite an unknown identity.
            source.close()
            source = open(sourceContext, keys, owner)
            check(source.checkpoints.prepare(keys) == plan)
            check(plan.parts.all { hash(requireNotNull(source.files.packFile(it.id, true))) == hashes[it.id] })
            var packedBytes = 0L
            val parts = plan.parts.mapIndexed { index, part -> part.copy(messageId = 100L + index) }
            val commit = SyncCheckpointCommit(
                id = plan.id, space = space, epoch = keys.epoch,
                author = owner.public.id, throughMessage = 11, parts = parts, maintenanceVersion = 1,
                packingVersion = 1, formatVersion = 2,
                readerCapability = SyncReaderCapabilities.sign(account, keys.membership, owner),
            )
            val encrypted = SyncCipher.encrypt(
                account,
                space,
                keys.epoch,
                commit.id,
                keys.key(),
                syncJson.encodeToString(SyncCheckpoints.sign(commit, account, owner)).toByteArray(),
            )
            val committed = SyncDelivery(
                SyncPacket(
                    kind = "commit",
                    id = commit.id,
                    space = space,
                    payload = syncJson.encodeToString(encrypted),
                ),
                SyncReceipt(999, 1),
            )
            fun stage(target: CloudSyncSession) {
                parts.forEach { part ->
                    val file = requireNotNull(source.files.packFile(part.id, true))
                    val packet = target.files.stagePack(file, part.id)
                    val delivery = SyncDelivery(packet, SyncReceipt(part.messageId, 1))
                    target.files.stage(delivery)
                    target.store.staged(delivery)
                }
                target.files.stage(committed)
                target.store.staged(committed)
            }
            stage = "cold-restore-with-offline-change"
            val restored = open(isolated("receiver"), receiverKeys, reader)
            restored.store.capture(position, mapOf("position" to JsonPrimitive(7)), 4)
            stage(restored)
            restored.checkpoints.onProgress = { heap() }
            check(restored.checkpoints.restore(committed, receiverKeys) == 11L)
            check(restored.store.pendingCount() == 1L)
            check(restored.store.record(position)!!.fields.getValue("position").versions.size == 2)
            repeat(2048) { row ->
                val ref = SyncRef("probe-item", 31, "/synthetic/$row")
                check(source.store.record(ref) == restored.store.record(ref))
            }
            stage = "transaction-rollback-after-validation"
            val rollback = open(isolated("rollback"), receiverKeys, reader)
            rollback.store.capture(position, mapOf("position" to JsonPrimitive(7)), 4)
            stage(rollback)
            var corrupted = false
            rollback.checkpoints.onProgress = { progress ->
                if (!corrupted && progress.stage == SyncWorkStage.RESTORING && progress.completed == progress.total) {
                    corrupted = true
                    val first = parts.first()
                    val parent = source.files.prepared(first.id)
                    val envelope = syncJson.decodeFromString<SyncEnvelope>(parent.payload)
                    val manifest = syncJson.decodeFromString<SyncCheckpointPackIndex>(
                        SyncCipher.decrypt(account, space, keys.key(), envelope).toString(Charsets.UTF_8),
                    )
                    val child = manifest.chunks[1]
                    RandomAccessFile(requireNotNull(rollback.files.packFile(first.id)), "rw").use { file ->
                        file.seek("NYANIME_SYNC_PACK_1\n".length.toLong())
                        val header = file.readInt()
                        file.seek(file.filePointer + header + child.offset)
                        val length = file.readInt()
                        val start = file.filePointer
                        val raw = ByteArray(length).also(file::readFully)
                        val packet = syncJson.decodeFromString<SyncPacket>(raw.decodeToString())
                        val body = syncJson.decodeFromString<SyncEnvelope>(packet.payload)
                        val cipher = (if (body.ciphertext[0] == 'A') "B" else "A") + body.ciphertext.drop(1)
                        val changed = syncJson.encodeToString(
                            packet.copy(payload = syncJson.encodeToString(body.copy(ciphertext = cipher))),
                        ).toByteArray()
                        check(changed.size == length)
                        file.seek(start)
                        file.write(changed)
                    }
                }
            }
            check(runCatching { rollback.checkpoints.restore(committed, receiverKeys) }.isFailure)
            check(corrupted)
            val rolledBack = rollback.store.record(position)!!.fields.getValue("position").versions.single().value
            check(rolledBack == JsonPrimitive(7))
            rollback.store.readableDatabase.rawQuery("SELECT count(*) FROM records", null).use {
                check(it.moveToFirst() && it.getLong(0) == 1L)
            }
            check(rollback.store.pendingCount() == 1L)
            stage = "recover-corrupt-cache-and-retry"
            parts.forEach { rollback.files.removeDelivery(it.messageId) }
            stage(rollback)
            rollback.checkpoints.onProgress = { heap() }
            rollback.checkpoints.restore(committed, receiverKeys)
            check(rollback.store.record(SyncRef("probe-item", 31, "/synthetic/2047")) != null)
            parts.forEach { packedBytes += requireNotNull(source.files.packFile(it.id, true)).length() }
            check(packedBytes <= legacyBytes * 105 / 100 + 8192)
            stage = "completed"
            "PASS localCompaction: syntheticRecords=2049; " +
                "legacyDocuments=$legacyBlocks; packedDocuments=${parts.size}; " +
                "legacyBytes=$legacyBytes; packedBytes=$packedBytes; sampledHeapMaxBytes=$maximumHeap; " +
                "durationMs=${SystemClock.elapsedRealtime() - started}; coalesced=10/20/30->30; " +
                "oldPreparedCommitResumed=true; inflightUnchanged=true; atomicUploadRecovery=true; " +
                "signedCapabilities=true; stablePreparedRestart=true; " +
                "coldRestore=true; offlineChangePreserved=true; transactionRollback=true; corruptCacheRetry=true; " +
                "networkRequests=0; actualServerReceipts=false"
        } finally {
            sessions.forEach { runCatching { it.close() } }
            check(root.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
            if (root.exists()) check(root.deleteRecursively())
        }
    }
}
