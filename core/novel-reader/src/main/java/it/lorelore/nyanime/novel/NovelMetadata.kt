package it.lorelore.nyanime.novel

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Portable records are distinct from private document ownership and disposable indices. */
internal object NovelMetadata {
    fun create(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE portable_extras(kind TEXT NOT NULL,record_key TEXT NOT NULL,book_id TEXT,payload TEXT NOT NULL,PRIMARY KEY(kind,record_key))",
        )
        NovelJournal.create(db)
    }

    fun snapshot(
        db: SQLiteDatabase,
        includePositions: Boolean,
        includeAppearance: Boolean,
    ): NovelArchive {
        val records = mutableListOf<NovelRecord>()
        fun add(record: NovelRecord) {
            require(records.size < 200_000) { "Novel metadata exceeds the record budget" }
            records += record
        }
        db.query("books", arrayOf("id", "payload"), null, null, null, null, "id").use { rows ->
            while (rows.moveToNext()) {
                val payload = NovelJson.parseToJsonElement(rows.getString(1)).jsonObject
                add(
                    NovelRecord(
                        "book",
                        rows.getString(0),
                        rows.getString(0),
                        payload.getValue("modifiedAt").jsonPrimitive.long,
                        payload,
                    ),
                )
            }
        }
        if (includePositions) {
            db.query("positions", arrayOf("book_id", "payload"), null, null, null, null, "book_id")
                .use { rows ->
                    while (rows.moveToNext()) {
                        val payload = NovelJson.parseToJsonElement(rows.getString(1)).jsonObject
                        add(
                            NovelRecord(
                                "position",
                                rows.getString(0),
                                rows.getString(0),
                                payload.getValue("updatedAt").jsonPrimitive.long,
                                payload,
                            ),
                        )
                    }
                }
        }
        db.query(
            "annotations",
            arrayOf("id", "book_id", "modified_at", "payload"),
            null,
            null,
            null,
            null,
            "id",
        )
            .use { rows ->
                while (rows.moveToNext()) {
                    add(
                        NovelRecord(
                            "annotation",
                            rows.getString(0),
                            rows.getString(1),
                            rows.getLong(2),
                            NovelJson.parseToJsonElement(rows.getString(3)),
                        ),
                    )
                }
            }
        if (includeAppearance) {
            db.query(
                "appearance",
                arrayOf("owner", "updated_at", "payload"),
                null,
                null,
                null,
                null,
                "owner",
            )
                .use { rows ->
                    while (rows.moveToNext()) {
                        add(
                            NovelRecord(
                                "appearance",
                                rows.getString(0),
                                rows.getString(0).takeUnless { it == "global" },
                                rows.getLong(1),
                                NovelJson.parseToJsonElement(rows.getString(2)),
                            ),
                        )
                    }
                }
        }
        db.query("portable_extras", arrayOf("payload"), null, null, null, null, "kind,record_key")
            .use { rows ->
                while (rows.moveToNext()) add(NovelJson.decodeFromString(rows.getString(0)))
            }
        return NovelArchive(records = records).also { it.encode() }
    }

    /**
     * Entire restore runs in the caller's transaction; unsupported/dangling data cannot half-apply.
     */
    fun restore(db: SQLiteDatabase, archive: NovelArchive, sortTitle: (String) -> String) {
        archive.encode()
        val owners = archive.records.filter { it.kind == "book" }.mapTo(hashSetOf()) { it.key }
        for (record in archive.records) {
            val owner = record.bookId ?: continue
            if (owner !in owners) {
                check(
                    db.query("books", arrayOf("id"), "id=?", arrayOf(owner), null, null, null).use {
                        it.moveToFirst()
                    },
                ) {
                    "Novel metadata references a missing book"
                }
            }
        }
        val ordered = archive.records.sortedBy { if (it.kind == "book") 0 else 1 }
        for (record in ordered) apply(db, record, sortTitle, newerOnly = true)
    }

    fun read(db: SQLiteDatabase, kind: String, key: String): NovelRecord? {
        val (table, keyColumn) =
            when (kind) {
                "book" -> "books" to "id"
                "position" -> "positions" to "book_id"
                "annotation" -> "annotations" to "id"
                "appearance" -> "appearance" to "owner"
                else ->
                    return db.query(
                        "portable_extras",
                        arrayOf("payload"),
                        "kind=? AND record_key=?",
                        arrayOf(kind, key),
                        null,
                        null,
                        null,
                    )
                        .use {
                            if (it.moveToFirst()) {
                                NovelJson.decodeFromString<NovelRecord>(it.getString(0))
                            } else {
                                null
                            }
                        }
            }
        return db.query(table, null, "$keyColumn=?", arrayOf(key), null, null, null).use { row ->
            if (!row.moveToFirst()) return@use null
            val payload =
                NovelJson.parseToJsonElement(row.getString(row.getColumnIndexOrThrow("payload")))
            val owner =
                when (kind) {
                    "book",
                    "position",
                    -> key
                    "annotation" -> row.getString(row.getColumnIndexOrThrow("book_id"))
                    else -> key.takeUnless { it == "global" }
                }
            val stamp =
                when (kind) {
                    "book" -> payload.jsonObject.getValue("modifiedAt").jsonPrimitive.long
                    "annotation" -> row.getLong(row.getColumnIndexOrThrow("modified_at"))
                    else -> row.getLong(row.getColumnIndexOrThrow("updated_at"))
                }
            NovelRecord(kind, key, owner, stamp, payload)
        }
    }

    fun apply(
        db: SQLiteDatabase,
        incoming: NovelRecord,
        sortTitle: (String) -> String,
        newerOnly: Boolean,
    ): Boolean {
        val before = read(db, incoming.kind, incoming.key)
        if (before == incoming) return false
        if (newerOnly && before != null && before.updatedAt >= incoming.updatedAt) return false
        require(before == null || before.bookId == incoming.bookId) {
            "Novel metadata owner cannot change"
        }
        // Preserve fields unknown to this version, while explicit nulls clear known fields.
        val payload =
            if (before?.payload is JsonObject && incoming.payload is JsonObject) {
                JsonObject(before.payload + incoming.payload)
            } else {
                incoming.payload
            }
        val record = incoming.copy(payload = payload)
        if (before != null && record.kind == "book") {
            val oldBook = NovelJson.decodeFromJsonElement<NovelBook>(before.payload)
            val newBook = NovelJson.decodeFromJsonElement<NovelBook>(record.payload)
            require(oldBook.origin == newBook.origin && oldBook.format == newBook.format) {
                "Novel identity cannot be reassigned to another origin"
            }
        }
        val values = ContentValues()
        val table: String
        val keyColumn: String
        when (record.kind) {
            "book" -> {
                val book = NovelJson.decodeFromJsonElement<NovelBook>(record.payload)
                table = "books"
                keyColumn = "id"
                values.put("id", book.id)
                values.put("sort_title", sortTitle(book.title))
                values.put("favorite", if (book.favorite) 1 else 0)
                values.put("content_version", book.contentVersion)
            }
            "position" -> {
                val position = NovelJson.decodeFromJsonElement<NovelPosition>(record.payload)
                table = "positions"
                keyColumn = "book_id"
                values.put("book_id", position.bookId)
                values.put("content_version", position.contentVersion)
                values.put("updated_at", position.updatedAt)
                if (position.totalProgression == null) {
                    values.putNull("progression")
                } else {
                    values.put("progression", position.totalProgression)
                }
            }
            "annotation" -> {
                val note = NovelJson.decodeFromJsonElement<NovelAnnotation>(record.payload)
                table = "annotations"
                keyColumn = "id"
                values.put("id", note.id)
                values.put("book_id", note.bookId)
                values.put("modified_at", note.modifiedAt)
                values.put("deleted", if (note.deleted) 1 else 0)
            }
            "appearance" -> {
                table = "appearance"
                keyColumn = "owner"
                values.put("owner", record.key)
                values.put("updated_at", record.updatedAt)
            }
            else -> {
                values.put("kind", record.kind)
                values.put("record_key", record.key)
                values.put("book_id", record.bookId)
                values.put("payload", NovelJson.encodeToString(record))
                if (
                    db.update(
                        "portable_extras",
                        values,
                        "kind=? AND record_key=?",
                        arrayOf(record.kind, record.key),
                    ) == 0
                ) {
                    db.insertOrThrow("portable_extras", null, values)
                }
                return true
            }
        }
        values.put("payload", record.payload.toString())
        if (db.update(table, values, "$keyColumn=?", arrayOf(record.key)) == 0) {
            db.insertOrThrow(table, null, values)
        }
        return true
    }
}
