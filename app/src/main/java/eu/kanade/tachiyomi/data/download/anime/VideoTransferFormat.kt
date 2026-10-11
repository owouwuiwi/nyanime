package eu.kanade.tachiyomi.data.download.anime

import eu.kanade.tachiyomi.data.download.transfer.TransferCoordinator
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Container recognition only: URLs, authentication and extraction belong to extensions. */
enum class VideoTransferFormat(val extension: String?) {
    MP4("mp4"),
    M4V("m4v"),
    MKV("mkv"),
    WEBM("webm"),
    HLS(null),
    OTHER(null),
    ;

    companion object {
        fun fromUrl(url: String): VideoTransferFormat = when (
            url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringAfterLast('.', "")?.lowercase()
        ) {
            "mp4" -> MP4
            "m4v" -> M4V
            "mkv" -> MKV
            "webm" -> WEBM
            "m3u8" -> HLS
            else -> OTHER
        }

        fun fromBytes(bytes: ByteArray, mime: String?): VideoTransferFormat {
            val prefix = bytes.toString(Charsets.UTF_8).trimStart().removePrefix("\uFEFF")
            if (prefix.startsWith("#EXTM3U")) return HLS
            if (bytes.size >= 12 && bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) return MP4
            if (bytes.size >= 4 && bytes.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3)) {
                return if (mime?.substringBefore(';')?.trim()?.equals("video/webm", true) == true) WEBM else MKV
            }
            return OTHER
        }
    }
}

/** Read at most 64 bytes even when a server ignores Range; no full download or duration scan. */
suspend fun probeVideoTransferFormat(
    client: OkHttpClient,
    sourceId: Long,
    url: String,
    headers: Headers,
): VideoTransferFormat = TransferCoordinator.transfer(sourceId) {
    coroutineScope {
        val call = client.newCall(
            Request.Builder().url(url).headers(headers).header("Range", "bytes=0-63")
                .removeHeader("If-Range").header("Accept-Encoding", "identity").build(),
        )
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.await().use { response ->
                if (response.code in setOf(400, 405, 416)) return@use VideoTransferFormat.OTHER
                if (!response.isSuccessful) {
                    throw VideoHttpException(response.code, retryAfterMillis(response.header("Retry-After")))
                }
                val buffer = ByteArray(64)
                var offset = 0
                response.body.byteStream().use { input ->
                    while (offset < buffer.size) {
                        val count = input.read(buffer, offset, buffer.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                }
                VideoTransferFormat.fromBytes(buffer.copyOf(offset), response.body.contentType()?.toString())
            }
        } finally {
            cancellation.cancel()
        }
    }
}
