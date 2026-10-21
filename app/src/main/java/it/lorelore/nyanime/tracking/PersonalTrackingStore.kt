package it.lorelore.nyanime.tracking

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local records are portable; pending remote writes are device/account-bound and never exported. */
class PersonalTrackingStore internal constructor(context: Context, databaseName: String = "personal-tracking.sqlite") {
    private val database = Database(context.applicationContext, databaseName)
    private val json = Json { ignoreUnknownKeys = true }
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    @Synchronized
    fun get(subject: TrackingSubject): PersonalTrackingRecord? = read("personal", subject.key)?.let {
        json.decodeFromString<PersonalTrackingRecord>(it).takeIf { record ->
            record.valid() && record.subject == subject
        }
    }

    @Synchronized
    fun update(
        subject: TrackingSubject,
        initial: PersonalTrackingRecord,
        patch: TrackingPatch,
    ): PersonalTrackingRecord {
        val current = get(subject) ?: initial
        val next = current.copy(
            values = patch.applyTo(current.values),
            changedAt = maxOf(System.currentTimeMillis(), current.changedAt + 1),
        )
        require(next.subject == subject && next.valid())
        write("personal", subject.key, json.encodeToString(next))
        return next
    }

    @Synchronized
    fun observe(subject: TrackingSubject, progress: Double) {
        val current = get(subject) ?: return
        val next = current.observe(progress)
        if (next != current) {
            write(
                "personal",
                subject.key,
                json.encodeToString(
                    next.copy(
                        changedAt = maxOf(
                            System.currentTimeMillis(),
                            current.changedAt + 1,
                        ),
                    ),
                ),
            )
        }
    }

    @Synchronized
    fun snapshot(): List<PersonalTrackingRecord> = rows("personal").map {
        json.decodeFromString<PersonalTrackingRecord>(it).also { record -> check(record.valid()) }
    }

    @Synchronized
    fun merge(records: List<PersonalTrackingRecord>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            records.filter(PersonalTrackingRecord::valid).forEach { record ->
                if (record.changedAt > (get(record.subject)?.changedAt ?: -1)) {
                    write("personal", record.subject.key, json.encodeToString(record))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        revision.value++
    }

    @Synchronized
    fun pending(): List<PendingTrackingEdit> = rows("pending").mapNotNull {
        runCatching { json.decodeFromString<PendingTrackingEdit>(it).takeIf(PendingTrackingEdit::valid) }.getOrNull()
    }

    @Synchronized
    fun pending(key: String): PendingTrackingEdit? = read("pending", key)?.let {
        json.decodeFromString<PendingTrackingEdit>(it).takeIf(PendingTrackingEdit::valid)
    }

    @Synchronized
    fun enqueue(edit: PendingTrackingEdit): PendingTrackingEdit = enqueue(edit, edit.base ?: TrackingValues())

    @Synchronized
    fun enqueue(requested: PendingTrackingEdit, before: TrackingValues): PendingTrackingEdit = transaction {
        val edit = TrackingQueuePolicy.prepare(requested, before, flight(requested.key))
        require(edit.valid())
        val previous = pending(edit.key)
        require(previous == null || previous.sameIdentity(edit)) {
            "An edit belongs to a different account or association"
        }
        val version = maxOf(edit.revision, (read("metadata", "revision")?.toLongOrNull() ?: 0) + 1)
        write("metadata", "revision", version.toString(), notify = false)
        val id = java.util.UUID.randomUUID().toString()
        val baseline = previous?.base ?: edit.base?.takeIf { previous == null }
        val combined = previous?.patch?.then(edit.patch) ?: edit.patch
        val currentBefore = previous?.patch?.applyTo(before) ?: before
        val guardFields = (previous?.expectedFields.orEmpty() - edit.patch.fields) + edit.expectedFields
        val guardValues = edit.expectedRemote?.let { latest ->
            TrackingPatch(latest, edit.expectedFields).applyTo(previous?.expectedRemote ?: latest)
        } ?: previous?.expectedRemote
        val alternativeValues = edit.alternativeExpectedRemote?.let { latest ->
            TrackingPatch(latest, edit.expectedFields).applyTo(previous?.alternativeExpectedRemote ?: latest)
        } ?: previous?.alternativeExpectedRemote
        val owned = owners(edit).toMutableMap()
        edit.patch.fields.forEach { owned[it] = version }
        write("owners", edit.identityKey(), json.encodeToString(owned), notify = false)
        val fields = combined.fields.filterTo(mutableSetOf()) { field ->
            baseline == null ||
                flight(edit.key) != null ||
                !TrackingQueuePolicy.sameField(
                    combined.values,
                    baseline,
                    field,
                )
        }
        val next = edit.copy(
            patch = combined.copy(fields = fields),
            revision = version,
            failed = false,
            delivery = if (edit.delivery == TrackingDelivery.BLOCKED) {
                TrackingDelivery.BLOCKED
            } else {
                TrackingDelivery.WAITING
            },
            problem = if (edit.delivery == TrackingDelivery.BLOCKED) edit.problem else TrackingQueueProblem.NONE,
            attempts = 0,
            attemptedAt = 0,
            notBefore = 0,
            createdAt = previous?.createdAt?.takeIf { it > 0 } ?: edit.createdAt.takeIf { it > 0 } ?: version,
            receiptId = id,
            base = baseline,
            origin = if (previous?.origin == TrackingEditOrigin.MANUAL && edit.origin == TrackingEditOrigin.AUTOMATIC) {
                previous.origin
            } else {
                edit.origin
            },
            expectedRemote = guardValues,
            expectedFields = guardFields.intersect(fields),
            alternativeExpectedRemote = alternativeValues,
            automaticProgress = if (TrackingField.PROGRESS in edit.patch.fields) {
                edit.automaticProgress
            } else {
                previous?.automaticProgress ?: edit.automaticProgress
            },
        )
        val receipt = TrackingEditReceipt(id, edit.copy(revision = version, receiptId = id), currentBefore, version)
        write("receipts", id, json.encodeToString(receipt), notify = false)
        if (fields.isEmpty()) {
            database.writableDatabase.delete("pending", "key = ?", arrayOf(edit.key))
        } else {
            write("pending", next.key, json.encodeToString(next), notify = false)
        }
        trimHistory()
        next
    }

    @Synchronized
    fun complete(edit: PendingTrackingEdit, confirmation: TrackingConfirmation? = null) = transaction {
        val firstRemote = flight(edit.key)?.takeIf {
            it.revision == edit.revision && it.sameIdentity(edit)
        }?.attemptBase ?: edit.attemptBase
        val current = pending(edit.key)
        if (current?.sameIdentity(edit) == true) {
            val remaining = current.acknowledge(edit)
            if (remaining != null) {
                val baseline =
                    confirmation?.after ?: edit.patch.applyTo(remaining.base ?: edit.base ?: TrackingValues())
                val rebased = if (confirmation == null) {
                    remaining.copy(base = baseline)
                } else {
                    remaining.rebaseAfterConfirmation(edit, confirmation)
                }
                write("pending", rebased.key, json.encodeToString(rebased), notify = false)
            } else {
                database.writableDatabase.delete("pending", "key = ?", arrayOf(edit.key))
            }
        }
        if (flight(edit.key)?.revision == edit.revision) {
            database.writableDatabase.delete(
                "flight",
                "key = ?",
                arrayOf(edit.key),
            )
        }
        recent().filter { receipt ->
            receipt.edit.sameIdentity(edit) &&
                receipt.revision <= edit.revision &&
                receipt.sentAt == 0L &&
                receipt.edit.patch.fields.all { field ->
                    field in edit.patch.fields &&
                        TrackingQueuePolicy.sameField(
                            receipt.edit.patch.values,
                            edit.patch.values,
                            field,
                        )
                }
        }.forEach { receipt ->
            var confirmed = receipt.copy(sentAt = System.currentTimeMillis())
            if (confirmation != null) {
                val owned = owners(edit).toMutableMap()
                val effects = if (edit.automaticProgress &&
                    receipt.edit.automaticProgress &&
                    TrackingField.PROGRESS in receipt.edit.patch.fields &&
                    owned[TrackingField.PROGRESS] == receipt.revision
                ) {
                    TrackingPatch.REMOTE_FIELDS.filterTo(mutableSetOf()) { field ->
                        !TrackingQueuePolicy.sameField(confirmation.before, confirmation.after, field) &&
                            (owned[field] ?: 0) <= edit.revision &&
                            (field !in edit.patch.fields || field in receipt.edit.patch.fields)
                    }
                } else {
                    emptySet()
                }
                effects.forEach { owned[it] = receipt.revision }
                write("owners", edit.identityKey(), json.encodeToString(owned), notify = false)
                val fields = receipt.edit.patch.fields + effects
                val before = TrackingPatch(confirmation.before, effects - receipt.edit.patch.fields)
                    .applyTo(receipt.before).let { previous ->
                        if (edit.automaticProgress && TrackingField.PROGRESS in receipt.edit.patch.fields) {
                            val originalRemote = firstRemote ?: confirmation.before
                            previous.copy(progress = maxOf(previous.progress, originalRemote.progress))
                        } else {
                            previous
                        }
                    }
                confirmed = confirmed.copy(
                    edit = receipt.edit.copy(
                        patch = TrackingPatch(
                            confirmation.after,
                            receipt.edit.patch.fields + effects,
                        ),
                    ),
                    before = before,
                    noEffect = fields.all {
                        TrackingQueuePolicy.sameField(confirmation.before, confirmation.after, it)
                    } &&
                        (
                            firstRemote?.let { original ->
                                fields.all { TrackingQueuePolicy.sameField(original, confirmation.after, it) }
                            } ?: (edit.attempts <= 1)
                            ),
                )
            }
            write("receipts", receipt.id, json.encodeToString(confirmed), notify = false)
        }
        trimHistory()
    }

    @Synchronized
    fun fail(edit: PendingTrackingEdit) = failed(edit, TrackingQueueProblem.NETWORK, System.currentTimeMillis())

    @Synchronized
    fun failed(
        edit: PendingTrackingEdit,
        problem: TrackingQueueProblem,
        now: Long,
        retryAfter: Long? = null,
    ) = transaction {
        val current = pending(edit.key) ?: return@transaction
        if (current.revision == edit.revision && current.sameIdentity(edit)) {
            val preserved = edit.copy(attemptBase = flight(edit.key)?.attemptBase ?: edit.attemptBase)
            write(
                "pending",
                edit.key,
                json.encodeToString(TrackingQueuePolicy.failed(preserved, problem, now, retryAfter)),
                notify = false,
            )
        }
    }

    @Synchronized
    fun flight(key: String): PendingTrackingEdit? = read("flight", key)?.let {
        json.decodeFromString<PendingTrackingEdit>(it).takeIf(PendingTrackingEdit::valid)
    }

    @Synchronized
    fun noteAttemptBase(edit: PendingTrackingEdit, remote: TrackingValues) = transaction {
        val active = flight(edit.key) ?: return@transaction
        if (active.revision != edit.revision ||
            !active.sameIdentity(edit) ||
            active.attemptBase != null
        ) {
            return@transaction
        }
        write("flight", active.key, json.encodeToString(active.copy(attemptBase = remote)), notify = false)
        val pending = pending(edit.key) ?: return@transaction
        if (pending.revision == edit.revision) {
            write("pending", pending.key, json.encodeToString(pending.copy(attemptBase = remote)), notify = false)
        } else if (pending.origin == TrackingEditOrigin.UNDO && pending.sameIdentity(edit)) {
            val fields = pending.expectedFields.intersect(edit.patch.fields).filterTo(mutableSetOf()) {
                pending.expectedRemote?.let { values ->
                    TrackingQueuePolicy.sameField(values, edit.patch.values, it)
                } ==
                    true
            }
            if (fields.isNotEmpty()) {
                write(
                    "pending",
                    pending.key,
                    json.encodeToString(
                        pending.copy(
                            alternativeExpectedRemote = TrackingPatch(
                                remote,
                                fields,
                            ).applyTo(pending.alternativeExpectedRemote ?: remote),
                        ),
                    ),
                    notify = false,
                )
            }
        }
    }

    @Synchronized
    fun claim(edit: PendingTrackingEdit, now: Long): PendingTrackingEdit? = transaction {
        val current = pending(edit.key) ?: return@transaction null
        if (current.revision != edit.revision ||
            !current.sameIdentity(edit) ||
            !TrackingQueuePolicy.ready(
                current,
                now,
            )
        ) {
            return@transaction null
        }
        val previousFlight = flight(edit.key)?.takeIf { it.revision == edit.revision && it.sameIdentity(edit) }
        val claimed = current.copy(
            delivery = TrackingDelivery.SENDING,
            attempts = current.attempts + 1,
            attemptedAt = now,
            attemptBase = previousFlight?.attemptBase ?: current.attemptBase,
        )
        write("flight", edit.key, json.encodeToString(claimed), notify = false)
        write("pending", edit.key, json.encodeToString(claimed), notify = false)
        claimed
    }

    @Synchronized
    fun recoverInterrupted() = transaction {
        pending().filter { it.delivery == TrackingDelivery.SENDING }.forEach {
            val recovered = if (it.attempts >= TrackingQueuePolicy.MAX_ATTEMPTS) {
                TrackingQueuePolicy.failed(it, TrackingQueueProblem.NETWORK, System.currentTimeMillis())
            } else {
                it.copy(delivery = TrackingDelivery.UNCERTAIN)
            }
            write(
                "pending",
                it.key,
                json.encodeToString(recovered),
                notify = false,
            )
        }
    }

    @Synchronized
    fun cooldown(tracker: Long): Long = read("metadata", "cooldown:$tracker")?.toLongOrNull() ?: 0

    @Synchronized
    fun cooldown(tracker: Long, until: Long) = transaction {
        write("metadata", "cooldown:$tracker", maxOf(cooldown(tracker), until).toString(), notify = false)
    }

    @Synchronized
    fun confirmAccount(edit: PendingTrackingEdit, epoch: String): Boolean = transaction {
        val current = pending(edit.key) ?: return@transaction false
        if (current.revision != edit.revision || !current.sameIdentity(edit)) return@transaction false
        write(
            "pending",
            current.key,
            json.encodeToString(
                current.copy(
                    accountEpoch = epoch,
                    delivery = TrackingDelivery.WAITING,
                    problem = TrackingQueueProblem.NONE,
                    failed = false,
                    notBefore = 0,
                    attempts = 0,
                ),
            ),
            notify = false,
        )
        receipt(current.receiptId)?.let { receipt ->
            write(
                "receipts",
                receipt.id,
                json.encodeToString(receipt.copy(edit = receipt.edit.copy(accountEpoch = epoch))),
                notify = false,
            )
        }
        true
    }

    @Synchronized
    fun retry(key: String, expectedRevision: Long, review: Boolean = false): Boolean = transaction {
        val current = pending(key) ?: return@transaction false
        if (current.revision != expectedRevision ||
            current.problem == TrackingQueueProblem.REVIEW &&
            !review
        ) {
            return@transaction false
        }
        write(
            "pending",
            key,
            json.encodeToString(
                current.copy(
                    failed = false,
                    delivery = TrackingDelivery.WAITING,
                    problem = TrackingQueueProblem.NONE,
                    attempts = 0,
                    notBefore = 0,
                ),
            ),
            notify = false,
        )
        true
    }

    @Synchronized
    fun discard(edit: PendingTrackingEdit): Boolean = transaction {
        val current = pending(edit.key) ?: return@transaction false
        if (current.revision != edit.revision || !current.sameIdentity(edit)) return@transaction false
        database.writableDatabase.delete("pending", "key = ?", arrayOf(edit.key))
        database.writableDatabase.delete("flight", "key = ?", arrayOf(edit.key))
        recent().filter { it.edit.sameIdentity(edit) && it.sentAt == 0L }.forEach {
            write("receipts", it.id, json.encodeToString(it.copy(discarded = true)), notify = false)
        }
        true
    }

    @Synchronized
    fun recent(): List<TrackingEditReceipt> = rows("receipts").mapNotNull {
        runCatching { json.decodeFromString<TrackingEditReceipt>(it) }.getOrNull()
    }.sortedByDescending { it.revision }

    @Synchronized
    fun receipt(id: String): TrackingEditReceipt? = read("receipts", id)?.let { json.decodeFromString(it) }

    @Synchronized
    fun owners(edit: PendingTrackingEdit): Map<TrackingField, Long> = read("owners", edit.identityKey())?.let {
        json.decodeFromString(it)
    } ?: emptyMap()

    @Synchronized
    fun undo(id: String, now: Long): Boolean = transaction {
        val receipt = receipt(id) ?: return@transaction false
        val inverse = TrackingQueuePolicy.undoPatch(receipt, owners(receipt.edit))
        if (inverse.fields.isEmpty()) return@transaction false
        val current = pending(receipt.edit.key)
        if (current != null && !current.sameIdentity(receipt.edit)) return@transaction false
        val uncertain = flight(receipt.edit.key)
        val guarded = if (receipt.sentAt > 0) {
            inverse.fields
        } else {
            inverse.fields.intersect(uncertain?.patch?.fields.orEmpty())
        }
        enqueue(
            receipt.edit.copy(
                patch = inverse, revision = now.coerceAtLeast(1), origin = TrackingEditOrigin.UNDO,
                expectedRemote = if (receipt.sentAt > 0) receipt.edit.patch.values else uncertain?.patch?.values,
                expectedFields = guarded,
                alternativeExpectedRemote = if (receipt.sentAt > 0) null else uncertain?.attemptBase ?: uncertain?.base,
                base = if (receipt.sentAt > 0) receipt.edit.patch.values else receipt.edit.base,
                attemptBase = null, automaticProgress = false,
            ),
            receipt.edit.patch.values,
        )
        write("receipts", id, json.encodeToString(receipt.copy(undone = true)), notify = false)
        true
    }

    private fun trimHistory() {
        val protected = pending().mapTo(mutableSetOf()) { it.receiptId }
        rows("flight").map { json.decodeFromString<PendingTrackingEdit>(it) }.forEach { protected += it.receiptId }
        recent().drop(TrackingQueuePolicy.MAX_HISTORY).filter { it.id !in protected }.forEach {
            database.writableDatabase.delete("receipts", "key = ?", arrayOf(it.id))
        }
    }

    @Synchronized
    fun importLegacy(values: Map<Long, Double>) = transaction {
        values.forEach { (track, progress) ->
            if (track <= 0 || !progress.isFinite() || progress !in 0.0..1_000_000.0) return@forEach
            val id = "$track:${progress.toBits()}"
            if (read("metadata", "legacy:$id") != null) return@forEach
            write("legacy", id, json.encodeToString(LegacyTrackingRequest(id, track, progress)), notify = false)
            write("metadata", "legacy:$id", "imported", notify = false)
        }
    }

    @Synchronized
    fun legacy(): List<LegacyTrackingRequest> = rows("legacy").map { json.decodeFromString(it) }

    @Synchronized
    fun discardLegacy(id: String) = transaction {
        database.writableDatabase.delete("legacy", "key = ?", arrayOf(id))
    }

    @Synchronized
    fun acceptLegacy(id: String, edit: PendingTrackingEdit, before: TrackingValues): Boolean = transaction {
        if (read("legacy", id) == null) return@transaction false
        enqueue(edit, before)
        database.writableDatabase.delete("legacy", "key = ?", arrayOf(id))
        true
    }

    private fun <T> transaction(block: () -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
            if (!db.inTransaction()) revision.value++
        }
    }

    private fun read(table: String, key: String): String? = database.readableDatabase.query(
        table,
        arrayOf("data"),
        "key = ?",
        arrayOf(key),
        null,
        null,
        null,
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun rows(table: String): List<String> = database.readableDatabase.query(
        table,
        arrayOf("data"),
        null,
        null,
        null,
        null,
        null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun write(table: String, key: String, data: String, notify: Boolean = true) {
        val values = ContentValues().apply {
            put("key", key)
            put("data", data)
        }
        check(
            database.writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L,
        )
        if (notify) revision.value++
    }

    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
        init {
            setWriteAheadLoggingEnabled(true)
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE personal (key TEXT NOT NULL PRIMARY KEY, data TEXT NOT NULL)")
            db.execSQL("CREATE TABLE pending (key TEXT NOT NULL PRIMARY KEY, data TEXT NOT NULL)")
            createQueueTables(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createQueueTables(db)
        }

        private fun createQueueTables(db: SQLiteDatabase) {
            listOf("flight", "receipts", "owners", "metadata", "legacy").forEach {
                db.execSQL("CREATE TABLE IF NOT EXISTS $it (key TEXT NOT NULL PRIMARY KEY, data TEXT NOT NULL)")
            }
        }
    }

    companion object {
        @Volatile private var instance: PersonalTrackingStore? = null
        fun get(context: Context): PersonalTrackingStore = instance ?: synchronized(this) {
            instance ?: PersonalTrackingStore(
                context.applicationContext,
                if (eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE) {
                    "personal-tracking-isolated-probe.sqlite"
                } else {
                    "personal-tracking.sqlite"
                },
            ).also { instance = it }
        }
    }
}
