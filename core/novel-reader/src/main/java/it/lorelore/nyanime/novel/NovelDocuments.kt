package it.lorelore.nyanime.novel

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Private ownership has its own table and never enters portable metadata. */
internal object NovelDocuments {
    fun create(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE documents(book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,content_version TEXT NOT NULL,payload TEXT NOT NULL,offline_ready INTEGER NOT NULL DEFAULT 0 CHECK(offline_ready IN (0,1)),PRIMARY KEY(book_id,content_version))",
        )
        createAvailabilityIndex(db)
    }

    fun put(db: SQLiteDatabase, book: NovelBook) {
        if (listOfNotNull(book.originalFile, book.publicationFile, book.coverFile).isEmpty()) return
        val downloaded =
            if (book.format == NovelFormat.ONLINE) {
                db.query(
                    "documents",
                    arrayOf("offline_ready"),
                    "book_id=? AND content_version=?",
                    arrayOf(book.id, book.contentVersion),
                    null,
                    null,
                    null,
                )
                    .use {
                        it.moveToFirst() && it.getInt(0) == 1
                    }
            } else {
                book.publicationFile != null
            }
        val document =
            NovelDocument(
                book.id,
                book.contentVersion,
                book.originalFile,
                book.publicationFile,
                book.coverFile,
                book.modifiedAt,
            )
        val values =
            ContentValues().apply {
                put("book_id", book.id)
                put("content_version", book.contentVersion)
                put("payload", NovelJson.encodeToString(document))
                // A cached cover or online manifest is not a downloaded publication.
                put(
                    "offline_ready",
                    if (downloaded) 1 else 0,
                )
            }
        if (
            db.update(
                "documents",
                values,
                "book_id=? AND content_version=?",
                arrayOf(book.id, book.contentVersion),
            ) == 0
        ) {
            db.insertOrThrow("documents", null, values)
        }
    }

    fun upgradeFromTwo(db: SQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE documents ADD COLUMN offline_ready INTEGER NOT NULL DEFAULT 0 CHECK(offline_ready IN (0,1))",
        )
        db.rawQuery(
            "SELECT d.book_id,d.content_version,d.payload,b.payload FROM documents d JOIN books b ON b.id=d.book_id",
            null,
        )
            .use { rows ->
                while (rows.moveToNext()) {
                    val document = NovelJson.decodeFromString<NovelDocument>(rows.getString(2))
                    val book = NovelJson.decodeFromString<NovelBook>(rows.getString(3))
                    if (book.format != NovelFormat.ONLINE && document.publicationFile != null) {
                        db.update(
                            "documents",
                            ContentValues().apply { put("offline_ready", 1) },
                            "book_id=? AND content_version=?",
                            arrayOf(rows.getString(0), rows.getString(1)),
                        )
                    }
                }
            }
        createAvailabilityIndex(db)
    }

    private fun createAvailabilityIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX documents_offline ON documents(offline_ready,book_id,content_version)",
        )
    }

    fun attach(db: SQLiteDatabase, book: NovelBook): NovelBook {
        val payload =
            db.query(
                "documents",
                arrayOf("payload"),
                "book_id=? AND content_version=?",
                arrayOf(book.id, book.contentVersion),
                null,
                null,
                null,
            )
                .use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
        return attach(book, payload)
    }

    fun attach(book: NovelBook, payload: String?): NovelBook {
        if (payload == null) return book
        val document = NovelJson.decodeFromString<NovelDocument>(payload)
        check(document.bookId == book.id && document.contentVersion == book.contentVersion)
        return book.copy(
            originalFile = document.originalFile,
            publicationFile = document.publicationFile,
            coverFile = document.coverFile,
        )
    }

    fun migrate(db: SQLiteDatabase, raw: JsonObject, book: NovelBook) {
        fun path(key: String) = raw[key]?.jsonPrimitive?.contentOrNull
        put(
            db,
            book.copy(
                originalFile = path("originalFile"),
                publicationFile = path("publicationFile"),
                coverFile = path("coverFile"),
            ),
        )
    }
}
