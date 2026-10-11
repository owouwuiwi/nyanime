package it.lorelore.nyanime.studio

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class StudioIndexGeneration(
    val id: Long,
    val cursor: String?,
    val pages: Int,
    val checked: Int,
    val found: Int,
    val bytes: Long,
    val updatedAt: Long,
)

internal data class StudioIndexSaved(
    val ready: StudioIndexGeneration? = null,
    val staging: StudioIndexGeneration? = null,
)

internal data class StudioIndexPage(
    val entries: List<JsonObject>,
    val nextCursor: String?,
    val complete: Boolean,
)

/** All calls run on IO. One page and its cursor commit together, never a whole-list rewrite. */
internal interface StudioNovelIndexJournal {
    fun pin(key: String): Closeable

    fun read(key: String): StudioIndexSaved

    fun begin(key: String, now: Long): StudioIndexGeneration

    fun append(key: String, generation: Long, page: StudioIndexPage, now: Long): StudioIndexSaved

    fun entries(generation: Long): List<JsonObject>
}

/** Ephemeral source metadata, excluded from Android backup, portable backups and Cloud. */
internal class StudioSqliteIndexJournal(context: Context) : StudioNovelIndexJournal {
    private val pins = ConcurrentHashMap<String, AtomicInteger>()
    private val root =
        File(context.noBackupFilesDir, "studio-novel-index-v2").also {
            check(it.isDirectory || it.mkdirs())
        }
    private val helper =
        object :
            SQLiteOpenHelper(
                context.applicationContext,
                File(root, "index.db").absolutePath,
                null,
                1,
            ) {
            override fun onConfigure(db: SQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE indexes (key TEXT PRIMARY KEY, ready INTEGER, " +
                        "staging INTEGER, touched INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE generations (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "owner TEXT NOT NULL REFERENCES indexes(key) ON DELETE CASCADE, cursor TEXT, " +
                        "pages INTEGER NOT NULL DEFAULT 0, checked INTEGER NOT NULL DEFAULT 0, " +
                        "found INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0, " +
                        "updated INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE entries (generation INTEGER NOT NULL " +
                        "REFERENCES generations(id) ON DELETE CASCADE, " +
                        "reference TEXT NOT NULL, ordinal INTEGER NOT NULL, body TEXT NOT NULL, " +
                        "bytes INTEGER NOT NULL, PRIMARY KEY(generation,reference))",
                )
                db.execSQL("CREATE INDEX chapter_order ON entries(generation,ordinal)")
                db.execSQL(
                    "CREATE TABLE cursors (generation INTEGER NOT NULL " +
                        "REFERENCES generations(id) ON DELETE CASCADE, " +
                        "hash TEXT NOT NULL, PRIMARY KEY(generation,hash))",
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("Unsupported private index schema")
            }
        }
            .also { it.setWriteAheadLoggingEnabled(true) }

    override fun pin(key: String): Closeable {
        require(KEY.matches(key))
        pins.compute(key) { _, count -> (count ?: AtomicInteger()).also { it.incrementAndGet() } }
        return Closeable {
            pins.computeIfPresent(key) { _, count ->
                if (count.decrementAndGet() == 0) null else count
            }
        }
    }

    override fun read(key: String): StudioIndexSaved = read(helper.readableDatabase, key)

    private fun read(db: SQLiteDatabase, key: String): StudioIndexSaved =
        db.rawQuery(
            "SELECT r.id,r.cursor,r.pages,r.checked,r.found,r.bytes,r.updated," +
                "s.id,s.cursor,s.pages,s.checked,s.found,s.bytes,s.updated FROM indexes i " +
                "LEFT JOIN generations r ON r.id=i.ready LEFT JOIN generations s ON s.id=i.staging WHERE i.key=?",
            arrayOf(key),
        )
            .use { rows ->
                if (!rows.moveToFirst()) {
                    StudioIndexSaved()
                } else {
                    StudioIndexSaved(generation(rows, 0), generation(rows, 7))
                }
            }

    override fun begin(key: String, now: Long): StudioIndexGeneration {
        require(KEY.matches(key) && now >= 0)
        val db = helper.writableDatabase
        return transaction(db) {
            val current = read(db, key)
            if (current.staging != null) return@transaction current.staging
            db.insertWithOnConflict(
                "indexes",
                null,
                ContentValues().apply {
                    put("key", key)
                    put("touched", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            prune(db, 0, key)
            val id =
                db.insertOrThrow(
                    "generations",
                    null,
                    ContentValues().apply {
                        put("owner", key)
                        put("updated", now)
                    },
                )
            check(
                db.update(
                    "indexes",
                    ContentValues().apply {
                        put("staging", id)
                        put("touched", now)
                    },
                    "key=?",
                    arrayOf(key),
                ) == 1,
            )
            requireNotNull(read(db, key).staging)
        }
    }

    override fun append(
        key: String,
        generation: Long,
        page: StudioIndexPage,
        now: Long,
    ): StudioIndexSaved {
        require(page.entries.size <= 2000 && (page.complete || !page.nextCursor.isNullOrBlank()))
        require(page.nextCursor == null || page.nextCursor.length <= 8192)
        val encoded =
            page.entries.map { item ->
                val reference =
                    requireNotNull(item["url"]?.studioText()).also {
                        require(it.isNotBlank() && it.length <= 8192)
                    }
                val title = requireNotNull(item["title"]?.studioText())
                require(title.isNotBlank() && title.length <= 2048)
                val raw = item.toString()
                val bytes = raw.toByteArray().size
                require(bytes <= 65_536) { "A chapter declaration exceeds the storage limit" }
                Triple(reference, raw, bytes)
            }
        val db = helper.writableDatabase
        return transaction(db) {
            val before = read(db, key)
            val staging = requireNotNull(before.staging)
            check(staging.id == generation) { "Chapter preparation was superseded" }
            require(staging.pages < 10_000 && staging.checked <= 1_000_000 - page.entries.size)
            if (!page.complete) {
                val hash = StudioNovelIndexKey.digest(requireNotNull(page.nextCursor))
                check(
                    db.insertWithOnConflict(
                        "cursors",
                        null,
                        ContentValues().apply {
                            put("generation", generation)
                            put("hash", hash)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L,
                ) {
                    "Chapter cursor did not advance"
                }
            }
            var count = staging.found
            var bytes = staging.bytes
            var added = 0L
            encoded.forEach { (reference, raw, size) ->
                val previous =
                    db.rawQuery(
                        "SELECT ordinal,bytes FROM entries WHERE generation=? AND reference=?",
                        arrayOf(generation.toString(), reference),
                    )
                        .use { if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else null }
                val ordinal = previous?.first ?: count++
                bytes += size - (previous?.second ?: 0)
                added += size - (previous?.second ?: 0)
                require(count <= 10_000 && bytes <= MAX_INDEX_BYTES) {
                    "Chapter index exceeds the reader limit"
                }
                db.insertWithOnConflict(
                    "entries",
                    null,
                    ContentValues().apply {
                        put("generation", generation)
                        put("reference", reference)
                        put("ordinal", ordinal)
                        put("body", raw)
                        put("bytes", size)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                    .also { check(it != -1L) }
            }
            prune(db, added.coerceAtLeast(0), key, usageAlreadyIncludesAddition = true)
            val values =
                ContentValues().apply {
                    put("cursor", if (page.complete) null as String? else page.nextCursor)
                    put("pages", staging.pages + 1)
                    put("checked", staging.checked + page.entries.size)
                    put("found", count)
                    put("bytes", bytes)
                    put("updated", now)
                }
            check(db.update("generations", values, "id=?", arrayOf(generation.toString())) == 1)
            val owner =
                ContentValues().apply {
                    put("touched", now)
                    if (page.complete) {
                        put("ready", generation)
                        putNull("staging")
                    }
                }
            check(db.update("indexes", owner, "key=?", arrayOf(key)) == 1)
            if (page.complete) {
                before.ready?.let {
                    check(db.delete("generations", "id=?", arrayOf(it.id.toString())) == 1)
                }
            }
            read(db, key)
        }
    }

    override fun entries(generation: Long): List<JsonObject> =
        helper.readableDatabase
            .rawQuery(
                "SELECT body FROM entries WHERE generation=? ORDER BY ordinal",
                arrayOf(generation.toString()),
            )
            .use { rows ->
                buildList {
                    var bytes = 0L
                    while (rows.moveToNext()) {
                        check(size < 10_000)
                        val body = rows.getString(0)
                        bytes += body.toByteArray().size
                        check(bytes <= MAX_INDEX_BYTES)
                        add(StudioDocuments.json.parseToJsonElement(body).jsonObject)
                    }
                }
            }

    private fun prune(
        db: SQLiteDatabase,
        addition: Long,
        current: String,
        usageAlreadyIncludesAddition: Boolean = false,
    ) {
        fun usage(): Long =
            db.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM entries", null).use {
                check(it.moveToFirst())
                it.getLong(0)
            }
        val candidates =
            db.rawQuery("SELECT key FROM indexes ORDER BY touched,key", null).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
        var count = candidates.size
        val reserve = if (usageAlreadyIncludesAddition) 0L else addition
        var used = usage()
        for (key in candidates) {
            if (count <= 128 && used + reserve <= MAX_CACHE_BYTES) return
            if (key == current || pins[key]?.get()?.let { it > 0 } == true) continue
            check(db.delete("indexes", "key=?", arrayOf(key)) == 1)
            count--
            used = usage()
        }
        check(count <= 128 && used + reserve <= MAX_CACHE_BYTES) {
            "Private chapter index cache is full"
        }
    }

    private fun <T> transaction(db: SQLiteDatabase, operation: () -> T): T {
        db.beginTransaction()
        return try {
            operation().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun generation(rows: Cursor, offset: Int): StudioIndexGeneration? =
        if (rows.isNull(offset)) {
            null
        } else {
            StudioIndexGeneration(
                rows.getLong(offset),
                rows.getString(offset + 1),
                rows.getInt(offset + 2),
                rows.getInt(offset + 3),
                rows.getInt(offset + 4),
                rows.getLong(offset + 5),
                rows.getLong(offset + 6),
            )
        }

    private companion object {
        val KEY = Regex("[a-f0-9]{64}")
        const val MAX_INDEX_BYTES = 16L * 1024 * 1024
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
