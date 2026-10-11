package eu.kanade.tachiyomi.data.download.anime

import eu.kanade.tachiyomi.data.download.transfer.TransferCoordinator
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Throttler
import okio.buffer
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class VideoBlockJournal(
    val identity: String,
    val etag: String,
    val total: Long,
    val blockSize: Int,
    val completed: Map<Int, String> = emptyMap(),
) {
    val count get() = ((total - 1) / blockSize + 1).toInt()
    fun length(index: Int) = minOf(blockSize.toLong(), total - index.toLong() * blockSize)
}

interface VideoBlockStore : PartialVideoStore {
    var journal: VideoBlockJournal?
    fun openBlock(offset: Long): OutputStream
    fun digest(offset: Long, length: Long): String
    fun supportsParallel(): Boolean = true
}

/** Only complete, hashed blocks are durable. Partial blocks are overwritten after interruption. */
class ParallelVideoTransfer(
    private val client: OkHttpClient,
    private val store: VideoBlockStore,
    private val sourceId: Long,
    private val availableBytes: () -> Long = { -1L },
    private val minimumSize: Long = 16L * 1024 * 1024,
    private val throttler: Throttler = Throttler(),
) {
    suspend fun tryDownload(url: String, headers: Headers, progress: (Long, Long) -> Unit): Boolean {
        // Legacy contiguous partials retain their established resume route.
        if (store.size() > 0 && store.journal == null && store.metadata?.etag != null) return false
        if (!store.supportsParallel()) return false
        val identity = videoIdentity(url, headers)
        var state: VideoBlockJournal
        try {
            val probe = request(url, headers, 0, 65_535, null)
            val representation = TransferCoordinator.transfer(sourceId) {
                response(probe, allowRangeFallback = true) { result ->
                    val parts = range(result) ?: throw UnsupportedRanges()
                    val etag = result.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                        ?: throw UnsupportedRanges()
                    val total = parts[2]
                    if (parts[0] != 0L ||
                        parts[1] != minOf(65_535L, total - 1) ||
                        total < minimumSize ||
                        result.body.contentLength() != parts[1] + 1 ||
                        result.body.contentType()?.toString()?.let { it.contains("text") || it.contains("json") } ==
                        true
                    ) {
                        throw UnsupportedRanges()
                    }
                    val bytes = result.body.byteStream().use { input ->
                        val buffer = ByteArray((parts[1] + 1).toInt())
                        var offset = 0
                        while (offset < buffer.size) {
                            val count = input.read(buffer, offset, buffer.size - offset)
                            if (count < 0) throw UnsupportedRanges()
                            offset += count
                        }
                        if (input.read() >= 0) throw UnsupportedRanges()
                        buffer
                    }
                    if (bytes.size.toLong() != parts[1] + 1) throw UnsupportedRanges()
                    val prefix = String(
                        bytes,
                        0,
                        minOf(bytes.size, 64),
                        Charsets.UTF_8,
                    ).trimStart().removePrefix("\uFEFF").lowercase()
                    if (listOf(
                            "#extm3u",
                            "<mpd",
                            "<?xml",
                            "<!doctype",
                            "<html",
                            "{",
                        ).any(prefix::startsWith)
                    ) {
                        throw UnsupportedRanges()
                    }
                    VideoBlockJournal(identity, etag, total, 2 * 1024 * 1024)
                }
            }
            state = store.journal?.takeIf {
                it.identity == identity &&
                    it.etag == representation.etag &&
                    it.total == representation.total &&
                    it.blockSize == representation.blockSize
            } ?: representation.also { store.open(0).close() }
            val free = availableBytes()
            val missing = state.total - state.completed.keys.sumOf { state.length(it) }
            if (free >= 0 &&
                missing > free - 32L * 1024 * 1024
            ) {
                throw VideoStorageException("Spazio insufficiente per completare il video.")
            }
            // Also verifies that the destination supports independent seeks, before sparse writes.
            store.openBlock(0).close()
            val valid = state.completed.filter { (index, hash) ->
                index in 0 until state.count &&
                    runCatching {
                        store.digest(index.toLong() * state.blockSize, state.length(index)) == hash
                    }.getOrDefault(false)
            }
            state = state.copy(completed = valid)
            store.journal = state
            val bytes = AtomicLong(valid.keys.sumOf { state.length(it) })
            progress(bytes.get(), state.total)
            (0 until state.count).filterNot(valid::containsKey).asFlow().flatMapMerge(4) { index ->
                flow {
                    TransferCoordinator.transfer(sourceId) {
                        val offset = index.toLong() * state.blockSize
                        val length = state.length(index)
                        response(request(url, headers, offset, offset + length - 1, state.etag)) { result ->
                            val parts = range(result) ?: throw UnsupportedRanges()
                            if (parts != listOf(offset, offset + length - 1, state.total) ||
                                result.header("ETag") != state.etag ||
                                result.body.contentLength() != length
                            ) {
                                throw UnsupportedRanges()
                            }
                            val digest = MessageDigest.getInstance("SHA-256")
                            var written = 0L
                            store.openBlock(offset).use { output ->
                                throttler.source(result.body.source()).buffer().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        if (n > length - written) throw UnsupportedRanges()
                                        if (written == 0L &&
                                            availableBytes() in 0 until 32L * 1024 * 1024
                                        ) {
                                            throw VideoStorageException("Spazio esaurito durante il download.")
                                        }
                                        output.write(buffer, 0, n)
                                        digest.update(buffer, 0, n)
                                        written += n
                                        TransferCoordinator.bytes(sourceId, n.toLong())
                                        progress(bytes.addAndGet(n.toLong()), state.total)
                                    }
                                }
                            }
                            if (written != length) throw IOException("Incomplete video block")
                            synchronized(store) {
                                state = state.copy(completed = state.completed + (index to digest.digest().hex()))
                                store.journal = state
                            }
                        }
                    }
                    emit(index)
                }
            }.collect()
            if (store.size() != state.total ||
                state.completed.size != state.count
            ) {
                throw IOException("Incomplete video")
            }
            progress(state.total, state.total)
            return true
        } catch (_: UnsupportedRanges) {
            // A sparse partial must never be passed to the contiguous resume engine.
            if (store.journal != null) {
                store.open(0).close()
                store.journal = null
                store.metadata = null
            }
            return false
        } catch (_: UnsupportedDirectVideo) {
            if (store.journal != null) {
                store.open(0).close()
                store.journal = null
                store.metadata = null
            }
            return false
        }
    }

    private suspend fun <T> response(
        request: Request,
        allowRangeFallback: Boolean = false,
        block: suspend (Response) -> T,
    ): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.await().use {
                if (allowRangeFallback && it.code in setOf(400, 405, 416)) throw UnsupportedRanges()
                if (it.code == 429 || it.code in 500..599) TransferCoordinator.backoff(sourceId)
                if (!it.isSuccessful) throw VideoHttpException(it.code, retryAfterMillis(it.header("Retry-After")))
                block(it)
            }
        } finally {
            cancellation.cancel()
        }
    }

    private fun request(url: String, headers: Headers, from: Long, to: Long, etag: String?) = Request.Builder()
        .url(url).headers(headers).header("Accept-Encoding", "identity").header("Range", "bytes=$from-$to")
        .removeHeader("If-Range").apply { etag?.let { header("If-Range", it) } }.build()

    private fun range(response: Response): List<Long>? {
        if (response.code != 206 || response.header("Content-Encoding")?.let { it != "identity" } == true) return null
        return Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
            ?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }
    }
    private class UnsupportedRanges : IOException()
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun videoIdentity(
    url: String,
    headers: Headers,
) = MessageDigest.getInstance("SHA-256").digest((url + headers).toByteArray()).hex()
internal fun retryAfterMillis(header: String?): Long? {
    header?.toLongOrNull()?.let { return it.coerceIn(0, 300) * 1000 }
    return runCatching {
        val until = java.time.ZonedDateTime.parse(
            header,
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
        ).toInstant().toEpochMilli()
        (until - System.currentTimeMillis()).coerceIn(0, 300_000)
    }.getOrNull()
}
