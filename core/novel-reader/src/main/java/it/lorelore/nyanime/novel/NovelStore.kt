package it.lorelore.nyanime.novel

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer
import java.util.Locale

/** Separate database: no publication text, EPUB locator or note enters manga/episode tables. */
class NovelStore(context: Context, databaseName: String = DATABASE_NAME) : AutoCloseable {
    companion object {
        const val DATABASE_NAME = "novel_reader_v1.db"

        @Volatile private var shared: NovelStore? = null

        fun shared(context: Context): NovelStore =
            shared
                ?: synchronized(this) {
                    shared ?: NovelStore(context.applicationContext).also { shared = it }
                }
    }

    override fun close() = helper.close()

    data class Cursor(val title: String, val id: String)

    data class ShelfItem(val book: NovelBook, val position: NovelPosition?)

    data class ShelfPage(val items: List<ShelfItem>, val next: Cursor?, val total: Int)

    private val helper = Database(context.applicationContext, databaseName)
    private val writes = Mutex()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    fun observePage(query: String = "", after: Cursor? = null, size: Int = 40) = revision.map {
        page(query, after, size)
    }

    fun observeBook(id: String) = revision.map { get(id) }

    suspend fun get(id: String): NovelBook? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase
                .query("books", arrayOf("payload"), "id=?", arrayOf(id), null, null, null)
                .use {
                    if (it.moveToFirst()) {
                        NovelDocuments.attach(
                            helper.readableDatabase,
                            NovelJson.decodeFromString<NovelBook>(it.getString(0)),
                        )
                    } else {
                        null
                    }
                }
        }

    suspend fun page(
        query: String = "",
        after: Cursor? = null,
        size: Int = 40,
        inProgressOnly: Boolean = false,
        localFilesOnly: Boolean = false,
    ): ShelfPage =
        withContext(Dispatchers.IO) {
            require(size in 1..200)
            val terms = mutableListOf("favorite=1")
            val arguments = mutableListOf<String>()
            if (localFilesOnly) {
                terms +=
                    "EXISTS (SELECT 1 FROM documents d WHERE d.book_id=books.id " +
                    "AND d.content_version=books.content_version AND d.offline_ready=1)"
            }
            if (inProgressOnly) {
                terms +=
                    "EXISTS (SELECT 1 FROM positions p WHERE p.book_id=books.id " +
                    "AND p.content_version=books.content_version AND " +
                    "(p.progression IS NULL OR p.progression<1.0))"
            }
            if (query.isNotBlank()) {
                terms += "books.sort_title LIKE ? ESCAPE '\\'"
                arguments +=
                    "%${sortTitle(query).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
            }
            val countWhere = terms.joinToString(" AND ")
            val total =
                helper.readableDatabase
                    .rawQuery(
                        "SELECT COUNT(*) FROM books WHERE $countWhere",
                        arguments.toTypedArray(),
                    )
                    .use {
                        it.moveToFirst()
                        it.getInt(0)
                    }
            if (after != null) {
                terms += "(books.sort_title > ? OR (books.sort_title = ? AND books.id > ?))"
                arguments += listOf(after.title, after.title, after.id)
            }
            val records = mutableListOf<Pair<Cursor, ShelfItem>>()
            helper.readableDatabase
                .rawQuery(
                    "SELECT books.id,books.sort_title,books.payload,positions.payload,documents.payload FROM books " +
                        "LEFT JOIN positions ON positions.book_id=books.id " +
                        "AND positions.content_version=books.content_version " +
                        "LEFT JOIN documents ON documents.book_id=books.id " +
                        "AND documents.content_version=books.content_version WHERE " +
                        terms.joinToString(" AND ") +
                        " ORDER BY books.sort_title ASC,books.id ASC LIMIT ?",
                    (arguments + (size + 1).toString()).toTypedArray(),
                )
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val book =
                            NovelDocuments.attach(
                                NovelJson.decodeFromString<NovelBook>(cursor.getString(2)),
                                if (cursor.isNull(4)) null else cursor.getString(4),
                            )
                        val position =
                            if (cursor.isNull(3)) {
                                null
                            } else {
                                NovelJson.decodeFromString<NovelPosition>(cursor.getString(3))
                            }
                        records +=
                            Cursor(cursor.getString(1), cursor.getString(0)) to
                            ShelfItem(book, position)
                    }
                }
            ShelfPage(
                records.take(size).map { it.second },
                records.getOrNull(size - 1)?.first?.takeIf {
                    records.size > size
                },
                total,
            )
        }

    suspend fun put(book: NovelBook) = mutate { db -> writeBook(db, book) }

    /** Only the local durable-download coordinator can set this; it never changes portable data. */
    suspend fun setOnlineOfflineAvailable(bookId: String, available: Boolean) =
        withContext(Dispatchers.IO) {
            writes.withLock {
                val db = helper.writableDatabase
                val book =
                    db.query("books", arrayOf("payload"), "id=?", arrayOf(bookId), null, null, null)
                        .use {
                            if (it.moveToFirst()) {
                                NovelJson.decodeFromString<NovelBook>(it.getString(0))
                            } else {
                                null
                            }
                        } ?: return@withLock
                if (book.format != NovelFormat.ONLINE) return@withLock
                val flag = if (available) 1 else 0
                val count =
                    db.update(
                        "documents",
                        ContentValues().apply { put("offline_ready", flag) },
                        "book_id=? AND content_version=? AND offline_ready!=?",
                        arrayOf(bookId, book.contentVersion, flag.toString()),
                    )
                if (count > 0) revision.value += 1
            }
        }

    /** Source metadata refreshes cannot undo a library action made during a network request. */
    suspend fun putOnline(book: NovelBook) = mutate { db ->
        require(book.format == NovelFormat.ONLINE)
        require(
            book.id ==
                NovelIdentity.remote(requireNotNull(book.origin).sourceId, book.origin.reference),
        )
        val previous =
            db.query("books", arrayOf("payload"), "id=?", arrayOf(book.id), null, null, null).use {
                if (it.moveToFirst()) {
                    NovelDocuments.attach(
                        db,
                        NovelJson.decodeFromString<NovelBook>(it.getString(0)),
                    )
                } else {
                    null
                }
            }
        val merged =
            if (previous == null) {
                book
            } else {
                require(previous.format == NovelFormat.ONLINE && previous.origin == book.origin)
                val candidate =
                    book.copy(
                        favorite = previous.favorite,
                        importedAt = previous.importedAt,
                        coverFile = book.coverFile ?: previous.coverFile,
                    )
                // A local index/cache update is not a portable metadata edit.
                val unchanged =
                    candidate.copy(
                        modifiedAt = previous.modifiedAt,
                        publicationFile = previous.publicationFile,
                        originalFile = previous.originalFile,
                        coverFile = previous.coverFile,
                    ) == previous
                if (unchanged) {
                    candidate.copy(modifiedAt = previous.modifiedAt)
                } else {
                    candidate.copy(
                        modifiedAt = maxOf(book.modifiedAt, nextNovelTimestamp(previous.modifiedAt)),
                    )
                }
            }
        writeBook(db, merged)
    }

    private fun writeBook(db: SQLiteDatabase, book: NovelBook) {
        val values =
            ContentValues().apply {
                put("id", book.id)
                put("sort_title", sortTitle(book.title))
                put("favorite", if (book.favorite) 1 else 0)
                put("content_version", book.contentVersion)
                put("payload", merge(db, "books", "id", book.id, NovelJson.encodeToString(book)))
            }
        // REPLACE would delete the old owner and cascade its notes/position.
        NovelRecord(
            "book",
            book.id,
            book.id,
            book.modifiedAt,
            NovelJson.parseToJsonElement(values.getAsString("payload")),
        )
        if (db.update("books", values, "id=?", arrayOf(book.id)) == 0) {
            db.insertOrThrow("books", null, values)
        }
        NovelDocuments.put(db, book)
    }

    suspend fun position(bookId: String): NovelPosition? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase
                .query(
                    "positions",
                    arrayOf("payload"),
                    "book_id=?",
                    arrayOf(bookId),
                    null,
                    null,
                    null,
                )
                .use {
                    if (it.moveToFirst()) {
                        NovelJson.decodeFromString<NovelPosition>(it.getString(0))
                    } else {
                        null
                    }
                }
        }

    suspend fun savePosition(position: NovelPosition) = mutate { db ->
        val book =
            db.query(
                "books",
                arrayOf("payload"),
                "id=?",
                arrayOf(position.bookId),
                null,
                null,
                null,
            )
                .use {
                    if (it.moveToFirst()) {
                        NovelJson.decodeFromString<NovelBook>(it.getString(0))
                    } else {
                        null
                    }
                } ?: return@mutate
        if (book.contentVersion != position.contentVersion) return@mutate
        val previous =
            db.query(
                "positions",
                arrayOf("payload"),
                "book_id=?",
                arrayOf(position.bookId),
                null,
                null,
                null,
            )
                .use {
                    if (it.moveToFirst()) {
                        NovelJson.decodeFromString<NovelPosition>(it.getString(0))
                    } else {
                        null
                    }
                }
        if (
            previous?.contentVersion == position.contentVersion &&
            previous.updatedAt > position.updatedAt
        ) {
            return@mutate
        }
        val values =
            ContentValues().apply {
                put("book_id", position.bookId)
                put("content_version", position.contentVersion)
                put("updated_at", position.updatedAt)
                if (position.totalProgression == null) {
                    putNull("progression")
                } else {
                    put("progression", position.totalProgression)
                }
                put(
                    "payload",
                    merge(
                        db,
                        "positions",
                        "book_id",
                        position.bookId,
                        NovelJson.encodeToString(position),
                    ),
                )
            }
        NovelRecord(
            "position",
            position.bookId,
            position.bookId,
            position.updatedAt,
            NovelJson.parseToJsonElement(values.getAsString("payload")),
        )
        if (db.update("positions", values, "book_id=?", arrayOf(position.bookId)) == 0) {
            db.insertOrThrow("positions", null, values)
        }
    }

    fun observeAnnotations(bookId: String) = revision.map { annotations(bookId) }

    suspend fun annotations(
        bookId: String,
        includeDeleted: Boolean = false,
    ): List<NovelAnnotation> =
        withContext(Dispatchers.IO) {
            val rows = mutableListOf<NovelAnnotation>()
            helper.readableDatabase
                .query(
                    "annotations",
                    arrayOf("payload"),
                    if (includeDeleted) "book_id=?" else "book_id=? AND deleted=0",
                    arrayOf(bookId),
                    null,
                    null,
                    "modified_at DESC,id ASC",
                )
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        rows += NovelJson.decodeFromString<NovelAnnotation>(cursor.getString(0))
                    }
                }
            rows
        }

    suspend fun putAnnotation(annotation: NovelAnnotation) = mutate { db ->
        db.query(
            "annotations",
            arrayOf("book_id"),
            "id=?",
            arrayOf(annotation.id),
            null,
            null,
            null,
        )
            .use {
                require(!it.moveToFirst() || it.getString(0) == annotation.bookId) {
                    "Annotation belongs to a different book"
                }
            }
        val values =
            ContentValues().apply {
                put("id", annotation.id)
                put("book_id", annotation.bookId)
                put("modified_at", annotation.modifiedAt)
                put("deleted", if (annotation.deleted) 1 else 0)
                put(
                    "payload",
                    merge(
                        db,
                        "annotations",
                        "id",
                        annotation.id,
                        NovelJson.encodeToString(annotation),
                    ),
                )
            }
        NovelRecord(
            "annotation",
            annotation.id,
            annotation.bookId,
            annotation.modifiedAt,
            NovelJson.parseToJsonElement(values.getAsString("payload")),
        )
        if (db.update("annotations", values, "id=?", arrayOf(annotation.id)) == 0) {
            db.insertOrThrow("annotations", null, values)
        }
    }

    suspend fun appearance(bookId: String?): NovelAppearance? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase
                .query(
                    "appearance",
                    arrayOf("payload"),
                    "owner=?",
                    arrayOf(bookId ?: "global"),
                    null,
                    null,
                    null,
                )
                .use {
                    if (it.moveToFirst()) {
                        it.getString(0)
                            .takeUnless { value -> value == "null" }
                            ?.let { value -> NovelJson.decodeFromString<NovelAppearance>(value) }
                    } else {
                        null
                    }
                }
        }

    suspend fun saveAppearance(bookId: String?, appearance: NovelAppearance?) = mutate { db ->
        val owner = bookId ?: "global"
        val previousStamp =
            db.query(
                "appearance",
                arrayOf("updated_at"),
                "owner=?",
                arrayOf(owner),
                null,
                null,
                null,
            )
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val values =
            ContentValues().apply {
                put("owner", owner)
                put("updated_at", nextNovelTimestamp(previousStamp))
                put(
                    "payload",
                    if (appearance == null) {
                        "null"
                    } else {
                        merge(
                            db,
                            "appearance",
                            "owner",
                            owner,
                            NovelJson.encodeToString(appearance),
                        )
                    },
                )
            }
        NovelRecord(
            "appearance",
            owner,
            bookId,
            values.getAsLong("updated_at"),
            NovelJson.parseToJsonElement(values.getAsString("payload")),
        )
        val previousPayload =
            db.query("appearance", arrayOf("payload"), "owner=?", arrayOf(owner), null, null, null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
        if (previousPayload == values.getAsString("payload")) return@mutate
        if (db.update("appearance", values, "owner=?", arrayOf(owner)) == 0) {
            db.insertOrThrow("appearance", null, values)
        }
    }

    /** Consistent portable snapshot: files and the disposable search index never enter it. */
    suspend fun archive(
        includePositions: Boolean = true,
        includeAppearance: Boolean = true,
    ): NovelArchive =
        withContext(Dispatchers.IO) {
            writes.withLock {
                val db = helper.writableDatabase
                db.beginTransactionNonExclusive()
                try {
                    NovelMetadata.snapshot(db, includePositions, includeAppearance).also {
                        db.setTransactionSuccessful()
                    }
                } finally {
                    db.endTransaction()
                }
            }
        }

    suspend fun restore(archive: NovelArchive) = mutate { db ->
        NovelMetadata.restore(db, archive, ::sortTitle)
    }

    suspend fun record(kind: String, key: String): NovelRecord? =
        withContext(Dispatchers.IO) {
            NovelMetadata.read(helper.readableDatabase, kind, key)
        }

    suspend fun changes(after: Long, limit: Int = 50): List<NovelChange> =
        withContext(Dispatchers.IO) {
            NovelJournal.changes(helper.readableDatabase, after, limit)
        }

    suspend fun pending(after: Long): Long =
        withContext(Dispatchers.IO) {
            NovelJournal.pending(helper.readableDatabase, after)
        }

    /** Compare and apply in the same transaction: a remote receipt cannot erase an offline edit. */
    suspend fun project(record: NovelRecord, expected: NovelRecord?): Boolean {
        var accepted = false
        mutate { db ->
            if (NovelMetadata.read(db, record.kind, record.key) != expected) return@mutate
            if (
                record.kind != "book" &&
                record.bookId != null &&
                NovelMetadata.read(db, "book", record.bookId) == null
            ) {
                return@mutate
            }
            if (record.kind == "position") {
                val parent =
                    NovelMetadata.read(db, "book", requireNotNull(record.bookId)) ?: return@mutate
                val book = NovelJson.decodeFromString<NovelBook>(parent.payload.toString())
                val position = NovelJson.decodeFromString<NovelPosition>(record.payload.toString())
                if (book.contentVersion != position.contentVersion) return@mutate
            }
            NovelMetadata.apply(db, record, ::sortTitle, newerOnly = false)
            accepted = true
        }
        return accepted
    }

    data class ReadingCursor(val updatedAt: Long, val bookId: String)

    data class ReadingPage(val items: List<ShelfItem>, val next: ReadingCursor?, val total: Int)

    /** History includes completed books and titles removed from favorites, ordered by use. */
    suspend fun readingPage(
        query: String = "",
        after: ReadingCursor? = null,
        size: Int = 40,
        inProgressOnly: Boolean = false,
        favoritesOnly: Boolean = false,
        localFilesOnly: Boolean = false,
    ): ReadingPage =
        withContext(Dispatchers.IO) {
            require(size in 1..200)
            val terms = mutableListOf("p.content_version=b.content_version")
            val args = mutableListOf<String>()
            if (favoritesOnly) terms += "b.favorite=1"
            if (inProgressOnly) terms += "(p.progression IS NULL OR p.progression<1)"
            if (localFilesOnly) terms += "d.offline_ready=1"
            if (query.isNotBlank()) {
                terms += "b.sort_title LIKE ? ESCAPE '!'"
                args +=
                    "%${sortTitle(query).replace("!", "!!").replace("%", "!%").replace("_", "!_")}%"
            }
            val joins =
                " FROM positions p JOIN books b ON b.id=p.book_id " +
                    "LEFT JOIN documents d ON d.book_id=b.id AND d.content_version=b.content_version WHERE "
            val total =
                helper.readableDatabase
                    .rawQuery(
                        "SELECT COUNT(*)" + joins + terms.joinToString(" AND "),
                        args.toTypedArray(),
                    )
                    .use {
                        it.moveToFirst()
                        it.getInt(0)
                    }
            if (after != null) {
                terms += "(p.updated_at<? OR (p.updated_at=? AND p.book_id>?))"
                args += listOf(after.updatedAt.toString(), after.updatedAt.toString(), after.bookId)
            }
            val result = mutableListOf<Pair<ReadingCursor, ShelfItem>>()
            helper.readableDatabase
                .rawQuery(
                    "SELECT b.payload,p.payload,d.payload,p.updated_at,p.book_id" +
                        joins +
                        terms.joinToString(" AND ") +
                        " ORDER BY p.updated_at DESC,p.book_id ASC LIMIT ?",
                    (args + (size + 1).toString()).toTypedArray(),
                )
                .use { rows ->
                    while (rows.moveToNext()) {
                        result +=
                            ReadingCursor(rows.getLong(3), rows.getString(4)) to
                            ShelfItem(
                                NovelDocuments.attach(
                                    NovelJson.decodeFromString<NovelBook>(rows.getString(0)),
                                    if (rows.isNull(2)) null else rows.getString(2),
                                ),
                                NovelJson.decodeFromString<NovelPosition>(rows.getString(1)),
                            )
                    }
                }
            ReadingPage(
                result.take(size).map { it.second },
                result.getOrNull(size - 1)?.first?.takeIf { result.size > size },
                total,
            )
        }

    suspend fun recent(limit: Int = 20, localFilesOnly: Boolean = false): List<ShelfItem> =
        readingPage(
            size = limit,
            inProgressOnly = true,
            favoritesOnly = true,
            localFilesOnly = localFilesOnly,
        )
            .items

    /** Removing a title from the shelf retains its notes, position and locally owned files. */
    suspend fun setFavorite(bookId: String, favorite: Boolean) = mutate { db ->
        val current =
            db.query("books", arrayOf("payload"), "id=?", arrayOf(bookId), null, null, null).use {
                if (it.moveToFirst()) {
                    NovelJson.decodeFromString<NovelBook>(it.getString(0))
                } else {
                    null
                }
            } ?: return@mutate
        if (current.favorite == favorite) return@mutate
        val updated =
            current.copy(
                favorite = favorite,
                modifiedAt = nextNovelTimestamp(current.modifiedAt),
            )
        val payload = merge(db, "books", "id", bookId, NovelJson.encodeToString(updated))
        NovelRecord(
            "book",
            bookId,
            bookId,
            updated.modifiedAt,
            NovelJson.parseToJsonElement(payload),
        )
        db.update(
            "books",
            ContentValues().apply {
                put("favorite", if (favorite) 1 else 0)
                put("payload", payload)
            },
            "id=?",
            arrayOf(bookId),
        )
    }

    private suspend fun mutate(operation: (SQLiteDatabase) -> Unit) =
        withContext(Dispatchers.IO) {
            writes.withLock {
                val database = helper.writableDatabase
                database.beginTransaction()
                try {
                    operation(database)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                revision.value += 1
            }
        }

    private fun merge(
        db: SQLiteDatabase,
        table: String,
        key: String,
        id: String,
        payload: String,
    ): String {
        val previous =
            db.query(table, arrayOf("payload"), "$key=?", arrayOf(id), null, null, null).use {
                if (it.moveToFirst()) {
                    NovelJson.parseToJsonElement(it.getString(0)) as? JsonObject
                        ?: JsonObject(emptyMap())
                } else {
                    JsonObject(emptyMap())
                }
            }
        return JsonObject(
            (previous + NovelJson.parseToJsonElement(payload).jsonObject).filterKeys {
                table != "books" || it !in NovelRecord.PRIVATE_BOOK_FIELDS
            },
        )
            .toString()
    }

    private fun sortTitle(title: String) =
        Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"), "")
            .lowercase(Locale.ROOT)

    private class Database(context: Context, databaseName: String) :
        SQLiteOpenHelper(context, databaseName, null, 3) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE books(id TEXT PRIMARY KEY,sort_title TEXT NOT NULL,favorite INTEGER NOT NULL,content_version TEXT NOT NULL,payload TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX books_shelf ON books(favorite,sort_title,id)")
            db.execSQL(
                "CREATE TABLE positions(book_id TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,content_version TEXT NOT NULL,updated_at INTEGER NOT NULL,progression REAL,payload TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE annotations(id TEXT PRIMARY KEY,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,modified_at INTEGER NOT NULL,deleted INTEGER NOT NULL,payload TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX annotations_owner ON annotations(book_id,deleted,modified_at)")
            db.execSQL(
                "CREATE TABLE appearance(owner TEXT PRIMARY KEY,updated_at INTEGER NOT NULL,payload TEXT NOT NULL)",
            )
            NovelMetadata.create(db)
            NovelDocuments.create(db)
            db.execSQL("CREATE INDEX positions_recent ON positions(updated_at DESC,book_id)")
            NovelJournal.seed(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..2 && newVersion == 3) {
                "Unsupported novel database migration: $oldVersion to $newVersion"
            }
            if (oldVersion == 2) {
                NovelDocuments.upgradeFromTwo(db)
                return
            }
            db.execSQL("ALTER TABLE books ADD COLUMN content_version TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE positions ADD COLUMN content_version TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE positions ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE positions ADD COLUMN progression REAL")
            db.execSQL("ALTER TABLE appearance ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            NovelMetadata.create(db)
            NovelDocuments.create(db)
            db.query("books", arrayOf("id", "payload"), null, null, null, null, null).use { rows ->
                while (rows.moveToNext()) {
                    val raw = NovelJson.parseToJsonElement(rows.getString(1)).jsonObject
                    val book = NovelJson.decodeFromString<NovelBook>(rows.getString(1))
                    NovelDocuments.migrate(db, raw, book)
                    db.update(
                        "books",
                        ContentValues().apply {
                            put("content_version", book.contentVersion)
                            put(
                                "payload",
                                JsonObject(
                                    raw.filterKeys { it !in NovelRecord.PRIVATE_BOOK_FIELDS },
                                )
                                    .toString(),
                            )
                        },
                        "id=?",
                        arrayOf(book.id),
                    )
                }
            }
            db.query("positions", arrayOf("book_id", "payload"), null, null, null, null, null)
                .use { rows ->
                    while (rows.moveToNext()) {
                        val position = NovelJson.decodeFromString<NovelPosition>(rows.getString(1))
                        db.update(
                            "positions",
                            ContentValues().apply {
                                put("content_version", position.contentVersion)
                                put("updated_at", position.updatedAt)
                                if (position.totalProgression == null) {
                                    putNull("progression")
                                } else {
                                    put("progression", position.totalProgression)
                                }
                            },
                            "book_id=?",
                            arrayOf(position.bookId),
                        )
                    }
                }
            db.execSQL("CREATE INDEX positions_recent ON positions(updated_at DESC,book_id)")
            NovelJournal.seed(db)
        }
    }
}
