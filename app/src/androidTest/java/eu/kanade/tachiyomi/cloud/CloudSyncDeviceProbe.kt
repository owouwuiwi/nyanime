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

/** Uses isolated random databases/files only. Never reads a library, account, credential or Telegram message. */
class CloudSyncDeviceProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
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
            val reopened = CloudSyncStore(targetContext, account, space)
            store = reopened
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
            paths += File(targetContext.noBackupFilesDir, "cloud-sync-packets/$account/$space")
            report.putString(
                "stream",
                "PASS: conflicts, rewind, restart, receipts, privacy, checkpoints, compaction, immutable packets, archive reset.\n",
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
