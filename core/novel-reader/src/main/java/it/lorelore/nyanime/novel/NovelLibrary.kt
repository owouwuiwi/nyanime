package it.lorelore.nyanime.novel

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.r2.shared.publication.Publication
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** One lazily created entry point shared by shelf, import, reader and metadata backup. */
class NovelLibrary(
    context: Context,
    val store: NovelStore = NovelStore.shared(context),
    private val onlineOpener: NovelOnlineOpener? = null,
    val root: File = File(context.applicationContext.filesDir, "novels"),
    searchDatabaseName: String = "novel_search_v1.db",
) {
    private val app = context.applicationContext
    val publications by lazy { NovelPublications(app) }
    val importer by lazy { NovelImporter(root, store, publications) }
    private val searchInstance = lazy { NovelSearch(app, databaseName = searchDatabaseName) }
    val search by searchInstance
    private val _writeFailures = MutableStateFlow<Throwable?>(null)
    val writeFailures = _writeFailures.asStateFlow()
    private val tasks = NovelSessionTasks { _writeFailures.tryEmit(it) }

    fun acknowledgeWriteFailure(error: Throwable) {
        _writeFailures.compareAndSet(error, null)
    }

    fun commit(operation: suspend () -> Unit) {
        tasks.commit(operation)
    }

    /** A cancelled index finishes its resource finally-blocks before its publication is closed. */
    fun release(opened: Opened, pendingIndex: Job?) {
        pendingIndex?.cancel()
        tasks.release {
            pendingIndex?.join()
            opened.close()
        }
    }

    /**
     * Disposable reader owners first cancel/join their consumers, then await every accepted write
     * and publication release. The ordinary shared library stays open for the application lifetime.
     * The store remains owned by the caller.
     */
    suspend fun drainSession() {
        tasks.drain()
        if (searchInstance.isInitialized()) search.close()
    }

    suspend fun open(bookId: String): Opened =
        acquireNovelResource(
            acquire = {
                var book =
                    store.get(bookId) ?: throw FileNotFoundException("Book metadata is missing")
                val online =
                    if (book.format == NovelFormat.ONLINE) {
                        try {
                            onlineOpener?.open(book)
                                ?: throw IOException("Novel source is unavailable")
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            throw OnlineDocumentException(error)
                        }
                    } else {
                        null
                    }
                val publication =
                    if (online != null) {
                        book =
                            try {
                                store.get(bookId) ?: book
                            } catch (error: Throwable) {
                                online.close()
                                throw error
                            }
                        NovelOnlinePublication.open(book, online)
                    } else {
                        val file =
                            book.publicationFile?.let(::ownedFile)?.takeIf { it.isFile }
                                ?: throw MissingDocumentException(book)
                        publications.open(file)
                    }
                try {
                    Opened(
                        book,
                        publication,
                        store.position(bookId)?.takeIf { it.contentVersion == book.contentVersion },
                        online,
                    )
                } catch (error: Throwable) {
                    publication.close()
                    throw error
                }
            },
            release = Opened::close,
        )

    fun ownedFile(path: String): File {
        require(!path.startsWith('/') && !path.contains('\\'))
        val parent = root.canonicalFile
        val file = File(parent, path).canonicalFile
        require(file.toPath().startsWith(parent.toPath()) && file != parent)
        return file
    }

    class MissingDocumentException(val book: NovelBook) :
        IOException("Relink the book's original file to continue reading")

    class OnlineDocumentException(cause: Throwable) :
        IOException("Novel source is unavailable", cause)

    data class Opened(
        val book: NovelBook,
        val publication: Publication,
        val position: NovelPosition?,
        val online: NovelOnlineAccess? = null,
    ) : AutoCloseable {
        override fun close() = publication.close()
    }
}
