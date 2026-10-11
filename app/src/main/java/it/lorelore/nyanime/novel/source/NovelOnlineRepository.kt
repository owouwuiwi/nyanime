package it.lorelore.nyanime.novel.source

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import it.lorelore.nyanime.novel.NovelBook
import it.lorelore.nyanime.novel.NovelDownloadAccess
import it.lorelore.nyanime.novel.NovelDownloadStore
import it.lorelore.nyanime.novel.NovelFormat
import it.lorelore.nyanime.novel.NovelIdentity
import it.lorelore.nyanime.novel.NovelOnlineAccess
import it.lorelore.nyanime.novel.NovelOnlineBody
import it.lorelore.nyanime.novel.NovelOnlineCache
import it.lorelore.nyanime.novel.NovelOnlineChapter
import it.lorelore.nyanime.novel.NovelOnlineDocument
import it.lorelore.nyanime.novel.NovelOnlineFetcher
import it.lorelore.nyanime.novel.NovelOnlineIdentity
import it.lorelore.nyanime.novel.NovelOnlineImage
import it.lorelore.nyanime.novel.NovelOnlineIndexes
import it.lorelore.nyanime.novel.NovelOrigin
import it.lorelore.nyanime.novel.NovelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Optional online bridge. Local EPUB/TXT never instantiate a provider, HTTP client or cache. */
internal class NovelOnlineRepository(
    context: Context,
    private val sources: NovelSources,
    private val network: NetworkHelper,
    private val downloads: NovelDownloadStore,
    private val offlineOnly: () -> Boolean = { false },
) {
    private val app = context.applicationContext
    private val store by lazy { NovelStore.shared(app) }
    private val indexes = NovelOnlineIndexes(File(app.filesDir, "novels"))
    private val cache by lazy { NovelOnlineCache(app) }
    private val images by lazy { NovelSourceImages(network) }
    private val refreshes = NovelPreparationLocks()
    private val coverWrites = Mutex()
    private val artwork by lazy { NovelArtworkCache(File(app.cacheDir, "novel-covers-v1")) }
    private val coverLoads = Array(8) { Mutex() }
    private val coverPermits = Semaphore(2)
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warming = ConcurrentHashMap<String, Job>()

    suspend fun document(book: NovelBook): NovelOnlineDocument? =
        indexes.read(requireNotNull(book.origin)) ?: downloads.document(book)

    suspend fun cover(sourceId: Long, reference: String, bookReference: String): NovelOnlineImage =
        withContext(Dispatchers.IO) {
            val id = NovelIdentity.remote(sourceId, bookReference)
            val root = File(app.filesDir, "novels").canonicalFile
            val prefix = "cover-" + NovelOnlineIdentity.digest(reference) + "-"
            val current = store.get(id)
            val existing = current?.coverFile?.let { File(root, it).canonicalFile }
            if (
                existing != null &&
                existing.toPath().startsWith(root.toPath()) &&
                existing.name.startsWith(prefix) &&
                existing.length() in 1..8L * 1024 * 1024
            ) {
                val bytes = existing.readBytes()
                val hash = existing.name.removePrefix(prefix).removeSuffix(".image")
                if (hash == NovelOnlineIdentity.digest(bytes)) {
                    return@withContext checkedNovelImage(bytes)
                }
            }
            val key = NovelArtworkCache.key(sourceId, bookReference, reference)
            val value =
                cachedArtwork(key)
                    ?: coverLoads[(key.hashCode() and Int.MAX_VALUE) % coverLoads.size].withLock {
                        cachedArtwork(key)
                            ?: coverPermits.withPermit {
                                val registration = sources.resolve(sourceId)
                                NovelSourceRuntime(
                                    registration,
                                    { sources.resolve(sourceId) },
                                    ::image,
                                )
                                    .image(reference)
                                    .also { loaded ->
                                        optionalArtwork { artwork.write(key, loaded.bytes) }
                                    }
                            }
                    }
            optionalArtwork { persistCover(sourceId, reference, bookReference, value) }
            value
        }

    private suspend fun persistCover(
        sourceId: Long,
        reference: String,
        bookReference: String,
        value: NovelOnlineImage,
    ) {
        val id = NovelIdentity.remote(sourceId, bookReference)
        val origin = NovelOrigin(sourceId, bookReference)
        val root = File(app.filesDir, "novels").canonicalFile
        val prefix = "cover-" + NovelOnlineIdentity.digest(reference) + "-"
        coverWrites.withLock {
            val book = store.get(id)
            if (book?.origin == origin && indexes.read(origin)?.coverReference == reference) {
                val directory = File(root, "books/$id/online").canonicalFile
                require(directory.toPath().startsWith(root.toPath()))
                check(directory.isDirectory || directory.mkdirs())
                val file =
                    File(directory, prefix + NovelOnlineIdentity.digest(value.bytes) + ".image")
                if (!file.exists()) {
                    val pending = File(directory, UUID.randomUUID().toString() + ".pending")
                    try {
                        pending.outputStream().use {
                            it.write(value.bytes)
                            it.fd.sync()
                        }
                        Files.move(
                            pending.toPath(),
                            file.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } finally {
                        if (pending.exists()) check(pending.delete())
                    }
                }
                if (indexes.read(origin)?.coverReference != reference) return@withLock
                store.putOnline(
                    book.copy(coverFile = file.relativeTo(root).invariantSeparatorsPath),
                )
                val old = book.coverFile?.let { File(root, it).canonicalFile }
                if (
                    old != null &&
                    old != file &&
                    old.parentFile == directory &&
                    old.name.matches(Regex("cover-[a-f0-9]{64}-[a-f0-9]{64}\\.image"))
                ) {
                    old.delete()
                }
            }
        }
    }

    private suspend fun cachedArtwork(key: String): NovelOnlineImage? = optionalArtwork {
        artwork.read(key)?.let(::checkedNovelImage)
    }

    private suspend fun <T> optionalArtwork(operation: suspend () -> T): T? =
        try {
            operation()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** An optional image failure cannot delay metadata or chapter access. */
    private fun warmCover(sourceId: Long, reference: String, bookReference: String) {
        val key = NovelArtworkCache.key(sourceId, bookReference, reference)
        val job =
            artworkScope.launch(start = CoroutineStart.LAZY) {
                try {
                    cover(sourceId, reference, bookReference)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // The next explicit image request can retry; no fake book error or busy
                    // spinner.
                }
            }
        if (warming.putIfAbsent(key, job) == null) {
            job.invokeOnCompletion { warming.remove(key, job) }
            job.start()
        } else {
            job.cancel()
        }
    }

    suspend fun refresh(
        sourceId: Long,
        reference: String,
        forceRefresh: Boolean = false,
        onDetails: (it.lorelore.nyanime.source.novel.NovelDetails) -> Unit = {},
        onPreparation: (NovelPreparationProgress) -> Unit = {},
    ): NovelBook =
        withContext(Dispatchers.IO) {
            if (offlineOnly()) {
                return@withContext store.get(NovelIdentity.remote(sourceId, reference))
                    ?: throw IOException("Novel metadata is not on this device")
            }
            refreshes.withKey(NovelIdentity.remote(sourceId, reference)) {
                val origin = NovelOrigin(sourceId, reference)
                val initial = requestNovelMetadata(sourceId, reference, sources::resolve, onDetails)
                val registration = initial.registration
                val details = initial.details
                val source = registration.source
                val chapters =
                    if (source is NovelChapterPreparation) {
                        coroutineScope {
                            val observer = launch {
                                source.observePreparation(reference).collect(onPreparation)
                            }
                            try {
                                source.preparedChapters(reference, forceRefresh)
                            } finally {
                                observer.cancelAndJoin()
                            }
                        }
                    } else {
                        requireNotNull(initial.ordinaryChapters)
                    }
                require(chapters.size in 1..10_000)
                withTimeout(45_000) {
                    val current = sources.resolve(sourceId)
                    check(
                        current.packageName == registration.packageName &&
                            current.providerId == registration.providerId &&
                            current.revision == registration.revision,
                    )
                    val now = System.currentTimeMillis()
                    val document =
                        NovelOnlineDocument(
                            bookId = NovelIdentity.remote(sourceId, reference),
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
                            coverReference = details.entry.cover,
                            alternativeTitles = details.entry.alternativeTitles,
                            genres = details.genres,
                            status = details.status.name,
                        )
                    val path = indexes.write(document)
                    store.putOnline(
                        NovelBook(
                            id = document.bookId,
                            title = details.entry.title,
                            authors = details.entry.authors,
                            description = details.description,
                            format = NovelFormat.ONLINE,
                            contentVersion = NovelOnlineIdentity.edition(origin),
                            origin = origin,
                            publicationFile = path,
                            favorite = false,
                            importedAt = now,
                            modifiedAt = now,
                        ),
                    )
                    document.coverReference?.let { reference ->
                        val cached =
                            cachedArtwork(
                                NovelArtworkCache.key(sourceId, origin.reference, reference),
                            )
                        if (cached != null) {
                            optionalArtwork {
                                persistCover(sourceId, reference, origin.reference, cached)
                            }
                        } else {
                            warmCover(sourceId, reference, origin.reference)
                        }
                    }
                    requireNotNull(store.get(document.bookId))
                }
            }
        }

    suspend fun pausePreparation(sourceId: Long, reference: String) =
        withContext(Dispatchers.IO) {
            (sources.resolve(sourceId).source as? NovelChapterPreparation)?.pausePreparation(
                reference,
            )
        }

    suspend fun open(book: NovelBook): NovelOnlineAccess =
        withContext(Dispatchers.IO) {
            require(book.format == NovelFormat.ONLINE)
            val origin = requireNotNull(book.origin)
            val document =
                document(book)
                    ?: run {
                        refresh(origin.sourceId, origin.reference)
                        requireNotNull(indexes.read(origin))
                    }
            val saved = downloads.open(book, document)
            try {
                if (offlineOnly()) {
                    if (saved.chapterVersions.value.isEmpty()) {
                        throw IOException("No chapters are downloaded")
                    }
                    return@withContext saved
                }
                NovelDownloadAccess(saved, openNetwork(book, document))
            } catch (error: Throwable) {
                saved.close()
                throw error
            }
        }

    /** Explicit download jobs use their immutable manifest rather than a later metadata refresh. */
    suspend fun openNetwork(book: NovelBook, document: NovelOnlineDocument): NovelOnlineAccess =
        withContext(Dispatchers.IO) {
            require(
                book.format == NovelFormat.ONLINE &&
                    book.origin == document.origin &&
                    book.id == document.bookId,
            )
            val origin = requireNotNull(book.origin)
            // Resolution is deferred until a cache miss, so cached reading also works offline.
            val lock = Mutex()
            var runtime: NovelSourceRuntime? = null
            suspend fun acquireRuntime(): NovelSourceRuntime = lock.withLock {
                runtime
                    ?: NovelSourceRuntime(
                        expected = sources.resolve(origin.sourceId),
                        resolve = { sources.resolve(origin.sourceId) },
                        imageLoader = ::image,
                    )
                        .also { runtime = it }
            }
            cache.open(
                book,
                document,
                object : NovelOnlineFetcher {
                    override suspend fun chapter(reference: String): NovelOnlineBody =
                        acquireRuntime().chapter(reference)

                    override suspend fun image(reference: String): NovelOnlineImage =
                        acquireRuntime().image(reference)
                },
            )
        }

    private suspend fun image(
        request: it.lorelore.nyanime.source.novel.NovelResourceRequest,
    ): NovelOnlineImage = images.read(request)
}
