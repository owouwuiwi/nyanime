package it.lorelore.nyanime.novel

import android.content.ContentValues
import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Isolated databases only: restore/migration must never touch the main app's library. */
internal object NovelMetadataProbe {
    suspend fun run(
        context: Context,
        library: NovelLibrary,
        book: NovelBook,
        point: NovelPosition,
        note: NovelAnnotation,
    ): List<String> {
        check(context.packageName == "it.lorelore.nyanime.novel.test")
        val passed = mutableListOf<String>()
        library.store.saveAppearance(book.id, NovelAppearance(fontSize = 1.25))
        val snapshot = library.store.archive()
        check(
            snapshot.records
                .first { it.kind == "book" }
                .payload
                .jsonObject
                .keys
                .none {
                    it in NovelRecord.PRIVATE_BOOK_FIELDS
                },
        )
        val base = snapshot.records.first { it.kind == "book" }
        val future =
            base.copy(
                payload =
                JsonObject(base.payload.jsonObject + ("futureField" to JsonPrimitive(true))),
            )
        val extension =
            NovelRecord(
                "future-feature",
                "key",
                book.id,
                300,
                JsonObject(mapOf("future" to JsonPrimitive("retained"))),
            )
        val archive =
            NovelArchive.decode(
                NovelArchive(records = snapshot.records.filter { it != base } + future + extension)
                    .encode(),
            )
        context.deleteDatabase("novel_restore_probe.sqlite")
        val restored = NovelStore(context, "novel_restore_probe.sqlite")
        restored.restore(archive)
        check(restored.get(book.id)?.publicationFile == null)
        check(restored.position(book.id) == point && restored.annotations(book.id).single() == note)
        check(restored.appearance(book.id)?.fontSize == 1.25)
        check(
            restored
                .archive()
                .records
                .first { it.kind == "book" }
                .payload
                .jsonObject["futureField"] == JsonPrimitive(true),
        )
        check(restored.archive().records.contains(extension))
        passed += "portable_restore_keeps_notes_positions_and_future_fields_without_local_paths"

        // A newer portable record may update metadata, but cannot replace this phone's files.
        restored.put(book)
        val incomingBook = book.copy(title = "Remote metadata", modifiedAt = book.modifiedAt + 10)
        val incoming =
            NovelRecord(
                "book",
                book.id,
                book.id,
                incomingBook.modifiedAt,
                NovelJson.encodeToJsonElement(incomingBook),
            )
        restored.restore(NovelArchive(records = listOf(incoming)))
        check(
            restored.get(book.id)?.let {
                it.title == incomingBook.title && it.publicationFile == book.publicationFile
            } == true,
        )
        check(restored.annotations(book.id).single() == note)
        restored.saveAppearance(book.id, null)
        restored.restore(snapshot)
        check(restored.appearance(book.id) == null)
        passed += "metadata_restore_preserves_owned_documents_and_newer_preference_tombstones"

        // Seed the exact old schema, then open the new helper. SQLite upgrades transactionally.
        context.deleteDatabase("novel_migration_probe.sqlite")
        val old =
            context.openOrCreateDatabase("novel_migration_probe.sqlite", Context.MODE_PRIVATE, null)
        old.execSQL(
            "CREATE TABLE books(id TEXT PRIMARY KEY,sort_title TEXT NOT NULL,favorite INTEGER NOT NULL,payload TEXT NOT NULL)",
        )
        old.execSQL("CREATE TABLE positions(book_id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        old.execSQL(
            "CREATE TABLE annotations(id TEXT PRIMARY KEY,book_id TEXT NOT NULL,modified_at INTEGER NOT NULL,deleted INTEGER NOT NULL,payload TEXT NOT NULL)",
        )
        old.execSQL("CREATE TABLE appearance(owner TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        val oldPayload =
            JsonObject(
                NovelJson.encodeToJsonElement(book).jsonObject +
                    mapOf(
                        "originalFile" to JsonPrimitive(book.originalFile),
                        "publicationFile" to JsonPrimitive(book.publicationFile),
                        "coverFile" to JsonPrimitive(book.coverFile),
                        "futureField" to JsonPrimitive(true),
                    ),
            )
        old.insertOrThrow(
            "books",
            null,
            ContentValues().apply {
                put("id", book.id)
                put("sort_title", book.title)
                put("favorite", 1)
                put("payload", oldPayload.toString())
            },
        )
        old.insertOrThrow(
            "positions",
            null,
            ContentValues().apply {
                put("book_id", book.id)
                put("payload", NovelJson.encodeToString(point))
            },
        )
        old.insertOrThrow(
            "annotations",
            null,
            ContentValues().apply {
                put("id", note.id)
                put("book_id", book.id)
                put("modified_at", note.modifiedAt)
                put("deleted", 0)
                put("payload", NovelJson.encodeToString(note))
            },
        )
        old.version = 1
        old.close()
        val migrated = NovelStore(context, "novel_migration_probe.sqlite")
        check(migrated.get(book.id)?.publicationFile == book.publicationFile)
        check(migrated.position(book.id) == point && migrated.annotations(book.id).single() == note)
        check(migrated.page().items.single().book.publicationFile == book.publicationFile)
        check(migrated.page(localFilesOnly = true).items.single().book.id == book.id)
        val migratedRecord = migrated.archive().records.first { it.kind == "book" }
        check(migratedRecord.payload.jsonObject.keys.none { it in NovelRecord.PRIVATE_BOOK_FIELDS })
        check(migratedRecord.payload.jsonObject["futureField"] == JsonPrimitive(true))
        check(library.store.position(book.id) == point)
        passed += "schema_one_migration_preserves_files_progress_notes_and_unknown_metadata"
        context.deleteDatabase("novel_journal_probe.sqlite")
        NovelStore(context, "novel_journal_probe.sqlite").use { journal ->
            journal.put(book)
            val initial = journal.changes(0).single().sequence
            repeat(100) { journal.savePosition(point.copy(updatedAt = 500L + it)) }
            val changes = journal.changes(initial)
            check(changes.size == 1 && changes.single().record.kind == "position")
            check(changes.single().record.updatedAt == 599L && journal.pending(0) == 2L)
            passed += "journal_compacts_one_hundred_position_writes_into_one_pending_entity"
            val before = journal.record("position", book.id)
            journal.savePosition(point.copy(updatedAt = 700))
            val remote = point.copy(updatedAt = 800, totalProgression = .75)
            val received =
                NovelRecord(
                    "position",
                    book.id,
                    book.id,
                    800,
                    NovelJson.parseToJsonElement(NovelJson.encodeToString(remote)),
                )
            check(
                !journal.project(received, before) && journal.position(book.id)?.updatedAt == 700L,
            )
            check(journal.project(received, journal.record("position", book.id)))
            check(
                journal.position(book.id) == remote &&
                    journal.get(book.id)?.publicationFile == book.publicationFile,
            )
            val wrongEdition = remote.copy(contentVersion = "unavailable-edition", updatedAt = 900)
            check(
                !journal.project(
                    NovelRecord(
                        "position",
                        book.id,
                        book.id,
                        900,
                        NovelJson.parseToJsonElement(NovelJson.encodeToString(wrongEdition)),
                    ),
                    journal.record("position", book.id),
                ),
            )
            check(journal.position(book.id) == remote)
            passed += "causal_compare_and_apply_keeps_offline_edits_and_rejects_other_editions"
        }
        context.deleteDatabase("novel_clock_probe.sqlite")
        NovelStore(context, "novel_clock_probe.sqlite").use { clock ->
            clock.put(book.copy(modifiedAt = Long.MAX_VALUE))
            clock.setFavorite(book.id, false)
            clock.setFavorite(book.id, true)
            check(clock.get(book.id)?.favorite == true)
            check(clock.get(book.id)?.modifiedAt == Long.MAX_VALUE)
            clock.restore(
                NovelArchive(
                    records =
                    listOf(
                        NovelRecord(
                            "appearance",
                            "global",
                            updatedAt = Long.MAX_VALUE,
                            payload = NovelJson.encodeToJsonElement(NovelAppearance()),
                        ),
                    ),
                ),
            )
            clock.saveAppearance(null, NovelAppearance(theme = NovelTheme.DARK))
            check(clock.appearance(null)?.theme == NovelTheme.DARK)
            check(clock.record("appearance", "global")?.updatedAt == Long.MAX_VALUE)
            passed += "extreme_restored_timestamps_keep_favorite_and_preferences_editable"
        }
        context.deleteDatabase("novel_clock_probe.sqlite")
        context.deleteDatabase("novel_paging_probe.sqlite")
        NovelStore(context, "novel_paging_probe.sqlite").use { shelf ->
            val books =
                (0..120).map { index ->
                    val hash = index.toString(16).padStart(64, '0')
                    book.copy(
                        id = NovelIdentity.local(hash),
                        title = "Book " + index.toString().padStart(3, '0'),
                        contentVersion = hash,
                        originalFile = null,
                        publicationFile = null,
                        coverFile = null,
                    )
                }
            for ((index, item) in books.withIndex()) {
                shelf.put(item)
                if (index % 30 == 0) {
                    shelf.savePosition(
                        point.copy(
                            bookId = item.id,
                            contentVersion = item.contentVersion,
                            totalProgression =
                            if (index == 60) {
                                1.0
                            } else if (index == 120) {
                                null
                            } else {
                                .5
                            },
                        ),
                    )
                }
            }
            val found = mutableListOf<String>()
            var cursor: NovelStore.Cursor? = null
            do {
                val page = shelf.page(after = cursor, size = 40)
                check(page.total == 121 && page.items.size <= 40)
                found += page.items.map { it.book.id }
                cursor = page.next
            } while (cursor != null)
            check(found == books.map { it.id } && found.distinct().size == 121)
            check(shelf.page("Book 1").total == 21)
            val reading = shelf.page(inProgressOnly = true)
            check(reading.total == 4 && reading.items.any { it.book.id == books.last().id })
            check(reading.items.none { it.position?.totalProgression == 1.0 })
            shelf.setFavorite(books.first().id, false)
            check(shelf.page().total == 120 && shelf.page(inProgressOnly = true).total == 3)
            check(shelf.page(localFilesOnly = true).total == 0)
            shelf.put(book)
            val downloaded = shelf.page(localFilesOnly = true)
            check(downloaded.total == 1 && downloaded.items.single().book.id == book.id)
            check(downloaded.items.single().book.publicationFile == book.publicationFile)
            passed +=
                "shelf_paging_and_continue_include_late_books_without_fabricated_chapter_counts"
            val history = mutableListOf<String>()
            var historyCursor: NovelStore.ReadingCursor? = null
            do {
                val page = shelf.readingPage(after = historyCursor, size = 2)
                check(page.total == 5 && page.items.size <= 2)
                history += page.items.map { it.book.id }
                historyCursor = page.next
            } while (historyCursor != null)
            check(
                history.toSet() ==
                    books.filterIndexed { index, _ -> index % 30 == 0 }.map { it.id }.toSet(),
            )
            check(history.size == 5 && history.distinct().size == 5)
            check(books.first().id in history && books[60].id in history)
            check(shelf.readingPage("Book 1").total == 1)
            check(shelf.readingPage("%").total == 0 && shelf.readingPage("_").total == 0)
            check(shelf.recent().size == 3)
            shelf.savePosition(point)
            check(shelf.recent(localFilesOnly = true).single().book.id == book.id)
            passed +=
                "history_pages_include_completed_and_removed_favorites_with_literal_queries_and_local_resume"
        }
        context.deleteDatabase("novel_paging_probe.sqlite")
        passed += NovelAvailabilityProbe.run(context, book, point)
        return passed
    }
}
