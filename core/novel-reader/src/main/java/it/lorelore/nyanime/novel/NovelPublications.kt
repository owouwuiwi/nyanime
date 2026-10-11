@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package it.lorelore.nyanime.novel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.positionsServiceFactory
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.asset.ContainerAsset
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.mediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import java.io.File
import java.io.IOException

/**
 * Local publications have no network client. Online source resources must be materialized
 * separately.
 */
class NovelPublications(context: Context) {
    private val blockedNetwork =
        object : HttpClient {
            override suspend fun stream(request: HttpRequest): Try<HttpStreamResponse, HttpError> =
                Try.failure(
                    HttpError.IO(IOException("Remote resources are disabled for local books")),
                )
        }
    private val retriever =
        AssetRetriever(context.applicationContext.contentResolver, blockedNetwork)
    private val opener = PublicationOpener(EpubParser(blockedNetwork))

    suspend fun open(file: File): Publication =
        acquireNovelResource(
            acquire = {
                require(file.isFile)
                val inspection = NovelEpubPolicy.inspect(file)
                if (inspection.unsupportedEncryption) {
                    throw UnsupportedDrmException()
                }
                val asset = retriever.retrieve(file).getOrElse { throw IOException(it.message) }
                var publication: Publication? = null
                try {
                    publication =
                        opener
                            .open(
                                asset,
                                allowUserInteraction = false,
                                onCreatePublication = {
                                    val media =
                                        (manifest.readingOrder + manifest.resources).associate {
                                            it.href.resolve().removeFragment().removeQuery() to
                                                it.mediaType
                                        }
                                    container =
                                        LocalContainer(
                                            container,
                                            (asset as? ContainerAsset)
                                                ?.container
                                                ?.entries
                                                .orEmpty(),
                                            media,
                                        )
                                    // WebResourceResponse receives this manifest MIME verbatim. A
                                    // charset parameter
                                    // makes Android download XHTML instead of rendering it.
                                    // Resource bytes/metadata
                                    // are UTF-8; the manifest keeps a bare MIME for the navigator's
                                    // response.
                                    fun forNavigator(link: org.readium.r2.shared.publication.Link) =
                                        link.mediaType?.let { type ->
                                            link.copy(
                                                mediaType =
                                                MediaType("${type.type}/${type.subtype}"),
                                            )
                                        } ?: link
                                    manifest =
                                        manifest.copy(
                                            readingOrder =
                                            manifest.readingOrder.map(::forNavigator),
                                            resources = manifest.resources.map(::forNavigator),
                                        )
                                    servicesBuilder.positionsServiceFactory = { context ->
                                        NovelPositions.create(
                                            context.manifest.readingOrder,
                                            context.manifest.metadata.layout,
                                            inspection.readingLengths,
                                        )
                                    }
                                },
                            )
                            .getOrElse { throw IOException(it.message) }
                    if (publication.isRestricted) throw UnsupportedDrmException()
                    require(publication.readingOrder.isNotEmpty()) {
                        "Publication has no readable chapters"
                    }
                    publication
                } catch (error: Throwable) {
                    if (publication != null) publication.close() else asset.close()
                    throw error
                }
            },
            release = Publication::close,
        )

    class UnsupportedDrmException : IOException("This publication uses unsupported DRM")

    internal class LocalContainer(
        private val delegate: Container<Resource>,
        archiveEntries: Set<Url>,
        private val media: Map<Url, MediaType?>,
    ) : Container<Resource> {
        // EpubParser may compose a remote container. Expose only relative archive entries.
        override val entries = archiveEntries.filterTo(mutableSetOf()) { it !is AbsoluteUrl }

        override fun get(url: Url): Resource? {
            val local = url.removeFragment().removeQuery()
            if (local is AbsoluteUrl || local !in entries) return null
            val resource = delegate[local] ?: return null
            val type = media[local]
            return when {
                type?.isHtml == true ||
                    local.filename?.substringAfterLast('.')?.lowercase() in
                    setOf("html", "htm", "xhtml") ->
                    SanitizedResource(resource, type ?: MediaType.XHTML)
                type?.subtype == "svg+xml" ||
                    local.filename?.endsWith(".svg", ignoreCase = true) == true ->
                    SanitizedResource(resource, requireNotNull(MediaType("image/svg+xml")))
                // A publication's own JavaScript is never served, even through a direct request.
                local.filename?.substringAfterLast('.')?.lowercase() in setOf("js", "mjs") ||
                    type?.subtype?.contains("javascript") == true ||
                    type?.subtype?.contains("ecmascript") == true -> {
                    resource.close()
                    null
                }
                else -> resource
            }
        }

        override fun close() = delegate.close()
    }

    /**
     * Bound memory before decoding, keep ranges consistent after sanitizing, release bytes on
     * close.
     */
    private class SanitizedResource(private val source: Resource, private val type: MediaType) :
        Resource {
        override val sourceUrl: AbsoluteUrl? = null
        private val mutex = Mutex()
        private var bytes: Try<ByteArray, ReadError>? = null

        @Volatile private var closed = false

        override suspend fun properties() =
            source.properties().map { props ->
                props.copy { mediaType = MediaType("${type.type}/${type.subtype};charset=utf-8") }
            }

        private suspend fun content(): Try<ByteArray, ReadError> = mutex.withLock {
            if (closed) {
                return@withLock Try.failure(ReadError.UnsupportedOperation("Resource is closed"))
            }
            bytes?.let {
                return@withLock it
            }
            val original =
                source.read(0L..NovelHtmlPolicy.MAX_HTML_BYTES.toLong()).getOrElse {
                    return@withLock Try.failure(it)
                }
            val result =
                try {
                    Try.success(
                        NovelHtmlPolicy.sanitize(
                            original,
                            type.subtype != "html",
                            type.charset,
                            vector = type.subtype == "svg+xml",
                        ),
                    )
                } catch (error: IllegalArgumentException) {
                    Try.failure(ReadError.Decoding(error))
                }
            if (!closed) bytes = result
            result
        }

        override suspend fun length(): Try<Long, ReadError> =
            withContext(Dispatchers.IO) {
                content().map { it.size.toLong() }
            }

        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> =
            withContext(Dispatchers.IO) {
                content().map { data ->
                    if (range == null) {
                        data
                    } else {
                        val start = range.first.coerceIn(0L, data.size.toLong()).toInt()
                        val end = range.last.coerceIn(-1L, data.size.toLong() - 1L).toInt()
                        if (end < start) byteArrayOf() else data.copyOfRange(start, end + 1)
                    }
                }
            }

        override fun close() {
            closed = true
            bytes = null
            source.close()
        }
    }
}
