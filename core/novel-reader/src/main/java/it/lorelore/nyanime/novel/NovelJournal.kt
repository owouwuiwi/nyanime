package it.lorelore.nyanime.novel

import android.database.sqlite.SQLiteDatabase

data class NovelChange(val sequence: Long, val record: NovelRecord)

/** One pending row per entity; repeated playback/reading updates never create an event archive. */
internal object NovelJournal {
    private data class Binding(
        val table: String,
        val kind: String,
        val key: String,
        val owner: String,
    )

    private val bindings =
        listOf(
            Binding("books", "book", "id", "NEW.id"),
            Binding("positions", "position", "book_id", "NEW.book_id"),
            Binding("annotations", "annotation", "id", "NEW.book_id"),
            Binding(
                "appearance",
                "appearance",
                "owner",
                "CASE WHEN NEW.owner='global' THEN NULL ELSE NEW.owner END",
            ),
        )

    fun create(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE metadata_changes(sequence INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,record_key TEXT NOT NULL,book_id TEXT,UNIQUE(kind,record_key))",
        )
        for (binding in bindings) {
            for (event in listOf("INSERT", "UPDATE")) {
                val condition = if (event == "UPDATE") "WHEN OLD.payload IS NOT NEW.payload" else ""
                db.execSQL(
                    "CREATE TRIGGER novel_${binding.table}_${event.lowercase()} " +
                        "AFTER $event ON ${binding.table} $condition BEGIN " +
                        "DELETE FROM metadata_changes WHERE kind='${binding.kind}' " +
                        "AND record_key=NEW.${binding.key}; " +
                        "INSERT INTO metadata_changes(kind,record_key,book_id) VALUES('${binding.kind}',NEW.${binding.key},${binding.owner}); END",
                )
            }
        }
        for (event in listOf("INSERT", "UPDATE")) {
            val condition = if (event == "UPDATE") "WHEN OLD.payload IS NOT NEW.payload" else ""
            db.execSQL(
                "CREATE TRIGGER novel_extras_${event.lowercase()} AFTER $event ON portable_extras $condition BEGIN " +
                    "DELETE FROM metadata_changes WHERE kind=NEW.kind AND record_key=NEW.record_key; " +
                    "INSERT INTO metadata_changes(kind,record_key,book_id) VALUES(NEW.kind,NEW.record_key,NEW.book_id); END",
            )
        }
    }

    fun seed(db: SQLiteDatabase) {
        for (binding in bindings) {
            val owner = binding.owner.replace("NEW.", "")
            db.execSQL(
                "INSERT OR IGNORE INTO metadata_changes(kind,record_key,book_id) SELECT '${binding.kind}',${binding.key},$owner FROM ${binding.table}",
            )
        }
        db.execSQL(
            "INSERT OR IGNORE INTO metadata_changes(kind,record_key,book_id) SELECT kind,record_key,book_id FROM portable_extras",
        )
    }

    fun changes(db: SQLiteDatabase, after: Long, limit: Int): List<NovelChange> {
        require(after >= 0 && limit in 1..200)
        val result = mutableListOf<NovelChange>()
        db.rawQuery(
            "SELECT sequence,kind,record_key FROM metadata_changes WHERE sequence>? ORDER BY sequence LIMIT ?",
            arrayOf(after.toString(), limit.toString()),
        )
            .use { rows ->
                while (rows.moveToNext()) {
                    NovelMetadata.read(db, rows.getString(1), rows.getString(2))?.let {
                        result += NovelChange(rows.getLong(0), it)
                    }
                }
            }
        return result
    }

    fun pending(db: SQLiteDatabase, after: Long): Long {
        require(after >= 0)
        return db.rawQuery(
            "SELECT COUNT(*) FROM metadata_changes WHERE sequence>?",
            arrayOf(after.toString()),
        )
            .use {
                it.moveToFirst()
                it.getLong(0)
            }
    }
}
