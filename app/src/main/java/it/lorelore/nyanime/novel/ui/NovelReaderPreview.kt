package it.lorelore.nyanime.novel.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import eu.kanade.tachiyomi.network.NetworkHelper
import it.lorelore.nyanime.novel.NovelBook
import it.lorelore.nyanime.novel.NovelFormat
import it.lorelore.nyanime.novel.NovelIdentity
import it.lorelore.nyanime.novel.NovelLibrary
import it.lorelore.nyanime.novel.NovelOnlineCache
import it.lorelore.nyanime.novel.NovelOnlineChapter
import it.lorelore.nyanime.novel.NovelOnlineDocument
import it.lorelore.nyanime.novel.NovelOnlineIdentity
import it.lorelore.nyanime.novel.NovelOnlineIndexes
import it.lorelore.nyanime.novel.NovelOnlineOpener
import it.lorelore.nyanime.novel.NovelOrigin
import it.lorelore.nyanime.novel.NovelStore
import it.lorelore.nyanime.novel.source.NovelSourceImages
import it.lorelore.nyanime.novel.source.NovelSourceRegistration
import it.lorelore.nyanime.novel.source.NovelSourceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A real reader session with disposable databases; no preview enters the shared library or Cloud.
 */
internal object NovelReaderPreview {
    internal class Session(
        val token: String,
        val bookId: String,
        val library: NovelLibrary,
        private val app: Context,
        private val root: File,
        private val cache: NovelOnlineCache,
        private val lifetime: NovelPreviewLifetime,
        private val databases: List<String>,
    ) {
        val claimed = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        private val destroyed = AtomicBoolean(false)

        suspend fun dispose() =
            withContext(NonCancellable + Dispatchers.IO) {
                if (!destroyed.compareAndSet(false, true)) return@withContext
                // Writer and publication releases must finish before their databases/files
                // disappear.
                lifetime.stop()
                library.drainSession()
                lifetime.drain()
                cache.close()
                library.store.close()
                databases.forEach { app.deleteDatabase(it) }
                val parent = File(app.cacheDir, "novel-previews-v1").canonicalFile
                val directory = root.canonicalFile
                check(directory.parentFile == parent && directory.name == token)
                check(!directory.exists() || directory.deleteRecursively())
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val creation = Mutex()
    private val gate = Any()
    private val sessions = mutableMapOf<String, Session>()
    private var staleFilesPruned = false

    fun find(token: String): Session? =
        synchronized(gate) {
            sessions[token]?.takeUnless { it.closing.get() }
        }

    fun claim(session: Session) {
        check(find(session.token) === session && session.claimed.compareAndSet(false, true)) {
            "This preview reader has already been opened"
        }
    }

    /**
     * Called only when the retained reader ViewModel is cleared, never on a configuration change.
     */
    fun release(session: Session, pending: List<Job>) {
        if (!session.closing.compareAndSet(false, true)) return
        synchronized(gate) { sessions.remove(session.token, session) }
        scope.launch {
            pending.forEach { it.cancel() }
            pending.forEach { it.join() }
            runCatching { session.dispose() }
                .onFailure {
                    // Keep incomplete cleanup local; a storage error must never crash the real app.
                    Log.w("NovelPreview", "Preview storage could not be fully released")
                }
        }
    }

    suspend fun open(
        context: Context,
        expected: NovelSourceRegistration,
        reference: String,
        resolveDraft: suspend () -> NovelSourceRegistration,
    ) = creation.withLock {
        val app = context.applicationContext
        NovelOrigin(expected.source.id, reference)
        check(synchronized(gate) { sessions.size < 2 }) { "Close an existing preview first" }
        var prepared: Session? = null
        var launched = false
        try {
            withContext(Dispatchers.IO) {
                pruneStale(app)
                withTimeout(45_000) {
                    fun checked(value: NovelSourceRegistration): NovelSourceRegistration {
                        check(
                            value.source.id == expected.source.id &&
                                value.providerId == expected.providerId &&
                                value.packageName == expected.packageName &&
                                value.revision == expected.revision,
                        ) {
                            "The preview definition or its authorization has changed"
                        }
                        return value
                    }
                    val current = checked(resolveDraft())
                    val details = current.source.details(reference)
                    require(details.entry.reference == reference)
                    val chapters = current.source.chapters(reference)
                    require(chapters.size in 1..10_000)
                    checked(resolveDraft())
                    val origin = NovelOrigin(current.source.id, reference)
                    val now = System.currentTimeMillis()
                    val document =
                        NovelOnlineDocument(
                            bookId = NovelIdentity.remote(origin.sourceId, reference),
                            origin = origin,
                            chapters =
                            chapters.map {
                                NovelOnlineChapter(
                                    it.reference,
                                    it.title,
                                    it.number,
                                    it.group,
                                    it.publishedAt,
                                )
                            },
                            language = details.language,
                            updatedAt = now,
                        )
                    val token = UUID.randomUUID().toString()
                    val root = File(File(app.cacheDir, "novel-previews-v1"), token)
                    check(root.mkdirs())
                    val prefix = "novel_preview_" + token.replace("-", "")
                    val names = listOf(prefix + ".db", prefix + "_search.db", prefix + "_cache.db")
                    val store = NovelStore(app, names[0])
                    val cache =
                        NovelOnlineCache(
                            app,
                            File(root, "content"),
                            names[2],
                            byteLimit = 32L * 1024 * 1024,
                            entryLimit = 512,
                        )
                    val network = Injekt.get<NetworkHelper>()
                    val images = NovelSourceImages(network)
                    val runtime =
                        NovelSourceRuntime(current, { checked(resolveDraft()) }, images::read)
                    val book =
                        NovelBook(
                            id = document.bookId,
                            title = details.entry.title,
                            authors = details.entry.authors,
                            description = details.description,
                            format = NovelFormat.ONLINE,
                            contentVersion = NovelOnlineIdentity.edition(origin),
                            origin = origin,
                            favorite = false,
                            importedAt = now,
                            modifiedAt = now,
                        )
                    val lifetime = NovelPreviewLifetime()
                    val library =
                        NovelLibrary(
                            app,
                            store,
                            NovelOnlineOpener {
                                lifetime.run {
                                    NovelPreviewAccess(cache.open(it, document, runtime), lifetime)
                                }
                            },
                            root = root,
                            searchDatabaseName = names[1],
                        )
                    val session =
                        Session(token, book.id, library, app, root, cache, lifetime, names)
                    prepared = session
                    try {
                        val path = NovelOnlineIndexes(root).write(document)
                        store.putOnline(book.copy(publicationFile = path))
                        NovelStore.shared(app).appearance(null)?.let {
                            store.saveAppearance(null, it)
                        }
                    } catch (error: Throwable) {
                        session.dispose()
                        throw error
                    }
                }
            }
            val session = requireNotNull(prepared)
            synchronized(gate) { check(sessions.put(session.token, session) == null) }
            withContext(Dispatchers.Main.immediate) {
                context.startActivity(
                    NovelReaderActivity.previewIntent(context, session.bookId, session.token)
                        .apply {
                            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                )
                launched = true
            }
        } catch (error: Throwable) {
            prepared
                ?.takeUnless { launched }
                ?.let { session ->
                    synchronized(gate) { sessions.remove(session.token, session) }
                    try {
                        session.dispose()
                    } catch (cleanup: Exception) {
                        error.addSuppressed(cleanup)
                    }
                }
            throw error
        }
    }

    /** Only generated preview names from an earlier process, never books or ordinary caches. */
    private fun pruneStale(app: Context) {
        if (staleFilesPruned) return
        check(synchronized(gate) { sessions.isEmpty() })
        val parent = File(app.cacheDir, "novel-previews-v1").canonicalFile
        parent.listFiles().orEmpty().forEach { file ->
            if (
                runCatching { UUID.fromString(file.name).toString() == file.name }
                    .getOrDefault(false)
            ) {
                check(file.canonicalFile.parentFile == parent)
                check(file.deleteRecursively())
            }
        }
        app.databaseList()
            .filter {
                it.matches(Regex("novel_preview_[a-f0-9]{32}(_search|_cache)?\\.db"))
            }
            .forEach { check(app.deleteDatabase(it)) }
        staleFilesPruned = true
    }
}
