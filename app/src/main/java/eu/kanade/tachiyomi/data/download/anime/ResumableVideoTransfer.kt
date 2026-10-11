package eu.kanade.tachiyomi.data.download.anime

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Throttler
import okio.buffer
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

data class PartialVideoMetadata(val identity: String, val etag: String?, val total: Long)

interface PartialVideoStore {
    var metadata: PartialVideoMetadata?
    fun size(): Long
    fun open(offset: Long): OutputStream
}

class VideoHttpException(val status: Int, val retryAfter: Long? = null) : IOException("HTTP $status")
class VideoStorageException(message: String) : IOException(message)
class UnsupportedDirectVideo : IOException("Unsupported direct video response")

/** Range requests are accepted only for the same representation and a strong validator. */
class ResumableVideoTransfer(
    private val client: OkHttpClient,
    private val store: PartialVideoStore,
    private val availableBytes: () -> Long = { -1L },
    private val throttler: Throttler = Throttler(),
) {
    // Retain the existing trailing-lambda storage callback at call sites.
    constructor(client: OkHttpClient, store: PartialVideoStore, availableBytes: () -> Long) :
        this(client, store, availableBytes, Throttler())

    suspend fun download(url: String, headers: Headers, progress: (Long, Long) -> Unit) = coroutineScope {
        val identity = MessageDigest.getInstance("SHA-256").digest((url + headers).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val previous = store.metadata?.takeIf { it.identity == identity }
        val size = store.size()
        val offset = size.takeIf { it > 0 && previous?.etag != null && it <= previous.total } ?: 0L
        val request = Request.Builder().url(url).headers(headers).header("Accept-Encoding", "identity").apply {
            removeHeader("Range")
            removeHeader("If-Range")
            if (offset > 0) {
                header("Range", "bytes=$offset-")
                header("If-Range", previous!!.etag!!)
            }
        }.build()
        val call = client.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.await().use { response ->
                // A completed partial is still revalidated before publication.
                if (response.code == 416 &&
                    offset > 0 &&
                    offset == previous?.total &&
                    response.header("ETag") == previous?.etag &&
                    response.header("Content-Range") == "bytes */$offset"
                ) {
                    progress(offset, offset)
                    return@coroutineScope
                }
                if (!response.isSuccessful) {
                    if (response.code == 416) store.metadata = null
                    throw VideoHttpException(response.code, retryAfterMillis(response.header("Retry-After")))
                }
                val mime = response.body.contentType()?.toString().orEmpty().lowercase()
                if (listOf("text/", "json", "mpegurl", "dash").any(mime::contains)) {
                    throw UnsupportedDirectVideo()
                }
                if (response.header("Content-Encoding")?.let { it != "identity" } == true) {
                    throw UnsupportedDirectVideo()
                }
                val etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                val range = response.header("Content-Range")
                val start: Long
                val total: Long
                if (response.code == 206) {
                    val parts = RANGE.matchEntire(range.orEmpty())?.destructured
                        ?: invalidRange("Invalid Content-Range")
                    val (from, to, length) = parts
                    start = from.toLongOrNull() ?: invalidRange("Invalid range start")
                    val end = to.toLongOrNull() ?: invalidRange("Invalid range end")
                    total = length.toLongOrNull() ?: invalidRange("Invalid range length")
                    if (offset == 0L ||
                        start != offset ||
                        end != total - 1 ||
                        total != previous?.total ||
                        etag != previous?.etag ||
                        (response.body.contentLength() >= 0 && response.body.contentLength() != total - start)
                    ) {
                        invalidRange("Server returned a different video range")
                    }
                } else {
                    if (response.code != 200) {
                        throw UnsupportedDirectVideo()
                    }
                    start = 0
                    total = response.body.contentLength()
                }
                if (start == 0L) {
                    val prefix = response.body.source().peek().use { probe ->
                        probe.request(64)
                        probe.readUtf8(minOf(64, probe.buffer.size)).trimStart().removePrefix("\uFEFF").lowercase()
                    }
                    if (listOf("#extm3u", "<mpd", "<?xml", "<!doctype", "<html", "{")
                            .any(prefix::startsWith)
                    ) {
                        // Some servers disguise manifests or error pages with a video URL/MIME type.
                        throw UnsupportedDirectVideo()
                    }
                }
                val free = availableBytes()
                val reclaimed = if (start == 0L) size.coerceAtLeast(0) else 0L
                val writable = if (free < 0) {
                    -1L
                } else if (reclaimed > Long.MAX_VALUE - free) {
                    Long.MAX_VALUE
                } else {
                    free + reclaimed
                }
                if (writable >= 0 && total > 0 && total - start > writable - STORAGE_RESERVE) {
                    throw VideoStorageException(
                        "Spazio insufficiente per completare il video. Libera spazio e riprova.",
                    )
                }
                val context = currentCoroutineContext()
                var written = start
                store.open(start).use { output ->
                    store.metadata = PartialVideoMetadata(identity, etag, total)
                    throttler.source(response.body.source()).buffer().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastSpaceCheck = written
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (total >= 0 && count > total - written) {
                                throw IOException("Video exceeds declared length")
                            }
                            output.write(buffer, 0, count)
                            written += count
                            progress(written, total)
                            if (written - lastSpaceCheck >= 8 * 1024 * 1024) {
                                lastSpaceCheck = written
                                if (availableBytes() in 0 until STORAGE_RESERVE) {
                                    throw VideoStorageException(
                                        "Spazio esaurito durante il download. Libera spazio e riprova.",
                                    )
                                }
                            }
                        }
                    }
                }
                context.ensureActive()
                if (written <= 0 || (total >= 0 && written != total)) {
                    throw IOException("Incomplete video download")
                }
                progress(written, written)
            }
        } finally {
            cancellation.cancel()
        }
    }

    private fun invalidRange(message: String): Nothing {
        store.metadata = null
        throw IOException(message)
    }

    companion object {
        private val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        private const val STORAGE_RESERVE = 32L * 1024 * 1024
    }
}
