package it.lorelore.nyanime.novel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Availability is installation-local; portable metadata and cached artwork cannot confer it. */
internal object NovelAvailabilityProbe {
    suspend fun run(context: Context, book: NovelBook, position: NovelPosition): List<String> {
        check(context.packageName == "it.lorelore.nyanime.novel.test")
        val name = "novel_availability_probe.sqlite"
        context.deleteDatabase(name)
        val artworkId = NovelIdentity.local("d".repeat(64))
        val artwork =
            book.copy(
                id = artworkId,
                contentVersion = "d".repeat(64),
                originalFile = null,
                publicationFile = null,
                coverFile = "books/$artworkId/edition/cover.png",
            )
        val origin = NovelOrigin(107, "book-reference")
        val onlineId = NovelIdentity.remote(origin.sourceId, origin.reference)
        val online =
            book.copy(
                id = onlineId,
                format = NovelFormat.ONLINE,
                origin = origin,
                originalFile = null,
                publicationFile = "books/$onlineId/edition/manifest.json",
                coverFile = null,
            )
        NovelStore(context, name).use { store ->
            for (item in listOf(book, artwork, online)) {
                store.put(item)
                store.savePosition(
                    position.copy(bookId = item.id, contentVersion = item.contentVersion),
                )
            }
            store.setFavorite(onlineId, false)
            val before = requireNotNull(store.get(onlineId))
            val sequence =
                store
                    .changes(0, 200)
                    .single { it.record.kind == "book" && it.record.key == onlineId }
                    .sequence
            val privateUpdate =
                before.copy(
                    favorite = true,
                    importedAt = before.importedAt + 1,
                    modifiedAt = before.modifiedAt + 1,
                    publicationFile = "books/$onlineId/online/index.json",
                    coverFile = "books/$onlineId/online/cover.png",
                )
            store.putOnline(privateUpdate)
            val after = requireNotNull(store.get(onlineId))
            check(
                !after.favorite &&
                    after.importedAt == before.importedAt &&
                    after.modifiedAt == before.modifiedAt,
            )
            check(after.publicationFile == privateUpdate.publicationFile)
            check(after.coverFile == privateUpdate.coverFile)
            check(
                store
                    .changes(0, 200)
                    .single { it.record.kind == "book" && it.record.key == onlineId }
                    .sequence == sequence,
            )
            check(store.position(onlineId)?.locatorJson == position.locatorJson)
            store.putOnline(online)
            store.setFavorite(onlineId, true)
            check(store.page(localFilesOnly = true).items.map { it.book.id } == listOf(book.id))
            check(
                store.readingPage(localFilesOnly = true).items.map { it.book.id } == listOf(book.id),
            )
            val journalBefore = store.changes(0, 200)
            val revision = store.changes.value
            store.setOnlineOfflineAvailable(onlineId, true)
            check(store.changes.value == revision + 1)
            store.setOnlineOfflineAvailable(onlineId, true)
            check(store.changes.value == revision + 1)
            check(
                store.page(localFilesOnly = true).items.map { it.book.id }.toSet() ==
                    setOf(book.id, onlineId),
            )
            store.putOnline(
                requireNotNull(store.get(onlineId))
                    .copy(coverFile = "books/$onlineId/online/refreshed.png"),
            )
            check(store.page(localFilesOnly = true).total == 2)
            check(store.changes(0, 200) == journalBefore)
            val archive = store.archive()
            check("offline_ready" !in archive.encode())
            context.deleteDatabase("novel_availability_restore.sqlite")
            store.setOnlineOfflineAvailable(onlineId, false)
            check(store.page(localFilesOnly = true).total == 1)
            NovelStore(context, "novel_availability_restore.sqlite").use { restored ->
                restored.restore(archive)
                check(restored.page().total == 3 && restored.page(localFilesOnly = true).total == 0)
                check(restored.readingPage(localFilesOnly = true).total == 0)
            }
            context.deleteDatabase("novel_availability_restore.sqlite")
        }
        // The rest of schema2 is identical. Recreate its exact pre-availability documents table.
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
            .use { old ->
                old.beginTransaction()
                try {
                    old.execSQL("ALTER TABLE documents RENAME TO documents_v3")
                    old.execSQL(
                        "CREATE TABLE documents(book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,content_version TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(book_id,content_version))",
                    )
                    old.execSQL(
                        "INSERT INTO documents(book_id,content_version,payload) SELECT book_id,content_version,payload FROM documents_v3",
                    )
                    old.execSQL("DROP TABLE documents_v3")
                    old.version = 2
                    old.setTransactionSuccessful()
                } finally {
                    old.endTransaction()
                }
            }
        NovelStore(context, name).use { migrated ->
            check(migrated.page().total == 3 && migrated.readingPage().total == 3)
            check(migrated.page(localFilesOnly = true).items.single().book.id == book.id)
            check(migrated.readingPage(localFilesOnly = true).items.single().book.id == book.id)
            check(migrated.get(onlineId)?.publicationFile == online.publicationFile)
            check(migrated.position(book.id) == position)
        }
        context.deleteDatabase(name)
        val root = File(context.cacheDir, "novel-online-index-probe")
        check(!root.exists() || root.deleteRecursively())
        try {
            val indexes = NovelOnlineIndexes(root)
            val document =
                NovelOnlineDocument(
                    bookId = onlineId,
                    origin = origin,
                    chapters = listOf(NovelOnlineChapter("a", "One")),
                    updatedAt = 0,
                )
            val path = indexes.write(document)
            check(indexes.read(origin) == document)
            val replacement =
                document.copy(chapters = document.chapters + NovelOnlineChapter("b", "Two"))
            check(indexes.write(replacement) == path)
            check(indexes.read(origin) == replacement)
        } finally {
            check(!root.exists() || root.deleteRecursively())
        }
        return listOf(
            "offline_filter_excludes_artwork_and_online_manifest",
            "portable_restore_cannot_claim_downloaded_resources",
            "schema_two_availability_migration_preserves_positions_and_document_ownership",
            "source_metadata_refresh_preserves_library_intent_and_does_not_journal_private_paths",
            "online_chapter_index_replacement_is_atomic_on_android",
            "durable_offline_availability_is_local_survives_metadata_refresh_and_updates_without_spurious_revisions",
        )
    }
}
