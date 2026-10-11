package it.lorelore.nyanime.novel

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Disposable full-text index, intentionally separate from book metadata and backup. */
class NovelSearch(
    context: Context,
    private val limits: Limits = Limits(),
    databaseName: String = "novel_search_v1.db",
) : AutoCloseable {
    init {
        require(databaseName.matches(Regex("[a-z0-9_]{1,80}\\.db")))
    }

    data class Limits(
        val bookBytes: Long = 32L * 1024 * 1024,
        val totalBytes: Long = 64L * 1024 * 1024,
        val databaseBytes: Long = 192L * 1024 * 1024,
        val books: Int = 64,
    ) {
        init {
            require(bookBytes in 1..totalBytes && totalBytes <= databaseBytes)
            require(databaseBytes in 4L * 1024 * 1024..256L * 1024 * 1024 && books in 1..128)
        }
    }

    enum class Phase {
        PREPARING,
        INDEXING,
    }

    data class Progress(
        val completed: Int,
        val total: Int,
        val failed: Int,
        val limited: Boolean = false,
        val active: Boolean = true,
        val phase: Phase = Phase.INDEXING,
    )

    data class CacheState(val textBytes: Long, val books: Int, val limitBytes: Long)

    data class Hit(
        val href: String,
        val chapter: String,
        val excerpt: String,
        val before: String,
        val highlight: String,
        val after: String,
        val characterOffset: Int,
    )

    private val database = Index(context.applicationContext, limits, databaseName)
    private val writes = Mutex()

    /** The owner cancels/joins indexing and queries before closing a disposable search session. */
    override fun close() = database.close()

    suspend fun prepare(
        opened: NovelLibrary.Opened,
        onProgress: suspend (Progress) -> Unit = {},
    ): NovelSearchScope = prepareNovelSearchScope(opened, limits.bookBytes, onProgress)

    suspend fun index(scope: NovelSearchScope, onProgress: suspend (Progress) -> Unit) =
        indexSelected(
            scope.book,
            scope.publication,
            {
                onProgress(
                    it.copy(
                        limited = it.limited || scope.limited,
                        failed = it.failed + scope.unavailable,
                    ),
                )
            },
            scope.hrefs,
        )

    suspend fun index(
        book: NovelBook,
        publication: Publication,
        onProgress: suspend (Progress) -> Unit,
    ) = indexSelected(book, publication, onProgress, null)

    private suspend fun indexSelected(
        book: NovelBook,
        publication: Publication,
        onProgress: suspend (Progress) -> Unit,
        allowedHrefs: Set<String>?,
    ) =
        withContext(Dispatchers.IO) {
            writes.withLock {
                val db = database.writableDatabase
                pruneOtherVersions(db, book)
                transaction(db) {
                    val previous =
                        db.rawQuery(
                            "SELECT body_bytes FROM cached_books WHERE book=?",
                            arrayOf(book.id),
                        )
                            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    if (previous > limits.bookBytes) evict(db, book.id)
                    db.execSQL(
                        "INSERT OR IGNORE INTO cached_books(book,version,last_used,body_bytes) VALUES(?,?,?,0)",
                        arrayOf<Any>(book.id, book.contentVersion, System.currentTimeMillis()),
                    )
                    makeRoom(db, book, 0)
                }
                var completed = 0
                var failed = 0
                var limited = false
                val total =
                    publication.readingOrder.count {
                        allowedHrefs == null || publication.url(it).toString() in allowedHrefs
                    }
                for ((ordinal, link) in publication.readingOrder.withIndex()) {
                    coroutineContext.ensureActive()
                    val href = publication.url(link).toString()
                    if (allowedHrefs != null && href !in allowedHrefs) continue
                    val indexed =
                        db.rawQuery(
                            "SELECT 1 FROM indexed_chapters WHERE book=? AND version=? AND href=?",
                            arrayOf(book.id, book.contentVersion, href),
                        )
                            .use { it.moveToFirst() }
                    if (!indexed) {
                        try {
                            val resource =
                                publication.get(link)
                                    ?: throw IOException("Missing chapter resource")
                            val html =
                                try {
                                    resource
                                        .read(0L..NovelHtmlPolicy.MAX_HTML_BYTES.toLong())
                                        .getOrElse { throw IOException(it.message) }
                                } finally {
                                    resource.close()
                                }
                            require(html.size <= NovelHtmlPolicy.MAX_HTML_BYTES)
                            val document = Jsoup.parse(html.toString(Charsets.UTF_8))
                            document.select("script,style,nav").remove()
                            val text = document.body().text()
                            val title = link.title.orEmpty().ifBlank { document.title() }.take(2048)
                            val context = coroutineContext
                            val cost =
                                passages(text).sumOf { (_, part) ->
                                    context.ensureActive()
                                    part.toByteArray(Charsets.UTF_8).size.toLong()
                                }
                            transaction(db) {
                                makeRoom(db, book, cost)
                                for ((offset, part) in passages(text)) {
                                    context.ensureActive()
                                    val row =
                                        db.insertOrThrow(
                                            "passages",
                                            null,
                                            ContentValues().apply {
                                                put("book", book.id)
                                                put("version", book.contentVersion)
                                                put("href", href)
                                                put("chapter", title)
                                                put("ordinal", ordinal)
                                                put("offset", offset)
                                                put("body", part)
                                            },
                                        )
                                    db.insertOrThrow(
                                        "passage_fts",
                                        null,
                                        ContentValues().apply {
                                            put("rowid", row)
                                            put("body", part)
                                        },
                                    )
                                }
                                db.insertOrThrow(
                                    "indexed_chapters",
                                    null,
                                    ContentValues().apply {
                                        put("book", book.id)
                                        put("version", book.contentVersion)
                                        put("href", href)
                                    },
                                )
                                db.execSQL(
                                    "UPDATE cached_books SET body_bytes=body_bytes+?,last_used=? WHERE book=?",
                                    arrayOf<Any>(cost, System.currentTimeMillis(), book.id),
                                )
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: BudgetExceeded) {
                            limited = true
                            break
                        } catch (_: SQLiteFullException) {
                            limited = true
                            break
                        } catch (_: IOException) {
                            failed++
                        } catch (_: IllegalArgumentException) {
                            failed++
                        }
                    }
                    completed++
                    onProgress(Progress(completed, total, failed))
                }
                onProgress(
                    Progress(
                        completed,
                        total,
                        failed,
                        limited,
                        active = false,
                    ),
                )
            }
        }

    suspend fun find(book: NovelBook, query: String, limit: Int = 100): List<Hit> =
        withContext(Dispatchers.IO) {
            require(limit in 1..200)
            val parsed = NovelSearchQuery(query)
            if (parsed.terms.isEmpty()) return@withContext emptyList()
            coroutineContext.ensureActive()
            database.writableDatabase.execSQL(
                "UPDATE cached_books SET last_used=? WHERE book=?",
                arrayOf<Any>(System.currentTimeMillis(), book.id),
            )
            // unicode61 handles accent folding. Scripts without word boundaries need literal
            // matching.
            val sql =
                if (parsed.literal) {
                    "SELECT href,chapter,body,offset FROM passages " +
                        "WHERE book=? AND version=? AND body LIKE ? ESCAPE '\\' " +
                        "ORDER BY ordinal,offset LIMIT ?"
                } else {
                    "SELECT p.href,p.chapter,p.body,p.offset FROM passage_fts " +
                        "JOIN passages p ON p.id=passage_fts.rowid " +
                        "WHERE p.book=? AND p.version=? AND passage_fts MATCH ? ORDER BY p.ordinal,p.offset LIMIT ?"
                }
            val hits = mutableListOf<Hit>()
            database.readableDatabase
                .rawQuery(
                    sql,
                    arrayOf(book.id, book.contentVersion, parsed.expression, limit.toString()),
                )
                .use { rows ->
                    while (rows.moveToNext()) {
                        coroutineContext.ensureActive()
                        val body = rows.getString(2)
                        val range = parsed.range(body) ?: continue
                        val start = range.first
                        val end = range.last + 1
                        hits +=
                            Hit(
                                href = rows.getString(0),
                                chapter = rows.getString(1),
                                excerpt =
                                body.substring(
                                    (start - 70).coerceAtLeast(0),
                                    minOf(end + 140, body.length),
                                ),
                                before = body.substring((start - 80).coerceAtLeast(0), start),
                                highlight = body.substring(start, end),
                                after = body.substring(end, minOf(end + 80, body.length)),
                                characterOffset = rows.getInt(3) + start,
                            )
                    }
                }
            hits.distinctBy { it.href to it.characterOffset }
        }

    private fun pruneOtherVersions(db: SQLiteDatabase, book: NovelBook) =
        transaction(db) {
            val args = arrayOf(book.id, book.contentVersion)
            db.execSQL(
                "DELETE FROM passage_fts WHERE rowid IN (SELECT id FROM passages WHERE book=? AND version<>?)",
                args,
            )
            db.delete("passages", "book=? AND version<>?", args)
            db.delete("indexed_chapters", "book=? AND version<>?", args)
            db.delete("cached_books", "book=? AND version<>?", args)
        }

    /** Explicitly disposable: book files, notes and positions never enter this database. */
    suspend fun clear(bookId: String? = null) =
        withContext(Dispatchers.IO) {
            writes.withLock {
                val db = database.writableDatabase
                transaction(db) {
                    if (bookId != null) {
                        evict(db, bookId)
                    } else {
                        db.delete("passage_fts", null, null)
                        db.delete("passages", null, null)
                        db.delete("indexed_chapters", null, null)
                        db.delete("cached_books", null, null)
                    }
                }
                db.rawQuery("PRAGMA incremental_vacuum", null).use { cursor ->
                    while (cursor.moveToNext()) coroutineContext.ensureActive()
                }
            }
        }

    suspend fun cacheState(): CacheState =
        withContext(Dispatchers.IO) {
            database.readableDatabase
                .rawQuery(
                    "SELECT COALESCE(SUM(body_bytes),0),COUNT(*) FROM cached_books",
                    null,
                )
                .use { cursor ->
                    cursor.moveToFirst()
                    CacheState(cursor.getLong(0), cursor.getInt(1), limits.totalBytes)
                }
        }

    private class BudgetExceeded : IOException("Search cache budget reached")

    private fun makeRoom(db: SQLiteDatabase, book: NovelBook, cost: Long) {
        val ownBytes =
            db.rawQuery(
                "SELECT body_bytes FROM cached_books WHERE book=?",
                arrayOf(book.id),
            )
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (ownBytes + cost > limits.bookBytes) throw BudgetExceeded()
        while (true) {
            val (bytes, count) =
                db.rawQuery(
                    "SELECT COALESCE(SUM(body_bytes),0),COUNT(*) FROM cached_books",
                    null,
                )
                    .use {
                        it.moveToFirst()
                        it.getLong(0) to it.getInt(1)
                    }
            if (bytes + cost <= limits.totalBytes && count <= limits.books) return
            val victim =
                db.rawQuery(
                    "SELECT book FROM cached_books WHERE book<>? ORDER BY last_used ASC,book ASC LIMIT 1",
                    arrayOf(book.id),
                )
                    .use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: throw BudgetExceeded()
            evict(db, victim)
        }
    }

    private fun evict(db: SQLiteDatabase, bookId: String) {
        val args = arrayOf(bookId)
        db.execSQL(
            "DELETE FROM passage_fts WHERE rowid IN (SELECT id FROM passages WHERE book=?)",
            args,
        )
        db.delete("passages", "book=?", args)
        db.delete("indexed_chapters", "book=?", args)
        db.delete("cached_books", "book=?", args)
    }

    private fun passages(text: String) = sequence {
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + 32 * 1024, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            yield(offset to text.substring(offset, end))
            if (end == text.length) break
            offset = end - 256
            if (text[offset].isLowSurrogate()) offset++
        }
    }

    private inline fun transaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private class Index(context: Context, private val limits: Limits, databaseName: String) :
        SQLiteOpenHelper(context, databaseName, null, 2) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE passages(id INTEGER PRIMARY KEY,book TEXT NOT NULL,version TEXT NOT NULL,href TEXT NOT NULL,chapter TEXT NOT NULL,ordinal INTEGER NOT NULL,offset INTEGER NOT NULL,body TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX passages_owner ON passages(book,version,ordinal,offset)")
            db.execSQL("CREATE VIRTUAL TABLE passage_fts USING fts4(body,tokenize=unicode61)")
            db.execSQL(
                "CREATE TABLE indexed_chapters(book TEXT NOT NULL,version TEXT NOT NULL,href TEXT NOT NULL,PRIMARY KEY(book,version,href))",
            )
            db.execSQL(
                "CREATE TABLE cached_books(book TEXT PRIMARY KEY,version TEXT NOT NULL,last_used INTEGER NOT NULL,body_bytes INTEGER NOT NULL)",
            )
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            if (!db.isReadOnly) {
                db.setMaximumSize(limits.databaseBytes)
                val mode =
                    db.rawQuery("PRAGMA auto_vacuum", null).use {
                        it.moveToFirst()
                        it.getInt(0)
                    }
                if (mode != 2) {
                    db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                    db.execSQL("VACUUM")
                }
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Only derived data is rebuilt; the metadata database is untouched.
            db.execSQL("DROP TABLE IF EXISTS passage_fts")
            db.execSQL("DROP TABLE IF EXISTS passages")
            db.execSQL("DROP TABLE IF EXISTS indexed_chapters")
            db.execSQL("DROP TABLE IF EXISTS cached_books")
            onCreate(db)
        }
    }
}
