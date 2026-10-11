package it.lorelore.nyanime.novel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.PositionsService
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.StringResource
import org.readium.r2.streamer.parser.epub.EpubPositionsService
import java.net.URI

/** Toolkit positions from verified ZIP lengths, without reading every chapter at opening. */
internal object NovelPositions {
    private const val MAX_POSITIONS = 16_384

    fun create(
        readingOrder: List<Link>,
        layout: Layout?,
        lengths: Map<String, Long>,
    ): PositionsService {
        require(readingOrder.isNotEmpty() && readingOrder.size <= 10_000)
        // Position calculation must not trust encryption.originalLength supplied by a document.
        // The publication's actual links and encryption metadata remain unchanged.
        val links = readingOrder.map {
            Link(href = it.href, mediaType = it.mediaType, title = it.title)
        }
        val sizes = links.associate { link ->
            val url = link.href.resolve().removeFragment().removeQuery()
            val path = URI(url.toString()).path
            url to requireNotNull(lengths[path]) { "Unverified chapter length" }
        }
        val total = links.sumOf { sizes.getValue(it.href.resolve().removeFragment().removeQuery()) }
        // ceil(length / step) adds at most one slot per chapter. Keep the allocation bounded,
        // including books with thousands of very short chapters and repeated spine references.
        val slots = MAX_POSITIONS - readingOrder.size
        val step =
            maxOf(1024L, (total + slots - 1) / slots).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val container =
            object : Container<Resource> {
                override val entries = sizes.keys

                override fun get(url: Url): Resource? {
                    val size = sizes[url.removeFragment().removeQuery()] ?: return null
                    return object : Resource by StringResource("") {
                        override suspend fun length(): Try<Long, ReadError> = Try.success(size)

                        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> =
                            Try.failure(
                                ReadError.UnsupportedOperation("Position metadata has no content"),
                            )
                    }
                }

                override fun close() = Unit
            }
        val delegate =
            EpubPositionsService(
                links,
                layout,
                container,
                EpubPositionsService.ReflowableStrategy.OriginalLength(step),
            )
        val mutex = Mutex()
        return object : PositionsService by delegate {
            override suspend fun positions(): List<Locator> = positionsByReadingOrder().flatten()

            override suspend fun positionsByReadingOrder(): List<List<Locator>> =
                withContext(Dispatchers.IO) {
                    mutex.withLock { delegate.positionsByReadingOrder() }
                }
        }
    }
}
