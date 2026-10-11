package it.lorelore.nyanime.novel.source

import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import it.lorelore.nyanime.novel.NovelOnlineImage
import it.lorelore.nyanime.source.novel.NovelResourceRequest
import java.io.IOException

/** One source-mediated image path is shared by the normal reader and disposable previews. */
internal class NovelSourceImages(private val network: NetworkHelper) {
    private val http by lazy { NovelResourceHttp(network.apiClient) }

    suspend fun read(request: NovelResourceRequest): NovelOnlineImage =
        checkedNovelImage(
            http.read(
                request.copy(
                    headers =
                    if (
                        request.headers.size >= 100 ||
                        request.headers.keys.any {
                            it.equals("User-Agent", ignoreCase = true)
                        }
                    ) {
                        request.headers
                    } else {
                        request.headers + ("User-Agent" to network.defaultUserAgentProvider())
                    },
                ),
            ),
        )
}

internal fun checkedNovelImage(bytes: ByteArray): NovelOnlineImage {
    require(bytes.size in 1..8 * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val mime = bounds.outMimeType ?: throw IOException("Unsupported chapter image")
    require(
        bounds.outWidth in 1..8192 &&
            bounds.outHeight in 1..8192 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 16L * 1024 * 1024,
    )
    return NovelOnlineImage(bytes, mime)
}
