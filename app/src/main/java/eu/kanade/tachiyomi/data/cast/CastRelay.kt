package eu.kanade.tachiyomi.data.cast

import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves only resources registered for the selected episode. Receiver requests never contain
 * source cookies or arbitrary upstream URLs. Bodies are streamed, except bounded HLS playlists.
 */
class CastRelay(
    private val address: String,
    sourceClient: OkHttpClient,
    private val headers: Headers,
    private val openLocal: (String) -> LocalResource? = { null },
) : NanoHTTPD(address, 0) {
    data class LocalResource(val stream: InputStream, val length: Long, val mime: String)

    private val client = sourceClient.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val token = UUID.randomUUID().toString()
    private val resources = ConcurrentHashMap<String, String>()
    private val resourceIds = ConcurrentHashMap<String, String>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val permits = Semaphore(8)
    private val streams = ConcurrentHashMap.newKeySet<InputStream>()

    @Volatile private var closed = false

    @Synchronized
    fun register(url: String): String {
        check(!closed)
        val id = resourceIds[url] ?: run {
            require(resources.size < 30_000) { "Troppi segmenti nella sessione Cast" }
            require(url.toHttpUrlOrNull() != null || url.startsWith("content://") || url.startsWith("file://"))
            val extension = when {
                url.substringBefore('?').endsWith(".m3u8", true) -> ".m3u8"
                url.substringBefore('?').endsWith(".vtt", true) -> ".vtt"
                else -> ""
            }
            val value = UUID.randomUUID().toString() + extension
            resources[value] = url
            resourceIds[url] = value
            value
        }
        return "http://$address:$listeningPort/cast/$token/$id"
    }

    /** Inspect only a prefix; never download a video into memory just to determine its format. */
    fun prepare(url: String): Pair<String, String> {
        val http = url.toHttpUrlOrNull()
        val mime = if (http != null) {
            val call = client.newCall(request(url).header("Range", "bytes=0-511").build())
            call.timeout().timeout(15, TimeUnit.SECONDS)
            track(call)
            try {
                call.execute().use { response ->
                    require(response.isSuccessful) { "La fonte non ha fornito il video (HTTP ${response.code})" }
                    if (response.peekBody(512).string().trimStart().startsWith("#EXTM3U")) {
                        "application/x-mpegURL"
                    } else {
                        CastWire.mime(url, response.header("Content-Type"))
                    }
                }
            } finally {
                calls.remove(call)
                call.cancel()
            }
        } else {
            val local = openLocal(url) ?: error("File locale non disponibile")
            local.stream.close()
            local.mime
        }
        require(mime != "application/dash+xml") {
            "Questa qualità usa DASH. Scegli una qualità HLS o MP4 per trasmettere."
        }
        return register(url) to mime
    }

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/cast/$token/"
        val url = session.uri.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)?.let(resources::get)
            ?: return response(404, "text/plain", "Risorsa non disponibile")
        if (closed) return response(410, "text/plain", "Sessione terminata")
        if (session.method == Method.OPTIONS) return cors(response(204, "text/plain", ""))
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return response(405, "text/plain", "Metodo non consentito")
        }
        if (!permits.tryAcquire()) return cors(response(503, "text/plain", "Riprova"))
        val result = try {
            if (url.toHttpUrlOrNull() == null) serveLocal(url, session) else serveRemote(url, session)
        } catch (_: Exception) {
            cors(response(502, "text/plain", "La fonte non risponde"))
        }
        // NanoHTTPD 2.3.1 still reads response data for HEAD. Keep its length, send no bytes.
        if (session.method == Method.HEAD) {
            runCatching { result.data.close() }
            result.data = ByteArrayInputStream(ByteArray(0))
        }
        // NanoHTTPD transmits the body after serve returns. Retain the slot until it closes it.
        val stream = object : FilterInputStream(result.data) {
            private val released = AtomicBoolean()
            override fun close() {
                if (!released.compareAndSet(false, true)) return
                try {
                    super.close()
                } finally {
                    streams.remove(this)
                    permits.release()
                }
            }
        }
        synchronized(this) {
            result.data = stream
            if (closed) stream.close() else streams.add(stream)
        }
        return result
    }

    override fun useGzipWhenAccepted(response: Response) = false

    private fun serveRemote(url: String, session: IHTTPSession): Response {
        val request = request(url).apply {
            session.headers["range"]?.let { header("Range", it) }
            // Some extension HTTP servers incorrectly send a HEAD body. Do not reuse that connection.
            if (session.method == Method.HEAD) head().header("Connection", "close")
        }.build()
        val call = client.newCall(request)
        track(call)
        val upstream = try {
            call.execute()
        } catch (e: Exception) {
            calls.remove(call)
            throw e
        }
        val body = upstream.body
        try {
            val type = CastWire.mime(url, upstream.header("Content-Type"))
            if (upstream.isSuccessful &&
                session.method == Method.GET &&
                (type == "application/x-mpegURL" || upstream.peekBody(16).string().startsWith("#EXTM3U"))
            ) {
                val source = body.source().apply { request(MAX_PLAYLIST_BYTES + 1L) }
                require(source.buffer.size <= MAX_PLAYLIST_BYTES) { "Playlist troppo grande" }
                val manifest = CastWire.rewriteHls(source.readUtf8(), upstream.request.url) {
                    register(it.toString())
                }
                upstream.close()
                calls.remove(call)
                return cors(response(200, "application/x-mpegURL", manifest))
            }
            val stream = object : FilterInputStream(body.byteStream()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        upstream.close()
                        calls.remove(call)
                    }
                }
            }
            val length = if (session.method == Method.HEAD) {
                upstream.header("Content-Length")?.toLongOrNull() ?: body.contentLength()
            } else {
                body.contentLength()
            }
            val result = if (length >= 0) {
                newFixedLengthResponse(status(upstream.code), upstream.header("Content-Type") ?: type, stream, length)
            } else {
                newChunkedResponse(status(upstream.code), upstream.header("Content-Type") ?: type, stream)
            }
            listOf("Content-Range", "Accept-Ranges", "ETag", "Last-Modified").forEach { key ->
                upstream.header(key)?.let { result.addHeader(key, it) }
            }
            return cors(result)
        } catch (e: Exception) {
            upstream.close()
            calls.remove(call)
            throw e
        }
    }

    private fun serveLocal(url: String, session: IHTTPSession): Response {
        val local = openLocal(url) ?: return response(404, "text/plain", "File non disponibile")
        val range = try {
            CastWire.range(session.headers["range"], local.length)
        } catch (_: Exception) {
            local.stream.close()
            return cors(
                response(416, "text/plain", "").apply {
                    addHeader("Content-Range", "bytes */${local.length}")
                },
            )
        }
        try {
            var remaining = if (session.method == Method.HEAD) 0 else range?.first ?: 0
            while (remaining > 0) {
                val skipped = local.stream.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    check(local.stream.read() >= 0)
                    remaining--
                }
            }
            return cors(
                newFixedLengthResponse(
                    status(if (range == null) 200 else 206),
                    local.mime,
                    local.stream,
                    range?.length ?: local.length,
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    if (range != null) addHeader("Content-Range", "bytes ${range.first}-${range.last}/${local.length}")
                },
            )
        } catch (e: Exception) {
            local.stream.close()
            throw e
        }
    }

    private fun request(url: String): Request.Builder = Request.Builder().url(url).headers(headers)
        .removeHeader("Host").removeHeader("Connection").removeHeader("Content-Length")
        .removeHeader("Range").header("Accept-Encoding", "identity")

    private fun cors(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
        addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
        addHeader("Cache-Control", "no-store")
    }

    override fun stop() {
        revoke()
        streams.forEach { runCatching { it.close() } }
        streams.clear()
        super.stop()
        resources.clear()
        resourceIds.clear()
    }

    /** Fast cancellation; the owning IO cleanup subsequently stops the listening socket. */
    fun revoke() {
        synchronized(this) { closed = true }
        calls.forEach(Call::cancel)
        calls.clear()
    }

    @Synchronized
    private fun track(call: Call) {
        check(!closed) { "Sessione terminata" }
        calls.add(call)
    }

    companion object {
        private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
        private fun status(code: Int) = object : Response.IStatus {
            override fun getRequestStatus() = code
            override fun getDescription() = "$code ${Response.Status.lookup(
                code,
            )?.description?.substringAfter(' ') ?: "Response"}"
        }
        private fun response(code: Int, mime: String, text: String): Response =
            newFixedLengthResponse(status(code), mime, text)
    }
}
