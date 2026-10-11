@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package it.lorelore.nyanime.novel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.PerResourcePositionsService
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.file.FileSystemError
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.mediaType
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Returned files are private, verified materializations; no HTTP headers enter the publication. */
data class NovelOnlineFile(val file: File, val mediaType: MediaType, val sha256: String) {
    init {
        require(sha256.matches(Regex("[a-f0-9]{64}")))
    }
}

fun interface NovelOnlineOpener {
    suspend fun open(book: NovelBook): NovelOnlineAccess
}

interface NovelOnlineAccess : AutoCloseable {
    val document: NovelOnlineDocument

    /** Only cached, verified chapters. Used to constrain offline text search and stale anchors. */
    val chapterVersions: StateFlow<Map<String, String>>

    /** Pure lookup: must not do disk I/O or consult a website on the navigator thread. */
    fun contains(href: String): Boolean

    /**
     * Pin a known resource until its stream closes, so cache eviction cannot remove a live file.
     */
    fun retain(href: String): AutoCloseable = AutoCloseable {}

    /** A separate cache-only view for search: missing chapters must never trigger network work. */
    suspend fun cachedOnly(): NovelOnlineAccess =
        throw UnsupportedOperationException("No cached chapter view")

    suspend fun materialize(href: String): NovelOnlineFile
}

/** The same EPUB navigator, backed by source-mediated relative resources rather than web URLs. */
object NovelOnlinePublication {
    fun open(book: NovelBook, access: NovelOnlineAccess): Publication {
        val container = OnlineContainer(access)
        return try {
            require(book.format == NovelFormat.ONLINE && book.origin == access.document.origin)
            require(book.id == access.document.bookId)
            val links =
                access.document.chapters.map {
                    Link(
                        href = requireNotNull(Href(it.href)),
                        mediaType = MediaType.HTML,
                        title = it.title,
                    )
                }
            Publication(
                manifest =
                Manifest(
                    metadata =
                    Metadata(
                        identifier = book.id,
                        localizedTitle = LocalizedString(book.title),
                        conformsTo = setOf(Publication.Profile.EPUB),
                        layout = Layout.REFLOWABLE,
                        languages = listOfNotNull(access.document.language),
                    ),
                    readingOrder = links,
                    tableOfContents = links,
                ),
                container = container,
                // Resource-relative positions are approximate, not invented EPUB page numbers.
                servicesBuilder =
                Publication.ServicesBuilder(
                    positions = PerResourcePositionsService.createFactory(MediaType.HTML),
                ),
            )
        } catch (error: Throwable) {
            container.close()
            throw error
        }
    }

    private class OnlineContainer(private val access: NovelOnlineAccess) : Container<Resource> {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val permits = Semaphore(2)
        private val pending = ConcurrentHashMap<String, Deferred<NovelOnlineFile>>()
        private val opened = ConcurrentHashMap.newKeySet<OnlineResource>()
        private val closing = AtomicBoolean(false)
        private val closed
            get() = closing.get()

        override val entries: Set<Url> =
            access.document.chapters.mapTo(mutableSetOf()) {
                requireNotNull(Url(it.href))
            }

        override fun get(url: Url): Resource? {
            if (closed) return null
            // Readium serves a resource absent from its initial manifest using this virtual URL.
            // Illustrations are declared by a lazily loaded chapter, so map ONLY the navigator's
            // own origin back into the exact source-owned resource allowlist. No HTTP is done here.
            val local = if (url is AbsoluteUrl) NAVIGATOR_ORIGIN.relativize(url) else url
            if (local is AbsoluteUrl) return null
            val href = local.removeFragment().removeQuery().toString()
            if (!access.contains(href)) return null
            return OnlineResource(href).also {
                opened += it
                if (closed) it.close()
            }
        }

        private suspend fun file(href: String): NovelOnlineFile {
            check(!closed)
            val proposed =
                scope.async(start = CoroutineStart.LAZY) {
                    permits.withPermit {
                        withTimeout(30_000) {
                            check(!closed && access.contains(href))
                            access.materialize(href).also {
                                require(it.file.isFile && it.file.length() in 1..MAX_RESOURCE_BYTES)
                            }
                        }
                    }
                }
            val task = pending.putIfAbsent(href, proposed) ?: proposed
            if (task !== proposed) proposed.cancel()
            if (task === proposed) task.invokeOnCompletion { pending.remove(href, task) }
            return task.await()
        }

        override fun close() {
            if (closing.getAndSet(true)) return
            scope.cancel()
            opened.toList().forEach { it.close() }
            access.close()
        }

        private inner class OnlineResource(private val href: String) : Resource {
            private val lease = access.retain(href)
            override val sourceUrl: AbsoluteUrl? = null
            private val mutex = Mutex()
            private val reads = Mutex()
            private var delegate: FileResource? = null
            private var media: MediaType? = null

            private val releasing = AtomicBoolean(false)
            private val released
                get() = releasing.get()

            private suspend fun resource(): FileResource = mutex.withLock {
                check(!closed && !released)
                delegate?.let {
                    return@withLock it
                }
                val cached = file(href)
                check(!closed && !released)
                media = cached.mediaType
                FileResource(cached.file).also {
                    delegate = it
                    if (closed || released) {
                        it.close()
                        delegate = null
                        throw IOException("Resource was released")
                    }
                }
            }

            private suspend fun <T> result(
                operation: suspend () -> Try<T, ReadError>,
            ): Try<T, ReadError> =
                try {
                    operation()
                } catch (error: Exception) {
                    // The materialization has its own job and deadline. Its cancellation is
                    // a resource failure, not cancellation of Readium's WebView/JNI caller.
                    // Only propagate cancellation when the actual caller has been cancelled.
                    currentCoroutineContext().ensureActive()
                    Try.failure(
                        ReadError.Access(
                            FileSystemError.IO(IOException("Novel resource unavailable", error)),
                        ),
                    )
                } catch (error: OutOfMemoryError) {
                    Try.failure(ReadError.OutOfMemory(error))
                }

            override suspend fun properties(): Try<Resource.Properties, ReadError> = result {
                resource()
                Try.success(Resource.Properties { mediaType = media })
            }

            override suspend fun length(): Try<Long, ReadError> = result { resource().length() }

            override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> =
                reads.withLock {
                    result { resource().read(range) }
                }

            override fun close() {
                if (releasing.getAndSet(true)) return
                delegate?.close()
                delegate = null
                opened -= this
                lease.close()
            }
        }
    }

    private const val MAX_RESOURCE_BYTES = 16L * 1024 * 1024
    private val NAVIGATOR_ORIGIN = requireNotNull(AbsoluteUrl("https://readium_package/"))
}
