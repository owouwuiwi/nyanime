package eu.kanade.tachiyomi.cloud

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncPacketFiles
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncStore
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.sync.SyncBatch
import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncCipher
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncDot
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncReceipt
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.SyncVector
import nyanime.telegram.sync.syncJson
import java.io.File
import java.util.UUID

/** Default: isolated SQLite/crypto. Explicit remoteMaintenance: nonce-bound private test archive only. */
class CloudSyncDeviceProbe : Instrumentation() {
    private var localState = false
    private var cleanupOnly = false
    private var remoteMaintenance = false
    private var localCompaction = false
    override fun onCreate(arguments: Bundle?) {
        localState = arguments?.getString("localState") == "true"
        cleanupOnly = arguments?.getString("cleanupOnly") == "true"
        remoteMaintenance = arguments?.getString("remoteMaintenance") == "true"
        localCompaction = arguments?.getString("localCompaction") == "true"
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        if (localCompaction) {
            val probe = CloudCompactionDeviceProbe(targetContext)
            try {
                report.putString("stream", kotlinx.coroutines.runBlocking { probe.run() })
                finish(Activity.RESULT_OK, report)
            } catch (error: Throwable) {
                report.putString(
                    "stream",
                    "${probe.stage}: ${error.javaClass.simpleName}: ${error.message?.take(240)} at " +
                        error.stackTrace.take(6).joinToString(),
                )
                finish(Activity.RESULT_CANCELED, report)
            }
            return
        }
        if (localState || cleanupOnly) {
            try {
                val result = if (cleanupOnly) {
                    kotlinx.coroutines.runBlocking { CloudProbeArchives.cleanup(targetContext) }
                } else {
                    val stamps = targetContext.getSharedPreferences("detail-freshness-v1", 0).all.values
                        .filterIsInstance<String>().mapNotNull { value ->
                            runCatching { org.json.JSONObject(value).optLong("succeededAt", 0) }.getOrNull()
                        }
                    "WAIT_SECONDS=${CloudProbeArchives.retrySeconds(targetContext)}; " +
                        "pendingFixtures=${CloudProbeArchives.count(targetContext)}; " +
                        "detailStamps=${stamps.size}; latestDetailSuccess=${stamps.maxOrNull() ?: 0}"
                }
                report.putString("stream", result)
                finish(Activity.RESULT_OK, report)
            } catch (error: Throwable) {
                val wait = (error as? nyanime.telegram.TelegramException)?.retryAfterSeconds ?: 0
                report.putString("stream", "${error.javaClass.simpleName}; retry=$wait")
                finish(Activity.RESULT_CANCELED, report)
            }
            return
        }
        if (remoteMaintenance) {
            val probe = CloudMaintenanceServerProbe(targetContext)
            try {
                val result = kotlinx.coroutines.runBlocking { probe.run() }
                report.putString("stream", result + "\ncleanup=" + (probe.cleanupFailure ?: "confirmed"))
                finish(if (probe.cleanupFailure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
            } catch (error: Throwable) {
                val diagnostic = (error as? nyanime.telegram.TelegramException)?.let {
                    "${it.failure}/${it.code}/${it.diagnostic}"
                }.orEmpty()
                report.putString(
                    "stream",
                    "${probe.stage} ${probe.engineIdentity} $diagnostic cleanup=${probe.cleanupFailure} " +
                        error.javaClass.simpleName +
                        " at " +
                        error.stackTrace.take(10).joinToString(),
                )
                finish(Activity.RESULT_CANCELED, report)
            }
            return
        }
        val account = Long.MAX_VALUE - 101
        val space = UUID.randomUUID().toString()
        var store: CloudSyncStore? = null
        val paths = mutableListOf<File>()
        try {
            val a = UUID.randomUUID().toString()
            val b = UUID.randomUUID().toString()
            val ref = SyncRef("manga-item", 31, "/title", "/chapter/2.5")
            val initial = CloudSyncStore(targetContext, account, space).also { it.bindDevice(a) }
            store = initial
            initial.capture(ref, mapOf("position" to JsonPrimitive(100)), 1000)
            val remote = SyncChange(SyncDot(b, 1), SyncVector(), ref, "position", JsonPrimitive(10), 900_000)
            val batch =
                SyncBatch(id = UUID.randomUUID().toString(), space = space, device = b, changes = listOf(remote))
            initial.receive(batch, SyncCheckpoints.hash(syncJson.encodeToString(batch)), 10)
            initial.capture(ref, mapOf("bookmark" to JsonPrimitive(true)), 1001)
            initial.capture(ref, mapOf("position" to JsonPrimitive(50)), 1002)
            check(initial.record(ref)!!.fields["position"]!!.versions.size == 2)
            val projection = initial.projections().single()
            initial.applied(projection, projection.record.fields.mapValues { it.value.resolved()?.value })
            initial.capture(ref, mapOf("position" to JsonPrimitive(20)), 1003)
            check(initial.record(ref)!!.fields["position"]!!.versions.size == 1)

            val key = SyncCipher.newKey()
            val prepared = initial.prepare(space) {
                SyncCipher.encrypt(account, space, 1, it.id, key, syncJson.encodeToString(it).toByteArray())
            }!!
            initial.capture(ref, mapOf("position" to JsonPrimitive(5)), 1004)
            initial.close()
            // Exercise the real v1 -> v2 additive index migration without touching another database.
            val legacyPath = File(targetContext.noBackupFilesDir, "cloud-sync-$account-$space.sqlite")
            android.database.sqlite.SQLiteDatabase.openDatabase(legacyPath.path, null, 0).use {
                it.execSQL("DROP INDEX sync_wire_kind")
                it.version = 1
            }
            val reopened = CloudSyncStore(targetContext, account, space)
            store = reopened
            check(reopened.writableDatabase.version == 2)
            check(reopened.pending() == prepared)
            check(reopened.prepare(space) { error("Existing outbox must not be encrypted again") } == prepared)
            reopened.confirm(prepared.envelope.id, 11, 100)
            check(reopened.pendingCount() == 1L)
            check(reopened.record(ref)!!.fields["position"]!!.resolved()!!.value == JsonPrimitive(5))
            reopened.configureExclusions("private") { it == ref }
            check(reopened.pendingCount() == 0L)
            check(reopened.prepare(space) { error("Private edits must never be encrypted for upload") } == null)
            val snapshot = mutableListOf<SyncChange>()
            reopened.snapshot { snapshot += it }
            check(snapshot.isEmpty())
            reopened.checkpointConfirmed(100, UUID.randomUUID().toString(), 20, 101)
            reopened.configureExclusions("public") { false }
            check(reopened.pendingCount() == 1L) // An excluded edit was not falsely acknowledged.
            reopened.compactAcknowledged(10)
            check(reopened.record(ref)!!.fields["position"]!!.resolved()!!.value == JsonPrimitive(5))
            val changedDot = remote.copy(value = JsonPrimitive(999))
            val changedBatch = batch.copy(id = UUID.randomUUID().toString(), changes = listOf(changedDot))
            check(
                runCatching {
                    reopened.receive(changedBatch, SyncCheckpoints.hash(syncJson.encodeToString(changedBatch)), 21)
                }.isFailure,
            ) // Compaction retains revision tamper detection.

            val files = CloudSyncPacketFiles(targetContext, account, space)
            val delivery = SyncDelivery(SyncPacket.data(prepared.envelope), SyncReceipt(11, 100))
            files.stage(delivery)
            files.stage(delivery)
            check(
                runCatching {
                    files.stage(delivery.copy(packet = delivery.packet.copy(payload = "modified")))
                }.isFailure,
            )
            check(files.delivery(11) == delivery)
            files.prepare(delivery.packet)
            files.resetReceived()
            check(runCatching { files.delivery(11) }.isFailure)
            check(files.prepared(delivery.packet.id) == delivery.packet)
            files.removePrepared(delivery.packet.id)
            val beforeReset = reopened.record(ref)
            val pendingBeforeReset = reopened.pendingCount()
            reopened.state("received", "999999")
            reopened.state("processed", "999999")
            reopened.resetArchive(-2)
            check(reopened.state("received") == null && reopened.state("processed") == null)
            check(reopened.state("initial-checkpoint") == "true" && reopened.state("transport-chat") == "-2")
            check(reopened.record(ref) == beforeReset && reopened.pendingCount() == pendingBeforeReset)
            val index = it.lorelore.nyanime.cloud.sync.SyncMaintenanceIndex(targetContext, account, space, -2)
            val checkpointIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            val proofs = checkpointIds.mapIndexed { n, id ->
                val commit = nyanime.telegram.sync.SyncCheckpointCommit(
                    id = id,
                    space = space,
                    epoch = 1,
                    author = a,
                    throughMessage = 10,
                    parts = emptyList(),
                )
                it.lorelore.nyanime.cloud.sync.MaintenanceProof(
                    SyncDelivery(
                        SyncPacket(kind = "commit", id = id, space = space, payload = "local-index-test"),
                        SyncReceipt(100L + n, 100),
                    ),
                    commit,
                )
            }
            val plan = it.lorelore.nyanime.cloud.sync.MaintenancePlan(
                UUID.randomUUID().toString(),
                100,
                proofs,
                emptyList(),
                false,
                before = 100,
            )
            index.reset(plan)
            val earlier = remote.copy(dot = SyncDot(b, 1), context = SyncVector())
            val later = earlier.copy(dot = SyncDot(b, 2), context = SyncVector(mapOf(b to 1)), value = JsonPrimitive(3))
            index.addPart(checkpointIds[0], listOf(later), plan)
            check(!index.covers(plan, listOf(earlier))) // One snapshot is insufficient.
            index.close()
            val reloadedIndex = it.lorelore.nyanime.cloud.sync.SyncMaintenanceIndex(targetContext, account, space, -2)
            check(reloadedIndex.plan() == plan)
            reloadedIndex.addPart(checkpointIds[1], listOf(later), plan)
            check(reloadedIndex.covers(plan, listOf(earlier, later)))
            check(!reloadedIndex.covers(plan, listOf(earlier.copy(dot = SyncDot(a, 1)))))
            check(
                runCatching {
                    reloadedIndex.addPart(
                        checkpointIds[0],
                        listOf(later.copy(value = JsonPrimitive(999))),
                        plan.copy(before = 9),
                    )
                }.isFailure,
            )
            check(reloadedIndex.plan() == plan) // Failed part and cursor roll back together.
            reloadedIndex.advance(plan.copy(before = 9), 2)
            check(reloadedIndex.plan()?.before == 9L && reloadedIndex.value("removed") == "2")
            reloadedIndex.close()
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                File(targetContext.noBackupFilesDir, "cloud-sync-maintenance-$account-$space--2.sqlite$suffix").delete()
            }
            paths += File(targetContext.noBackupFilesDir, "cloud-sync-packets/$account/$space")
            report.putString(
                "stream",
                "PASS: conflicts, rewind, restart, receipts, privacy, checkpoints, compaction, immutable packets, " +
                    "archive reset, v1/v2 migration, two-checkpoint causal index, part/cursor transaction rollback.\n",
            )
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("stream", error.javaClass.simpleName + " at " + error.stackTrace.take(8).joinToString())
            finish(Activity.RESULT_CANCELED, report)
        } finally {
            store?.close()
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                File(targetContext.noBackupFilesDir, "cloud-sync-$account-$space.sqlite$suffix").delete()
            }
            paths.forEach { it.delete() } // Empty random test directories only; no recursive cleanup.
        }
    }
}
