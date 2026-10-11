package eu.kanade.tachiyomi.ui.reader.spread

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Portable reader settings; no cached images, chapter IDs or extension-specific rules. */
class SpreadStore private constructor(context: Context) {
    private val file = context.getDatabasePath("reader-spreads.sqlite")
    private val database = Database(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    @Synchronized
    fun get(source: Long, title: String): SpreadRecord {
        if (!file.exists()) return SpreadRecord(source, title)
        return database.readableDatabase.query(
            "settings",
            arrayOf("data"),
            "source = ? AND title = ?",
            arrayOf(source.toString(), title),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) decode(cursor.getString(0)) else null
        } ?: SpreadRecord(source, title)
    }

    @Synchronized
    fun snapshot(): List<SpreadRecord> {
        if (!file.exists()) return emptyList()
        return database.readableDatabase.query(
            "settings",
            arrayOf("data"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) decode(cursor.getString(0))?.let(::add) }
        }
    }

    @Synchronized
    fun put(record: SpreadRecord) {
        require(record.valid())
        write(database.writableDatabase, record)
        revision.value++
    }

    @Synchronized
    fun update(source: Long, title: String, transform: (SpreadRecord) -> SpreadRecord) {
        val current = get(source, title)
        val updated = transform(current)
        require(updated.source == source && updated.title == title)
        if (updated == current) return
        val next = if (current.changedAt == Long.MAX_VALUE) current.changedAt else current.changedAt + 1
        put(updated.copy(changedAt = maxOf(System.currentTimeMillis(), next)))
    }

    @Synchronized
    fun merge(records: List<SpreadRecord>) {
        if (records.isEmpty()) return
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            records.filter(SpreadRecord::valid).forEach { record ->
                if (record.changedAt >= get(record.source, record.title).changedAt) write(db, record)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        revision.value++
    }

    private fun decode(value: String) = runCatching {
        json.decodeFromString<SpreadRecord>(value).takeIf(SpreadRecord::valid)
    }.getOrNull()

    private fun write(db: SQLiteDatabase, record: SpreadRecord) {
        val values = ContentValues().apply {
            put("source", record.source)
            put("title", record.title)
            put("data", json.encodeToString(record))
        }
        check(db.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, "reader-spreads.sqlite", null, 1) {
        init {
            setWriteAheadLoggingEnabled(true)
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE settings (source INTEGER NOT NULL, title TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(source, title))",
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        @Volatile private var instance: SpreadStore? = null
        fun get(context: Context): SpreadStore = instance ?: synchronized(this) {
            instance ?: SpreadStore(context.applicationContext).also { instance = it }
        }
    }
}
