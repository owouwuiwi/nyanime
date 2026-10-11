package eu.kanade.tachiyomi.data.cloud.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import nyanime.telegram.sync.SyncBatch
import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncDot
import nyanime.telegram.sync.SyncEnvelope
import nyanime.telegram.sync.SyncRecord
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.SyncVector
import nyanime.telegram.sync.syncJson
import java.io.File
import java.util.UUID

/**
 * One account/space per database. Local observations, causal revisions and the outgoing journal
 * commit together. Applying a remote batch and queuing its local projection is also atomic.
 * Source databases acknowledge their dirty rows only AFTER capture returns successfully.
 */
internal class CloudSyncStore(context: Context, account: Long, private val space: String) : SQLiteOpenHelper(
    context,
    File(context.noBackupFilesDir, "cloud-sync-$account-$space.sqlite").path,
    null,
    1,
) {
    private var excluded: (SyncRef) -> Boolean = { false }
    init {
        require(account > 0 && UUID.fromString(space).toString() == space)
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE records (key TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE baseline (key TEXT NOT NULL, field TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(key, field))",
        )
        db.execSQL(
            "CREATE TABLE changes (device TEXT NOT NULL, sequence INTEGER NOT NULL, data TEXT, digest TEXT NOT NULL, " +
                "outgoing INTEGER NOT NULL, batch TEXT, ref TEXT NOT NULL, PRIMARY KEY(device, sequence))",
        )
        db.execSQL("CREATE INDEX sync_unsent ON changes(outgoing, batch, sequence)")
        db.execSQL("CREATE TABLE excluded (key TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE outbox (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE inbox (id TEXT PRIMARY KEY, digest TEXT NOT NULL, message INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE projection (key TEXT PRIMARY KEY, revision INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE wire (message INTEGER PRIMARY KEY, id TEXT NOT NULL, kind TEXT NOT NULL, " +
                "date INTEGER NOT NULL, digest TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE observations (key TEXT NOT NULL, field TEXT NOT NULL, vector TEXT NOT NULL, " +
                "PRIMARY KEY(key, field))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Re-evaluate privacy before preparing envelopes, including edits queued while offline. */
    @Synchronized
    fun configureExclusions(signature: String, predicate: (SyncRef) -> Boolean) = transaction { db ->
        excluded = predicate
        if (state("exclusions") == signature) return@transaction
        db.delete("excluded", null, null)
        db.query("records", arrayOf("key", "data"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val record = syncJson.decodeFromString<SyncRecord>(cursor.getString(1))
                if (excluded(record.ref)) replace(db, "excluded", "key" to cursor.getString(0))
            }
        }
        rebaseOutbox()
        state("exclusions", signature)
    }

    @Synchronized
    fun isExcluded(ref: SyncRef): Boolean = excluded(ref)

    @Synchronized
    fun state(key: String): String? = value(readableDatabase, "state", "value", "key=?", arrayOf(key))

    @Synchronized
    fun state(key: String, value: String) {
        replace(writableDatabase, "state", "key" to key, "value" to value)
    }

    @Synchronized
    fun pendingIntents(prefix: String): List<Pair<String, Projection>> = readableDatabase.query(
        "state",
        arrayOf("key", "value"),
        "key LIKE ? AND value<>''",
        arrayOf("$prefix%"),
        null,
        null,
        "key",
        "100",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0) to syncJson.decodeFromString(cursor.getString(1)))
        }
    }

    @Synchronized
    fun device(): String = transaction { db ->
        value(db, "state", "value", "key=?", arrayOf("device")) ?: UUID.randomUUID().toString().also {
            replace(db, "state", "key" to "device", "value" to it)
        }
    }

    @Synchronized
    fun bindDevice(id: String) {
        require(UUID.fromString(id).toString() == id)
        val existing = state("device")
        check(existing == null || existing == id)
        state("device", id)
    }

    /** The caller has already atomically written the encrypted file. */
    @Synchronized
    fun staged(delivery: SyncDelivery) = transaction { db ->
        val message = delivery.receipt.messageId
        val digest = SyncCheckpoints.hash(syncJson.encodeToString(delivery.packet))
        val previous = value(db, "wire", "digest", "message=?", arrayOf(message.toString()))
        check(previous == null || previous == digest) { "Synchronization message changed after receipt" }
        replace(
            db,
            "wire",
            "message" to message.toString(),
            "id" to delivery.packet.id,
            "kind" to delivery.packet.kind,
            "date" to delivery.receipt.serverDate.toString(),
            "digest" to digest,
        )
    }

    @Synchronized
    fun nextMessage(after: Long): Long? = readableDatabase.rawQuery(
        "SELECT message FROM wire WHERE message>? ORDER BY message LIMIT 1",
        arrayOf(after.toString()),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    @Synchronized
    fun messageFor(id: String): Long? =
        value(readableDatabase, "wire", "message", "id=?", arrayOf(id))?.toLong()

    @Synchronized
    fun pending(): Pending? = value(readableDatabase, "outbox", "data", null, null)
        ?.let { syncJson.decodeFromString(it) }

    /** Stable operation dots survive re-encryption after a membership/key change. */
    @Synchronized
    fun rebaseOutbox() = transaction { db ->
        db.execSQL("UPDATE changes SET batch=NULL WHERE outgoing=1")
        db.delete("outbox", null, null)
    }

    /** New Telegram message IDs must not inherit cursors from an archive that no longer exists. */
    @Synchronized
    fun resetArchive(chat: Long) = transaction { db ->
        require(chat < 0)
        rebaseOutbox()
        db.delete("wire", null, null)
        db.delete("inbox", null, null)
        listOf(
            "received", "processed", "pull-before", "pull-top", "compact-through", "compacted-through",
            "checkpoint", "checkpoint-pending", "lastCheckpoint", "lastSent", "lastVerified", "membership-pending",
        ).forEach {
            db.delete("state", "key=?", arrayOf(it))
        }
        replace(db, "state", "key" to "initial-checkpoint", "value" to "true")
        replace(db, "state", "key" to "transport-chat", "value" to chat.toString())
    }

    /** Bounded chunks from one consistent snapshot, including removals and concurrent values. */
    @Synchronized
    fun snapshot(write: (List<SyncChange>) -> Unit): Long = transaction { db ->
        var bytes = 0
        val chunk = mutableListOf<SyncChange>()
        db.query("records", arrayOf("data"), null, null, null, null, "key").use { cursor ->
            while (cursor.moveToNext()) {
                val record = syncJson.decodeFromString<SyncRecord>(cursor.getString(0))
                if (excluded(record.ref)) continue
                record.fields.values.forEach { register ->
                    register.versions.forEach { change ->
                        val size = syncJson.encodeToString(change).toByteArray(Charsets.UTF_8).size
                        if (chunk.isNotEmpty() && (bytes + size > 2 * 1024 * 1024 || chunk.size == 1024)) {
                            write(chunk.toList())
                            chunk.clear()
                            bytes = 0
                        }
                        chunk += change
                        bytes += size
                    }
                }
            }
        }
        if (chunk.isNotEmpty()) write(chunk.toList())
        value(db, "state", "value", "key=?", arrayOf("sequence"))?.toLong() ?: 0
    }

    /** Validation of every encrypted chunk must finish before entering this transaction. */
    @Synchronized
    fun restoreSnapshot(checkpoint: String, message: Long, chunks: Sequence<List<SyncChange>>) = transaction { db ->
        require(UUID.fromString(checkpoint).toString() == checkpoint && message > 0)
        chunks.forEachIndexed { index, changes ->
            changes.groupBy { it.dot.device }.forEach { (author, values) ->
                val id = UUID.nameUUIDFromBytes(
                    "checkpoint|$checkpoint|$index|$author".toByteArray(Charsets.UTF_8),
                ).toString()
                val batch = SyncBatch(id = id, space = space, device = author, changes = values)
                receive(batch, SyncCheckpoints.hash(syncJson.encodeToString(batch)), message)
            }
        }
        replace(db, "state", "key" to "checkpoint", "value" to checkpoint)
    }

    @Synchronized
    fun checkpointConfirmed(sequence: Long, id: String, message: Long, serverDate: Long) = transaction { db ->
        require(sequence >= 0 && message > 0 && serverDate > 0)
        db.execSQL(
            "UPDATE changes SET outgoing=0 WHERE device=? AND sequence<=? AND ref NOT IN (SELECT key FROM excluded)",
            arrayOf<Any>(device(), sequence),
        )
        db.execSQL(
            "DELETE FROM outbox WHERE id NOT IN (SELECT batch FROM changes WHERE outgoing=1 AND batch IS NOT NULL)",
        )
        replace(db, "state", "key" to "checkpoint", "value" to id)
        replace(db, "state", "key" to "lastSent", "value" to serverDate.toString())
        replace(db, "state", "key" to "lastCheckpoint", "value" to serverDate.toString())
    }

    /** Retain causal state/tombstones and compact revision proofs after a complete checkpoint. */
    @Synchronized
    fun compactAcknowledged(through: Long): List<Long> = transaction { db ->
        require(through >= 0)
        if ((state("compacted-through")?.toLongOrNull() ?: 0) >= through) return@transaction emptyList()
        db.execSQL(
            "UPDATE changes SET data=NULL WHERE outgoing=0 AND data IS NOT NULL AND ref NOT IN (SELECT key FROM excluded)",
        )
        val messages = db.rawQuery(
            "SELECT message FROM wire WHERE kind='data' AND message<=? ORDER BY message LIMIT 100",
            arrayOf(through.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        messages.forEach { db.delete("wire", "message=?", arrayOf(it.toString())) }
        if (messages.size < 100) state("compacted-through", through.toString())
        messages
    }

    @Synchronized
    fun record(ref: SyncRef): SyncRecord? = record(readableDatabase, ref.key())

    @Synchronized
    fun baseline(ref: SyncRef, field: String): JsonElement? =
        value(readableDatabase, "baseline", "value", "key=? AND field=?", arrayOf(ref.key(), field))
            ?.let(syncJson::parseToJsonElement)

    @Synchronized
    fun baselineFields(ref: SyncRef): Set<String> = readableDatabase.query(
        "baseline",
        arrayOf("field"),
        "key=?",
        arrayOf(ref.key()),
        null,
        null,
        null,
    ).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    @Synchronized
    fun capture(ref: SyncRef, fields: Map<String, JsonElement?>, timeMillis: Long): Int = transaction { db ->
        ref.validate()
        val key = ref.key()
        if (excluded(ref)) replace(db, "excluded", "key" to key)
        val device = device()
        // Receipt is not observation. A local edit made while the remote projection is pending
        // remains concurrent with it, rather than falsely claiming to have superseded it.
        var sequence = value(db, "state", "value", "key=?", arrayOf("sequence"))?.toLong() ?: 0L
        var record = record(db, key) ?: SyncRecord(ref)
        var count = 0
        fields.toSortedMap().forEach { (field, content) ->
            val serialized = content?.toString() ?: "null"
            val previous = value(db, "baseline", "value", "key=? AND field=?", arrayOf(key, field))
            if (previous != serialized) {
                val context = observation(db, key, field).copy(
                    counters = observation(db, key, field).counters + (device to sequence),
                )
                sequence = Math.addExact(sequence, 1)
                val dot = SyncDot(device, sequence)
                val change = SyncChange(dot, context, ref, field, content, timeMillis)
                change.validate()
                insertChange(db, change, outgoing = true)
                record = record.merge(change)
                observe(db, key, field, context.including(dot))
                replace(db, "baseline", "key" to key, "field" to field, "value" to serialized)
                count++
            }
        }
        if (count > 0) {
            replace(db, "records", "key" to key, "data" to syncJson.encodeToString(record))
            replace(db, "state", "key" to "sequence", "value" to sequence.toString())
            saveVector(db, vector(db).including(SyncDot(device, sequence)))
            val revision = value(db, "projection", "revision", "key=?", arrayOf(key))?.toLong()
            if (revision != null) {
                replace(db, "projection", "key" to key, "revision" to Math.addExact(revision, 1).toString())
            }
        }
        count
    }

    /**
     * The caller verifies the encrypted envelope, membership and signature before calling this.
     * An unknown version must stop processing, never be silently acknowledged.
     */
    @Synchronized
    fun receive(batch: SyncBatch, digest: String, messageId: Long) = transaction { db ->
        batch.validate()
        require(batch.space == space)
        require(messageId > 0 && digest.matches(Regex("[a-f0-9]{64}")))
        val previous = value(db, "inbox", "digest", "id=?", arrayOf(batch.id))
        if (previous != null) {
            check(previous == digest) { "Sync batch identity reused" }
            return@transaction
        }
        var vector = vector(db)
        batch.changes.groupBy { it.ref.key() }.forEach { (key, changes) ->
            if (excluded(changes.first().ref)) replace(db, "excluded", "key" to key)
            var record = record(db, key) ?: SyncRecord(changes.first().ref)
            var changed = false
            changes.forEach { change ->
                val existing = value(
                    db,
                    "changes",
                    "digest",
                    "device=? AND sequence=?",
                    arrayOf(change.dot.device, change.dot.sequence.toString()),
                )
                if (existing != null) {
                    check(existing == SyncCheckpoints.hash(syncJson.encodeToString(change))) { "Sync revision reused" }
                } else {
                    insertChange(db, change, outgoing = false)
                    val merged = record.merge(change)
                    changed = changed || merged != record
                    record = merged
                }
                vector = vector.merged(change.context).including(change.dot)
            }
            if (changed) {
                replace(db, "records", "key" to key, "data" to syncJson.encodeToString(record))
                val revision = value(db, "projection", "revision", "key=?", arrayOf(key))?.toLong() ?: 0
                replace(db, "projection", "key" to key, "revision" to Math.addExact(revision, 1).toString())
            }
        }
        saveVector(db, vector)
        replace(db, "inbox", "id" to batch.id, "digest" to digest, "message" to messageId.toString())
    }

    /** Read a bounded batch. Network retries reuse the persisted envelope, never a new identity. */
    @Synchronized
    fun prepare(space: String, encrypt: (SyncBatch) -> SyncEnvelope): Pending? = transaction { db ->
        require(space == this.space)
        val existing = value(db, "outbox", "data", null, null)
        if (existing != null) return@transaction syncJson.decodeFromString<Pending>(existing)
        var bytes = 0
        val changes = db.query(
            "changes",
            arrayOf("data"),
            "outgoing=1 AND batch IS NULL AND ref NOT IN (SELECT key FROM excluded)",
            null,
            null,
            null,
            "sequence",
            "1024",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val data = cursor.getString(0)
                    bytes += data.toByteArray(Charsets.UTF_8).size
                    if (isNotEmpty() && bytes > 2 * 1024 * 1024) break
                    add(syncJson.decodeFromString<SyncChange>(data))
                }
            }
        }
        if (changes.isEmpty()) return@transaction null
        val batch = SyncBatch(id = UUID.randomUUID().toString(), space = space, device = device(), changes = changes)
        batch.validate()
        val envelope = encrypt(batch)
        require(envelope.id == batch.id && envelope.space == space)
        val pending = Pending(envelope)
        replace(db, "outbox", "id" to batch.id, "data" to syncJson.encodeToString(pending))
        changes.forEach {
            db.execSQL(
                "UPDATE changes SET batch=? WHERE device=? AND sequence=?",
                arrayOf<Any>(batch.id, it.dot.device, it.dot.sequence),
            )
        }
        pending
    }

    @Synchronized
    fun sending(pending: Pending) = transaction { db ->
        check(value(db, "outbox", "id", "id=?", arrayOf(pending.envelope.id)) != null)
        replace(db, "outbox", "id" to pending.envelope.id, "data" to syncJson.encodeToString(pending))
    }

    /** Only a server-confirmed message or a matching historical receipt may drain the outbox. */
    @Synchronized
    fun confirm(id: String, messageId: Long, serverDate: Long) = transaction { db ->
        require(messageId > 0 && serverDate > 0)
        check(value(db, "outbox", "id", "id=?", arrayOf(id)) != null)
        db.execSQL("UPDATE changes SET outgoing=0 WHERE batch=?", arrayOf(id))
        db.delete("outbox", "id=?", arrayOf(id))
        replace(db, "state", "key" to "lastSent", "value" to serverDate.toString())
    }

    @Synchronized
    fun pendingCount(): Long = readableDatabase.rawQuery(
        "SELECT count(*) FROM changes WHERE outgoing=1 AND ref NOT IN (SELECT key FROM excluded)",
        null,
    ).use {
        it.moveToFirst()
        it.getLong(0)
    }

    @Synchronized
    fun projections(limit: Int = 50, after: String = ""): List<Projection> = readableDatabase.rawQuery(
        "SELECT p.key, p.revision, r.data FROM projection p JOIN records r ON p.key=r.key " +
            "WHERE p.key>? AND p.key NOT IN (SELECT key FROM excluded) ORDER BY p.key LIMIT ?",
        arrayOf(after, limit.coerceIn(1, 100).toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(Projection(cursor.getString(0), cursor.getLong(1), syncJson.decodeFromString(cursor.getString(2))))
            }
        }
    }

    @Synchronized
    fun projectionCount(): Long = readableDatabase.rawQuery(
        "SELECT count(*) FROM projection WHERE key NOT IN (SELECT key FROM excluded)",
        null,
    ).use {
        it.moveToFirst()
        it.getLong(0)
    }

    /** Bounded, one-time repair of history emitted before its item metadata was captured. */
    @Synchronized
    fun historyReferencePage(after: String, limit: Int = 50): HistoryReferencePage {
        val size = limit.coerceIn(1, 100)
        return readableDatabase.rawQuery(
            "SELECT key, data FROM records WHERE key>? AND key NOT IN (SELECT key FROM excluded) ORDER BY key LIMIT ?",
            arrayOf(after, size.toString()),
        ).use { cursor ->
            var next = after
            val refs = buildList {
                while (cursor.moveToNext()) {
                    next = cursor.getString(0)
                    val record = syncJson.decodeFromString<SyncRecord>(cursor.getString(1))
                    if (record.ref.kind in setOf("anime-history", "manga-history") && !record.deleted) add(record.ref)
                }
            }
            HistoryReferencePage(refs, next, cursor.count < size)
        }
    }

    data class HistoryReferencePage(val refs: List<SyncRef>, val next: String, val complete: Boolean)

    /**
     * The caller applies with journaling suppressed in its source transaction. A crash before this
     * acknowledgment repeats the same projection safely. A newer projection is never cleared.
     */
    @Synchronized
    fun applied(projection: Projection, actualFields: Map<String, JsonElement?>) = transaction { db ->
        actualFields.forEach { (field, content) ->
            replace(
                db,
                "baseline",
                "key" to projection.key,
                "field" to field,
                "value" to (content?.toString() ?: "null"),
            )
        }
        db.delete(
            "projection",
            "key=? AND revision=?",
            arrayOf(projection.key, projection.revision.toString()),
        )
        projection.record.fields.forEach { (field, register) ->
            val observed = register.versions.fold(observation(db, projection.key, field)) { vector, change ->
                vector.merged(change.context).including(change.dot)
            }
            observe(db, projection.key, field, observed)
        }
    }

    private fun observation(db: SQLiteDatabase, key: String, field: String): SyncVector =
        value(db, "observations", "vector", "key=? AND field=?", arrayOf(key, field))
            ?.let { syncJson.decodeFromString(it) } ?: SyncVector()

    private fun observe(db: SQLiteDatabase, key: String, field: String, vector: SyncVector) =
        replace(db, "observations", "key" to key, "field" to field, "vector" to syncJson.encodeToString(vector))

    private fun record(db: SQLiteDatabase, key: String): SyncRecord? =
        value(db, "records", "data", "key=?", arrayOf(key))?.let { syncJson.decodeFromString(it) }

    private fun vector(db: SQLiteDatabase, name: String = "vector"): SyncVector =
        value(db, "state", "value", "key=?", arrayOf(name))?.let { syncJson.decodeFromString(it) } ?: SyncVector()

    private fun saveVector(db: SQLiteDatabase, vector: SyncVector, name: String = "vector") =
        replace(db, "state", "key" to name, "value" to syncJson.encodeToString(vector))

    private fun insertChange(db: SQLiteDatabase, change: SyncChange, outgoing: Boolean) {
        db.insertOrThrow(
            "changes",
            null,
            ContentValues().apply {
                put("device", change.dot.device)
                put("sequence", change.dot.sequence)
                put("data", syncJson.encodeToString(change))
                put("digest", SyncCheckpoints.hash(syncJson.encodeToString(change)))
                put("outgoing", if (outgoing) 1 else 0)
                put("ref", change.ref.key())
            },
        )
    }

    private fun value(
        db: SQLiteDatabase,
        table: String,
        column: String,
        selection: String?,
        args: Array<String>?,
    ): String? = db.query(table, arrayOf(column), selection, args, null, null, null, "1").use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    private fun replace(db: SQLiteDatabase, table: String, vararg values: Pair<String, String>) {
        val content = ContentValues().apply { values.forEach { (key, value) -> put(key, value) } }
        check(db.insertWithOnConflict(table, null, content, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    @Serializable
    data class Pending(val envelope: SyncEnvelope, val messageId: Long = 0, val attempted: Boolean = false)

    @Serializable
    data class Projection(val key: String, val revision: Long, val record: SyncRecord)
}
