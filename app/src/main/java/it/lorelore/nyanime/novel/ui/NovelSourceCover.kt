package it.lorelore.nyanime.novel.ui

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import it.lorelore.nyanime.novel.NovelOnlineIdentity
import it.lorelore.nyanime.novel.source.NovelOnlineRepository
import okio.Buffer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Source-owned references are resolved by their capability, never treated as plain image URLs. */
internal data class NovelSourceCover(
    val sourceId: Long,
    val reference: String,
    val bookReference: String,
) {
    val key
        get() =
            "novel-cover:$sourceId:" +
                NovelOnlineIdentity.digest(bookReference) +
                ":" +
                NovelOnlineIdentity.digest(reference)

    override fun toString() = key
}

internal class NovelSourceCoverKeyer : Keyer<NovelSourceCover> {
    override fun key(data: NovelSourceCover, options: Options) = data.key
}

internal class NovelSourceCoverFetcher
private constructor(private val data: NovelSourceCover, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val image =
            Injekt.get<NovelOnlineRepository>()
                .cover(data.sourceId, data.reference, data.bookReference)
        return SourceFetchResult(
            source =
            ImageSource(source = Buffer().write(image.bytes), fileSystem = options.fileSystem),
            mimeType = image.mediaType,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<NovelSourceCover> {
        override fun create(
            data: NovelSourceCover,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = NovelSourceCoverFetcher(data, options)
    }
}
