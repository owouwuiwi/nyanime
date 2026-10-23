package it.lorelore.nyanime.novel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/** Disposable reader storage is tested separately from the ordinary book/search databases. */
internal object NovelSessionProbe {
    suspend fun run(context: Context, ordinary: NovelLibrary): List<String> {
        check(context.packageName == "it.lorelore.nyanime.novel.test")
        val metadataName = "novel_session_probe.db"
        val searchName = "novel_session_probe_search.db"
        val root = File(context.cacheDir, "novel-session-probe")
        check(!root.exists() || root.deleteRecursively())
        context.deleteDatabase(metadataName)
        context.deleteDatabase(searchName)
        val store = NovelStore(context, metadataName)
        val library = NovelLibrary(context, store, root = root, searchDatabaseName = searchName)
        var opened: NovelLibrary.Opened? = null
        val passed = mutableListOf<String>()
        try {
            val book =
                library.importer.import(
                    {
                        ByteArrayInputStream(
                            "SelenografiaProva isolata.\n".repeat(20).toByteArray(),
                        )
                    },
                    NovelFormat.TXT,
                    "Session fixture.txt",
                    partTitle = { "Part $it" },
                )
            check(ordinary.store.get(book.id) == null)
            check(
                library
                    .ownedFile(requireNotNull(book.publicationFile))
                    .canonicalPath
                    .startsWith(root.canonicalPath),
            )
            check(!File(ordinary.root, requireNotNull(book.publicationFile)).exists())
            passed += "disposable_reader_metadata_and_original_files_are_isolated"
            val baseline = ordinary.search.cacheState()
            val current = library.open(book.id).also { opened = it }
            library.search.index(book, current.publication) {}
            check(library.search.find(book, "SelenografiaProva").isNotEmpty())
            check(ordinary.search.find(book, "SelenografiaProva").isEmpty())
            check(ordinary.search.cacheState() == baseline)
            passed += "disposable_reader_search_does_not_modify_shared_index"
            library.commit { store.setFavorite(book.id, false) }
            library.release(current, null)
            opened = null
            library.drainSession()
            check(store.get(book.id)?.favorite == false)
            check(ordinary.store.get(book.id) == null)
            passed += "disposable_reader_drains_accepted_writes_before_storage_cleanup"
            return passed
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                opened?.let { library.release(it, null) }
                library.drainSession()
                store.close()
                context.deleteDatabase(metadataName)
                context.deleteDatabase(searchName)
                check(!root.exists() || root.deleteRecursively())
            }
        }
    }
}
