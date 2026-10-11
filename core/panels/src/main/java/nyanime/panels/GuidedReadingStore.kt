package nyanime.panels

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One row per portable page. Updating a bookmark never rewrites the complete archive. */
class GuidedReadingStore private constructor(context: Context) {
    private val database = Database(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(ref: PanelPageRef) = json.encodeToString(ref)

    @Synchronized fun get(ref: PanelPageRef): GuidedPageRecord? = read(database.readableDatabase, ref)

    @Synchronized fun snapshot(): List<GuidedPageRecord> = database.readableDatabase
        .query("pages", arrayOf("data"), null, null, null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) decode(cursor.getString(0))?.let(::add) }
        }

    @Synchronized fun changes(after: Long, limit: Int = 50): List<Pair<Long, GuidedPageRecord>> =
        database.readableDatabase.query(
            "pages",
            arrayOf("revision", "data"),
            "revision > ?",
            arrayOf(after.toString()),
            null,
            null,
            "revision ASC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) decode(cursor.getString(1))?.let { add(cursor.getLong(0) to it) } }
        }

    @Synchronized fun pending(after: Long): Long = database.readableDatabase
        .rawQuery("SELECT COUNT(*) FROM pages WHERE revision > ?", arrayOf(after.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    @Synchronized fun put(record: GuidedPageRecord) = transaction { db ->
        require(record.valid())
        if (record.changedAt >= (read(db, record.ref)?.changedAt ?: Long.MIN_VALUE)) write(db, record)
    }

    @Synchronized fun merge(imported: List<GuidedPageRecord>) = transaction { db ->
        imported.filter(GuidedPageRecord::valid).forEach { incoming ->
            if (incoming.changedAt >= (read(db, incoming.ref)?.changedAt ?: Long.MIN_VALUE)) write(db, incoming)
        }
    }

    /** Causal Cloud projection wins only if no local write happened after capture. */
    @Synchronized fun project(record: GuidedPageRecord, expected: GuidedPageRecord?): Boolean = transaction { db ->
        require(record.valid())
        if (read(db, record.ref) != expected) return@transaction false
        write(db, record)
        true
    }
    private fun read(db: SQLiteDatabase, ref: PanelPageRef): GuidedPageRecord? =
        db.query("pages", arrayOf("data"), "ref = ?", arrayOf(key(ref)), null, null, null).use {
            if (it.moveToFirst()) decode(it.getString(0)) else null
        }
    private fun decode(raw: String) = runCatching {
        json.decodeFromString<GuidedPageRecord>(raw).takeIf(GuidedPageRecord::valid)
    }.getOrNull()
    private fun write(db: SQLiteDatabase, record: GuidedPageRecord) {
        val values = ContentValues().apply {
            put("ref", key(record.ref))
            put("data", json.encodeToString(record))
        }
        check(db.insertWithOnConflict("pages", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            return block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }
    private class Database(context: Context) : SQLiteOpenHelper(context, "guided-reading.sqlite", null, 1) {
        init {
            setWriteAheadLoggingEnabled(true)
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE pages (revision INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "ref TEXT UNIQUE NOT NULL, data TEXT NOT NULL)",
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    companion object {
        @Volatile private var instance: GuidedReadingStore? = null
        fun get(context: Context): GuidedReadingStore = instance ?: synchronized(this) {
            instance ?: GuidedReadingStore(context.applicationContext).also { instance = it }
        }
    }
}
