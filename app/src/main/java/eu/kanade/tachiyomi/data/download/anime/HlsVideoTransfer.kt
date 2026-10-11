package eu.kanade.tachiyomi.data.download.anime

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import eu.kanade.tachiyomi.data.download.transfer.TransferCoordinator
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Throttler
import okio.buffer
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class UnsupportedHls : IOException()

data class PreparedHls(
    val directory: File,
    val video: File,
    val audio: List<Pair<File, String>>,
    val subtitles: List<Pair<File, String>>,
    val durationUs: Long,
) {
    fun inspectionArguments(): Array<String> = arrayOf(
        "-v", "error", "-protocol_whitelist", "file,crypto,data", "-allowed_extensions", "ALL",
        "-show_streams", "-of", "json", "-i", video.absolutePath,
    )

    fun arguments(output: String, muxedAudio: Int = 0, muxedSubtitles: Int = 0): Array<String> = buildList {
        fun input(file: File) {
            addAll(
                listOf(
                    "-protocol_whitelist",
                    "file,crypto,data",
                    "-allowed_extensions",
                    "ALL",
                    "-i",
                    file.absolutePath,
                ),
            )
        }
        input(video)
        audio.forEach { input(it.first) }
        subtitles.forEach { input(it.first) }
        addAll(listOf("-map", "0:v", "-map", "0:a?", "-map", "0:s?", "-map", "0:t?"))
        audio.forEachIndexed { index, _ -> addAll(listOf("-map", "${index + 1}:a")) }
        subtitles.forEachIndexed { index, _ -> addAll(listOf("-map", "${index + audio.size + 1}:s")) }
        fun trackLabel(type: String, index: Int, label: String) {
            if (label.isBlank()) return
            addAll(listOf("-metadata:s:$type:$index", "title=$label"))
            if (label.matches(Regex("[a-zA-Z]{2,3}"))) {
                addAll(listOf("-metadata:s:$type:$index", "language=${label.lowercase()}"))
            }
        }
        audio.forEachIndexed { index, (_, label) -> trackLabel("a", index + muxedAudio, label) }
        subtitles.forEachIndexed { index, (_, label) -> trackLabel("s", index + muxedSubtitles, label) }
        // Preserve all source streams; no transcoding in the accelerated route.
        addAll(listOf("-f", "matroska", "-c", "copy", output, "-y"))
    }.toTypedArray()
}

/** Media3 validates VOD playlists; their original tags (including discontinuities) are retained.
 * Each downloaded resource is verified before being published into a local-only playlist.
 */
class HlsVideoTransfer(
    private val client: OkHttpClient,
    private val cacheDirectory: File,
    private val sourceId: Long,
    private val throttler: Throttler = Throttler(),
) {
    private data class Playlist(val url: String, val text: String, val parsed: HlsPlaylist)
    private data class Resource(val url: String, val file: File, val key: Boolean)

    suspend fun prepare(
        url: String,
        headers: Headers,
        audio: List<Pair<String, String>>,
        subtitles: List<Pair<String, String>>,
        preferredResolution: Int? = null,
        preferredBitrate: Int? = null,
        progress: (Long, Int, Int) -> Unit,
    ): PreparedHls {
        suspend fun playlist(address: String): Playlist = TransferCoordinator.transfer(sourceId) {
            response(Request.Builder().url(address).headers(headers).build()) { result ->
                val text = result.body.byteStream().use { input ->
                    val bytes = input.readBytesLimited(2 * 1024 * 1024)
                    String(bytes, Charsets.UTF_8)
                }
                val actualUrl = result.request.url.toString()
                val parsed = try {
                    HlsPlaylistParser().parse(Uri.parse(actualUrl), text.byteInputStream())
                } catch (
                    _: ParserException,
                ) {
                    throw UnsupportedHls()
                }
                Playlist(actualUrl, text, parsed)
            }
        }
        val first = playlist(url)
        val root: Playlist
        var audioTracks = audio
        var subtitleTracks = subtitles
        when (val parsed = first.parsed) {
            is HlsMediaPlaylist -> root = first
            is HlsMultivariantPlaylist -> {
                if (parsed.sessionKeyDrmInitData.isNotEmpty()) throw UnsupportedHls()
                val maxHeight = preferredResolution?.takeIf { it > 0 } ?: Int.MAX_VALUE
                val maxBitrate = preferredBitrate?.takeIf { it > 0 } ?: Int.MAX_VALUE
                val variants = parsed.variants.filter {
                    it.format.height <= maxHeight && it.format.bitrate <= maxBitrate
                }
                val variant = variants.maxByOrNull { it.format.bitrate } ?: throw UnsupportedHls()
                root = playlist(variant.url.toString())
                audioTracks = audio +
                    parsed.audios.filter { it.groupId == variant.audioGroupId && it.url != null }
                        .map { it.url.toString() to it.name }
                subtitleTracks = subtitles +
                    parsed.subtitles
                        .filter { it.groupId == variant.subtitleGroupId && it.url != null }
                        .map { it.url.toString() to it.name }
            }
            else -> throw UnsupportedHls()
        }
        val audioPlaylists = coroutineScope {
            audioTracks.distinctBy {
                it.first
            }.map { track -> async { playlist(track.first) to track.second } }.awaitAll()
        }
        // Non-playlist subtitle files retain the established FFmpeg route for now.
        val subtitlePlaylists = coroutineScope {
            subtitleTracks.distinctBy {
                it.first
            }.map { track -> async { playlist(track.first) to track.second } }.awaitAll()
        }
        val playlists = listOf(root) + audioPlaylists.map { it.first } + subtitlePlaylists.map { it.first }
        playlists.forEach {
            val media = it.parsed as? HlsMediaPlaylist ?: throw UnsupportedHls()
            if (!media.hasEndTag ||
                media.segments.isEmpty() ||
                media.protectionSchemes != null ||
                it.text.contains("#EXT-X-BYTERANGE") ||
                it.text.contains("BYTERANGE=") ||
                Regex("METHOD=(?!NONE|AES-128)[^,\\r\\n]+").containsMatchIn(it.text)
            ) {
                throw UnsupportedHls()
            }
        }
        val identity = MessageDigest.getInstance("SHA-256")
            .digest((headers.toString() + playlists.joinToString { it.url + it.text }).toByteArray()).hex()
        val directory = File(cacheDirectory, identity).also {
            if (!it.mkdirs() &&
                !it.isDirectory
            ) {
                throw VideoStorageException("Cache download non disponibile.")
            }
        }
        val resources = linkedMapOf<String, Resource>()
        val localPlaylists = playlists.mapIndexed { index, entry ->
            fun resource(reference: String, key: Boolean = false): String {
                val address = entry.url.toHttpUrlOrNull()?.resolve(reference)?.toString() ?: throw UnsupportedHls()
                val hash = MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).hex()
                val extension = address.substringBefore('?').substringAfterLast('.', "bin").takeIf {
                    it.matches(Regex("[a-zA-Z0-9]{1,6}"))
                } ?: "bin"
                val file = File(directory, "$hash.$extension")
                resources.getOrPut(address) { Resource(address, file, key) }
                return file.name
            }
            val rewritten = entry.text.lineSequence().map { line ->
                when {
                    line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-MAP:") -> {
                        URI_ATTRIBUTE.replace(line) { match ->
                            "URI=\"${resource(match.groupValues[1], line.startsWith("#EXT-X-KEY:"))}\""
                        }
                    }
                    line.isNotBlank() && !line.startsWith('#') -> resource(line.trim())
                    else -> line
                }
            }.joinToString("\n", postfix = "\n")
            File(directory, "playlist-$index.m3u8").also { it.writeText(rewritten) }
        }
        val bytes = AtomicLong()
        val finished = AtomicInteger()
        resources.values.toList().asFlow().flatMapMerge(4) { resource ->
            flow {
                TransferCoordinator.transfer(sourceId) {
                    downloadResource(resource, headers) { count ->
                        TransferCoordinator.bytes(sourceId, count.toLong())
                        progress(bytes.addAndGet(count.toLong()), finished.get(), resources.size)
                    }
                }
                progress(bytes.get(), finished.incrementAndGet(), resources.size)
                emit(resource)
            }
        }.collect()
        return PreparedHls(
            directory,
            localPlaylists.first(),
            audioPlaylists.mapIndexed { index, (_, label) -> localPlaylists[index + 1] to label },
            subtitlePlaylists.mapIndexed { index, (_, label) ->
                localPlaylists[index + 1 + audioPlaylists.size] to label
            },
            (root.parsed as HlsMediaPlaylist).durationUs,
        )
    }

    private suspend fun downloadResource(resource: Resource, headers: Headers, progress: (Int) -> Unit) {
        val stateFile = File(resource.file.path + ".state")
        val state = runCatching { Properties().apply { stateFile.inputStream().use { load(it) } } }.getOrNull()
        val valid = resource.file.isFile &&
            state?.getProperty("etag") != null &&
            state.getProperty("length") == resource.file.length().toString() &&
            state.getProperty("hash") == resource.file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
                digest.digest().hex()
            }
        val request = Request.Builder().url(resource.url).headers(headers).header("Accept-Encoding", "identity")
            .apply { if (valid) header("If-None-Match", state!!.getProperty("etag")) }.build()
        response(request) { result ->
            if (result.code == 304 &&
                valid &&
                (result.header("ETag") == null || result.header("ETag") == state?.getProperty("etag"))
            ) {
                return@response
            }
            if (result.code != 200) throw IOException("Unexpected HLS response")
            if (result.header("Content-Encoding")?.let { it != "identity" } == true) throw UnsupportedHls()
            val temp = File(resource.file.path + ".tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            try {
                temp.outputStream().use { output ->
                    throttler.source(result.body.source()).buffer().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (resource.key && written + n > 16) throw UnsupportedHls()
                            if (written % (8L * 1024 * 1024) == 0L &&
                                temp.parentFile!!.usableSpace < 32L * 1024 * 1024
                            ) {
                                throw VideoStorageException("Spazio insufficiente nella cache video.")
                            }
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            written += n
                            progress(n)
                        }
                    }
                    output.fd.sync()
                }
                if (written <= 0 ||
                    (result.body.contentLength() >= 0 && written != result.body.contentLength()) ||
                    (resource.key && written != 16L)
                ) {
                    throw IOException("Incomplete HLS resource")
                }
                if (!temp.renameTo(resource.file)) throw VideoStorageException("Impossibile salvare il segmento video.")
                val metadata = Properties().apply {
                    setProperty("length", written.toString())
                    setProperty("hash", digest.digest().hex())
                    result.header("ETag")?.takeIf {
                        it.startsWith('"') && it.endsWith('"')
                    }?.let { setProperty("etag", it) }
                }
                val stateTemp = File(stateFile.path + ".tmp")
                stateTemp.outputStream().use {
                    metadata.store(it, null)
                    it.fd.sync()
                }
                if (!stateTemp.renameTo(stateFile)) throw VideoStorageException("Impossibile salvare lo stato video.")
            } finally {
                temp.delete()
            }
        }
    }

    private suspend fun <T> response(request: Request, block: suspend (Response) -> T): T = coroutineScope {
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
                if (it.code == 429 || it.code in 500..599) TransferCoordinator.backoff(sourceId)
                if (!it.isSuccessful &&
                    it.code != 304
                ) {
                    throw VideoHttpException(it.code, retryAfterMillis(it.header("Retry-After")))
                }
                block(it)
            }
        } finally {
            cancellation.cancel()
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (output.size() + n > limit) throw UnsupportedHls()
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    companion object {
        private val URI_ATTRIBUTE = Regex("URI=\"([^\"]+)\"")
    }
}
