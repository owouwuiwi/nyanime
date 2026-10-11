package it.lorelore.nyanime.novel

import android.content.Context
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Metadata only; emergency backup does not initialize a reader, publication or network client. */
internal object NovelBackup {
    fun validate(text: String): NovelArchive = NovelArchive.decode(text)

    suspend fun create(context: Context, options: BackupOptions): String? =
        withContext(Dispatchers.IO) {
            if (
                (!options.libraryEntries && !options.appSettings) ||
                !context.getDatabasePath(NovelStore.DATABASE_NAME).isFile
            ) {
                return@withContext null
            }
            NovelStore.shared(context).let { store ->
                if (!options.libraryEntries) {
                    return@let store.record("appearance", "global")?.let {
                        NovelArchive(records = listOf(it)).encode()
                    }
                }
                val snapshot =
                    store.archive(
                        includePositions = options.chapters,
                        includeAppearance = options.appSettings,
                    )
                val selected = select(snapshot, options)
                if (selected.isEmpty()) null else NovelArchive(records = selected).encode()
            }
        }

    fun select(snapshot: NovelArchive, options: BackupOptions): List<NovelRecord> {
        val books =
            snapshot.records
                .filter { it.kind == "book" }
                .filter {
                    options.readEntries ||
                        it.payload.jsonObject["favorite"]?.jsonPrimitive?.booleanOrNull != false
                }
                .mapTo(hashSetOf()) { it.key }
        return snapshot.records.filter { record ->
            when {
                record.kind == "appearance" ->
                    options.appSettings &&
                        (record.bookId == null || options.libraryEntries && record.bookId in books)
                record.kind == "position" && !options.chapters -> false
                else -> options.libraryEntries && (record.bookId == null || record.bookId in books)
            }
        }
    }

    suspend fun restore(context: Context, text: String, options: RestoreOptions) =
        withContext(Dispatchers.IO) {
            val selected =
                validate(text).records.filter { record ->
                    if (record.kind == "appearance") {
                        options.appSettings && (options.libraryEntries || record.bookId == null)
                    } else {
                        options.libraryEntries
                    }
                }
            if (selected.isNotEmpty()) {
                NovelStore.shared(context).restore(NovelArchive(records = selected))
            }
        }
}
