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
class PersonalTrackingStore private constructor(context: Context) {
    private val database = Database(context.applicationContext)
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
    fun enqueue(edit: PendingTrackingEdit): PendingTrackingEdit {
        require(edit.valid())
        val previous = pending(edit.key)
        require(previous == null || previous.account == edit.account && previous.remoteId == edit.remoteId) {
            "An edit belongs to a different account or association"
        }
        val next = edit.copy(
            patch = previous?.patch?.then(edit.patch) ?: edit.patch,
            revision = maxOf(edit.revision, (previous?.revision ?: 0) + 1),
            failed = false,
        )
        write("pending", edit.key, json.encodeToString(next))
        return next
    }

    @Synchronized
    fun complete(edit: PendingTrackingEdit) {
        val current = pending(edit.key) ?: return
        val remaining = current.acknowledge(edit)
        if (remaining != null) {
            write("pending", remaining.key, json.encodeToString(remaining))
        } else {
            database.writableDatabase.delete("pending", "key = ?", arrayOf(edit.key))
        }
        revision.value++
    }

    @Synchronized
    fun fail(edit: PendingTrackingEdit) {
        if (pending(edit.key)?.revision == edit.revision) {
            write("pending", edit.key, json.encodeToString(edit.copy(failed = true)))
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

    private fun write(table: String, key: String, data: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("data", data)
        }
        check(
            database.writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L,
        )
        revision.value++
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, "personal-tracking.sqlite", null, 1) {
        init {
            setWriteAheadLoggingEnabled(true)
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE personal (key TEXT NOT NULL PRIMARY KEY, data TEXT NOT NULL)")
            db.execSQL("CREATE TABLE pending (key TEXT NOT NULL PRIMARY KEY, data TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        @Volatile private var instance: PersonalTrackingStore? = null
        fun get(context: Context): PersonalTrackingStore = instance ?: synchronized(this) {
            instance ?: PersonalTrackingStore(context.applicationContext).also { instance = it }
        }
    }
}
