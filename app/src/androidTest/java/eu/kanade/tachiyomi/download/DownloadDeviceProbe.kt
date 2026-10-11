@file:Suppress("ktlint:standard:max-line-length") // Native arguments and machine-readable benchmark rows.

package eu.kanade.tachiyomi.download

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.anime.HlsVideoTransfer
import eu.kanade.tachiyomi.data.download.anime.NativeVideoOutput
import eu.kanade.tachiyomi.data.download.anime.ParallelVideoTransfer
import eu.kanade.tachiyomi.data.download.anime.PreparedHls
import eu.kanade.tachiyomi.data.download.anime.ResumableVideoTransfer
import eu.kanade.tachiyomi.data.download.anime.UriPartialVideoStore
import eu.kanade.tachiyomi.data.download.transfer.DownloadPlaybackGate
import eu.kanade.tachiyomi.data.download.transfer.MangaImageTransfer
import eu.kanade.tachiyomi.data.download.transfer.TransferCoordinator
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import mihon.core.archive.ZipWriter
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.Throttler
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Opt-in physical-device probe. This runner and fixtures never ship in the application APK. */
class DownloadDeviceProbe : Instrumentation() {
    private var cleanup = false
    private var cleanupHls: String? = null
    private var checksOnly = false
    private var storedHls = false
    private var nativeFd = false
    override fun onCreate(arguments: Bundle?) {
        cleanup = arguments?.getString("cleanup") == "true"
        cleanupHls = arguments?.getString("cleanupHls")
        checksOnly = arguments?.getString("checksOnly") == "true"
        storedHls = arguments?.getString("storedHls") == "true"
        nativeFd = arguments?.getString("nativeFd") == "true"
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            cleanupHls?.let { key ->
                require(key.matches(Regex("[a-f0-9]{64}")))
                check(File(targetContext.cacheDir, "video-transfers/$key").deleteRecursively())
                results.putString("stream", "Selected probe HLS cache removed")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (cleanup) {
                check(File(targetContext.cacheDir, "download-device-probe").deleteRecursively())
                results.putString("stream", "Probe files removed")
                finish(Activity.RESULT_OK, results)
                return
            }
            runBlocking(Dispatchers.IO) {
                if (nativeFd) {
                    checkNativeFd()
                } else if (storedHls) {
                    checkStoredHls()
                } else {
                    probe()
                }
            }
            results.putString("stream", "Download device probe passed")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            Log.e(TAG, "Probe failed", error)
            results.putString("stream", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun checkNativeFd() {
        val sample = File(targetContext.cacheDir, "download-device-probe/encrypted.mkv")
        check(sample.length() > 0)
        Log.i(TAG, "NATIVE_PROTOCOLS ${FFmpegKit.execute("-protocols").output}")
        val grant = targetContext.contentResolver.persistedUriPermissions.first {
            it.isWritePermission && android.provider.DocumentsContract.isTreeUri(it.uri)
        }
        val tree = UniFile.fromUri(targetContext, grant.uri)!!
        val directory = tree.createDirectory("nyanime-fd-probe-${System.currentTimeMillis()}")!!
        try {
            val saved = directory.createFile("probe.mkv")!!
            targetContext.contentResolver.openFileDescriptor(saved.uri, "rwt")!!.use { descriptor ->
                val session = FFmpegKit.executeWithArguments(
                    arrayOf("-i", sample.path, "-map", "0", "-c", "copy", "-f", "matroska", "-fd", descriptor.fd.toString(), "fd:", "-y"),
                )
                Log.i(TAG, "NATIVE_FD_WRITE code=${session.returnCode} output=${session.output.takeLast(2000)}")
                check(session.returnCode.isValueSuccess)
            }
            targetContext.contentResolver.openFileDescriptor(saved.uri, "r")!!.use { descriptor ->
                val probe = FFprobeKit.executeWithArguments(
                    arrayOf("-v", "error", "-show_streams", "-show_format", "-of", "json", "-fd", descriptor.fd.toString(), "-i", "fd:"),
                )
                Log.i(TAG, "NATIVE_FD_READ code=${probe.returnCode} output=${probe.output.takeLast(2000)}")
                check(probe.returnCode.isValueSuccess && saved.length() > 0)
            }
        } finally {
            check(directory.delete())
        }
    }

    private fun checkStoredHls() {
        val directory = File(targetContext.cacheDir, "video-transfers").listFiles().orEmpty()
            .firstOrNull { File(it, "playlist-0.m3u8").isFile } ?: error("No stored HLS")
        val input = File(directory, "playlist-0.m3u8")
        val arguments =
            arrayOf("-v", "error", "-protocol_whitelist", "file,crypto,data,saf", "-allowed_extensions", "ALL", "-show_streams", "-of", "json", "-i", input.path)
        val probe = FFprobeKit.executeWithArguments(arguments)
        Log.i(TAG, "STORED_HLS_PROBE code=${probe.returnCode} output=${probe.output.takeLast(5000)}")
        val output = File(targetContext.cacheDir, "download-device-probe/stored-output.mkv").also {
            it.parentFile!!.mkdirs()
        }
        val prepared = PreparedHls(directory, input, emptyList(), emptyList(), 0)
        val session = FFmpegKit.executeWithArguments(prepared.arguments(output.path))
        Log.i(TAG, "STORED_HLS_REMUX code=${session.returnCode} output=${session.output.takeLast(5000)}")
        check(session.returnCode.isValueSuccess) { session.output.takeLast(5000) }
        val media = FFprobeKit.getMediaInformation(output.path).mediaInformation ?: error("Invalid stored HLS output")
        Log.i(TAG, "STORED_HLS_OFFLINE duration=${media.duration} streams=${media.streams.size}")
        targetContext.contentResolver.persistedUriPermissions.filter { it.isWritePermission }.forEach { grant ->
            val tree = UniFile.fromUri(targetContext, grant.uri) ?: return@forEach
            val folder = runCatching { tree.createDirectory("nyanime-hls-probe") }.getOrNull() ?: return@forEach
            val destination = folder.createFile("saved.mkv") ?: error("Cannot create HLS probe output")
            try {
                NativeVideoOutput(targetContext, destination.uri).use { native ->
                    val stored = FFmpegKit.executeWithArguments(native.arguments(prepared.arguments(native.path)))
                    Log.i(TAG, "STORED_HLS_SAF code=${stored.returnCode} output=${stored.output.takeLast(2000)}")
                    check(stored.returnCode.isValueSuccess)
                }
                check(destination.length() > 0)
            } finally {
                folder.delete()
            }
            return
        }
        error("No writable SAF tree")
    }

    private suspend fun probe() {
        val root = File(targetContext.cacheDir, "download-device-probe").also { it.mkdirs() }
        val fixtures = File(root, "fixtures").also { it.mkdirs() }
        val headers = Headers.Builder().add("X-Probe", "generic").build()
        val direct = File(fixtures, "video.bin")
        val random = Random(7)
        direct.outputStream().use { output ->
            repeat(256) {
                val buffer = ByteArray(64 * 1024)
                random.nextBytes(buffer)
                output.write(buffer)
            }
        }
        val directHash = hash(direct)
        val image = File(fixtures, "page.jpg")
        val pixels = IntArray(900 * 1200) { random.nextInt() or (0xff shl 24) }
        Bitmap.createBitmap(pixels, 900, 1200, Bitmap.Config.ARGB_8888).let { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
        }
        val imageHash = hash(image)
        fun ffmpeg(vararg args: String) {
            val session = FFmpegKit.executeWithArguments(args)
            check(session.returnCode.isValueSuccess) { session.output }
        }
        val videoPlaylist = File(fixtures, "video.m3u8")
        val rawVideo = File(fixtures, "frames.rgba")
        rawVideo.outputStream().use { output ->
            repeat(12) {
                val frame = ByteArray(480 * 270 * 4)
                random.nextBytes(frame)
                for (pixel in 3 until frame.size step 4) frame[pixel] = 255.toByte()
                output.write(frame)
            }
        }
        ffmpeg(
            "-stream_loop", "-1", "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", "480x270", "-framerate", "24", "-i", rawVideo.path, "-t", "20", "-an", "-c:v", "mpeg2video",
            "-b:v", "1500k", "-g", "48", "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", videoPlaylist.path, "-y",
        )
        val audioPlaylist = File(fixtures, "audio.m3u8")
        val rawAudio = File(fixtures, "audio.pcm")
        rawAudio.outputStream().use { output ->
            repeat(48_000) { index ->
                val sample = (kotlin.math.sin(index * 2.0 * Math.PI * 440 / 48_000) * 16_000).toInt()
                output.write(sample and 255)
                output.write((sample shr 8) and 255)
            }
        }
        ffmpeg(
            "-stream_loop", "-1", "-f", "s16le", "-ar", "48000", "-ac", "1", "-i", rawAudio.path, "-t", "20", "-vn", "-c:a", "aac",
            "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", audioPlaylist.path, "-y",
        )
        File(fixtures, "caption.vtt").writeText("WEBVTT\n\n00:00.000 --> 00:20.000\nGeneric offline caption\n")
        File(
            fixtures,
            "sub.m3u8",
        ).writeText(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:20\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:20.0,\ncaption.vtt\n#EXT-X-ENDLIST\n",
        )
        val client = OkHttpClient.Builder().callTimeout(
            0,
            TimeUnit.MILLISECONDS,
        ).readTimeout(60, TimeUnit.SECONDS).build()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath.removePrefix("/")
                    val file = if (path.startsWith("page-")) image else File(fixtures, path)
                    if (!file.isFile ||
                        file.canonicalFile.parentFile != fixtures.canonicalFile
                    ) {
                        return MockResponse.Builder().code(404).build()
                    }
                    val etag = "\"${file.name}-${file.length()}\""
                    if (request.headers["If-None-Match"] == etag) return MockResponse.Builder().code(304).build()
                    val range = request.headers["Range"]?.removePrefix("bytes=")?.split('-')
                    val from = range?.first()?.toLongOrNull() ?: 0L
                    val to = minOf(range?.last()?.toLongOrNull() ?: (file.length() - 1), file.length() - 1)
                    val bytes = java.io.RandomAccessFile(file, "r").use { input ->
                        input.seek(from)
                        ByteArray((to - from + 1).toInt()).also { input.readFully(it) }
                    }
                    return MockResponse.Builder().code(if (range == null) 200 else 206).addHeader("ETag", etag)
                        .apply { if (range != null) addHeader("Content-Range", "bytes $from-$to/${file.length()}") }
                        .headersDelay(80, TimeUnit.MILLISECONDS).throttleBody(128 * 1024, 250, TimeUnit.MILLISECONDS)
                        .body(Buffer().write(bytes)).build()
                }
            }
            server.start()
            val address = server.url("/").toString()
            repeat(if (checksOnly) 0 else 3) { trial ->
                for (accelerated in listOf(false, true)) {
                    val destination = File(root, "direct-$trial-$accelerated").also { it.mkdirs() }
                    val part = File(destination, "partial").also { it.createNewFile() }
                    val state = File(destination, "state").also { it.createNewFile() }
                    val store = UriPartialVideoStore(targetContext, UniFile.fromFile(part)!!, UniFile.fromFile(state)!!)
                    val start = SystemClock.elapsedRealtime()
                    if (accelerated) {
                        check(
                            ParallelVideoTransfer(
                                client,
                                store,
                                900L + trial,
                            ).tryDownload(address + "video.bin", headers) {
                                    _,
                                    _,
                                ->
                            },
                        )
                    } else {
                        ResumableVideoTransfer(client, store).download(address + "video.bin", headers) { _, _ -> }
                    }
                    check(hash(part) == directHash)
                    metric("http", accelerated, trial, SystemClock.elapsedRealtime() - start, 0, 0, part.length())
                }
            }
            repeat(if (checksOnly) 0 else 3) { trial ->
                for (accelerated in listOf(false, true)) {
                    val output = File(root, "hls-$trial-$accelerated.mkv")
                    val start = SystemClock.elapsedRealtime()
                    val arguments: Array<String>
                    val preparedAt: Long
                    val transferredAt: Long
                    if (accelerated) {
                        val prepared = HlsVideoTransfer(client, File(root, "hls-cache-$trial"), 950L + trial).prepare(
                            address + "video.m3u8",
                            headers,
                            listOf(address + "audio.m3u8" to "test"),
                            listOf(address + "sub.m3u8" to "test"),
                        ) { _, _, _ -> }
                        transferredAt = SystemClock.elapsedRealtime()
                        arguments = prepared.arguments(output.path)
                        preparedAt = start
                    } else {
                        FFprobeKit.executeWithArguments(
                            arrayOf(
                                "-v",
                                "quiet",
                                "-show_entries",
                                "format=duration",
                                address + "video.m3u8",
                            ),
                        )
                        preparedAt = SystemClock.elapsedRealtime()
                        arguments =
                            arrayOf(
                                "-i", address + "video.m3u8", "-i", address + "audio.m3u8", "-i",
                                address + "sub.m3u8",
                                "-map", "0:v", "-map", "1:a", "-map", "2:s", "-c", "copy", "-f", "matroska", output.path, "-y",
                            )
                        transferredAt = preparedAt
                    }
                    ffmpeg(*arguments)
                    val ended = SystemClock.elapsedRealtime()
                    val streams =
                        FFprobeKit.getMediaInformation(output.path).mediaInformation?.streams
                            ?: error("Unopenable offline video")
                    check(streams.map { it.type }.toSet().containsAll(setOf("video", "audio", "subtitle")))
                    metric(
                        "hls",
                        accelerated,
                        trial,
                        ended - start,
                        preparedAt - start,
                        ended - transferredAt,
                        output.length(),
                    )
                }
            }
            repeat(if (checksOnly) 0 else 3) { trial ->
                for (accelerated in listOf(false, true)) {
                    val pages = File(root, "pages-$trial-$accelerated").also { it.mkdirs() }
                    val pageDir = UniFile.fromFile(pages)!!
                    val start = SystemClock.elapsedRealtime()
                    val index = if (accelerated) pages.listFiles().orEmpty().associateBy { it.name } else emptyMap()
                    (0 until 24).asFlow().flatMapMerge(if (accelerated) 4 else 2) { page ->
                        flow {
                            val file = File(pages, "%03d.jpg".format(page))
                            val existing = if (accelerated) {
                                index[file.name]
                            } else {
                                pageDir.listFiles()?.firstOrNull {
                                    it.name ==
                                        file.name
                                }?.let { file }
                            }
                            if (existing == null) {
                                if (accelerated) {
                                    MangaImageTransfer(1000L + trial, Throttler()).download(
                                        {
                                            client.newCall(
                                                Request.Builder().url(address + "page-$page.jpg").build(),
                                            ).awaitSuccess()
                                        },
                                        { file.outputStream() },
                                        { pages.usableSpace },
                                        {},
                                        {},
                                    )
                                } else {
                                    client.newCall(
                                        Request.Builder().url(address + "page-$page.jpg").build(),
                                    ).awaitSuccess().use { response ->
                                        response.body.byteStream().use { input ->
                                            file.outputStream().use { input.copyTo(it) }
                                        }
                                    }
                                }
                            }
                            check(hash(file) == imageHash)
                            emit(page)
                        }
                    }.collect()
                    val transferred = SystemClock.elapsedRealtime()
                    val cbz = File(root, "chapter-$trial-$accelerated.cbz").also { it.createNewFile() }
                    ZipWriter(
                        targetContext,
                        UniFile.fromFile(cbz)!!,
                        if (accelerated) 64 * 1024 else 8192,
                    ).use { writer ->
                        pageDir.listFiles()!!.sortedBy { it.name }.forEach { writer.write(it) }
                    }
                    ZipFile(cbz).use { zip ->
                        check(zip.size() == 24)
                        zip.entries().asSequence().forEach { entry ->
                            check(zip.getInputStream(entry).use { hash(it.readBytes()) } == imageHash)
                        }
                    }
                    metric(
                        "manga",
                        accelerated,
                        trial,
                        SystemClock.elapsedRealtime() - start,
                        0,
                        SystemClock.elapsedRealtime() - transferred,
                        cbz.length(),
                    )
                }
            }
            if (checksOnly) {
                val checkedHls = verifyHls(fixtures, root, address, client, headers)
                val directory = targetContext.contentResolver.persistedUriPermissions.asSequence()
                    .filter { it.isWritePermission && android.provider.DocumentsContract.isTreeUri(it.uri) }
                    .mapNotNull { grant ->
                        runCatching {
                            UniFile.fromUri(targetContext, grant.uri)
                                ?.createDirectory("nyanime-download-probe-${System.currentTimeMillis()}")
                        }.getOrNull()
                    }.firstOrNull()
                if (directory != null) {
                    try {
                        val part = directory.createFile("probe.partial")!!
                        val state = directory.createFile("probe.state")!!
                        val store = UriPartialVideoStore(targetContext, part, state)
                        val accelerated = ParallelVideoTransfer(client, store, 1201)
                            .tryDownload(address + "video.bin", headers) { _, _ -> }
                        if (!accelerated) {
                            ResumableVideoTransfer(
                                client,
                                store,
                            ).download(address + "video.bin", headers) {
                                    _,
                                    _,
                                ->
                            }
                        }
                        check(store.digest(0, direct.length()) == directHash)
                        Log.i(TAG, "CHECK saf-integrity passed accelerated=$accelerated")
                        val saved = directory.createFile("probe.mkv")!!
                        NativeVideoOutput(targetContext, saved.uri).use { native ->
                            val remux = FFmpegKit.executeWithArguments(
                                native.arguments(checkedHls.arguments(native.path)),
                            )
                            check(remux.returnCode.isValueSuccess) { remux.output }
                        }
                        val copied = File(root, "checked-saf.mkv")
                        saved.openInputStream().use { input -> copied.outputStream().use { input.copyTo(it) } }
                        val media =
                            FFprobeKit.getMediaInformation(copied.path).mediaInformation ?: error("Invalid SAF output")
                        check(media.streams.any { it.type == "video" } && media.duration.toDouble() >= 19)
                        Log.i(TAG, "CHECK saf-hls-remux-offline passed")
                    } finally {
                        check(directory.delete())
                    }
                } else {
                    Log.i(TAG, "CHECK saf skipped: no accessible writable tree grant")
                }
            }
            DownloadPlaybackGate.enter()
            val suspended = kotlinx.coroutines.coroutineScope {
                val pending = async { TransferCoordinator.transfer(1050) { true } }
                delay(100)
                check(!pending.isCompleted)
                DownloadPlaybackGate.leave()
                pending.await()
            }
            check(suspended)
        }
        // Retain only probe outputs needed for manual offline playback checks, never user files.
        Log.i(TAG, "OFFLINE_OUTPUTS ${root.absolutePath}")
    }

    private suspend fun verifyHls(fixtures: File, root: File, address: String, client: OkHttpClient, headers: Headers): PreparedHls {
        val key = ByteArray(16) { (it * 7).toByte() }
        val iv = ByteArray(16) { it.toByte() }
        File(fixtures, "key.bin").writeBytes(key)
        val text = File(fixtures, "video.m3u8").readText()
        var segment = 0
        val encrypted = text.lineSequence().map { line ->
            if (line.isNotBlank() && !line.startsWith('#')) {
                val file = File(fixtures, line)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                }
                val name = "encrypted-${segment++}.ts"
                File(fixtures, name).writeBytes(cipher.doFinal(file.readBytes()))
                (if (segment == 2) "#EXT-X-DISCONTINUITY\n" else "") + name
            } else {
                line
            }
        }.joinToString("\n").replace(
            "#EXTM3U\n",
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x${iv.joinToString("") { "%02x".format(it) }}\n",
        )
        File(fixtures, "encrypted.m3u8").writeText(encrypted)
        val transfer = HlsVideoTransfer(client, File(root, "checks-hls"), 1200)
        val prepared = transfer.prepare(address + "encrypted.m3u8", headers, emptyList(), emptyList()) { _, _, _ -> }
        val output = File(root, "encrypted.mkv")
        val session = FFmpegKit.executeWithArguments(prepared.arguments(output.path))
        check(session.returnCode.isValueSuccess) { session.output }
        val media = FFprobeKit.getMediaInformation(output.path).mediaInformation ?: error("Invalid encrypted output")
        check(media.streams.any { it.type == "video" })
        check(media.duration.toDouble() >= 19)
        Log.i(TAG, "CHECK hls-aes128-discontinuity-offline passed")
        var transferred = 0L
        transfer.prepare(address + "encrypted.m3u8", headers, emptyList(), emptyList()) { bytes, _, _ ->
            transferred =
                bytes
        }
        check(transferred == 0L) { "Validated HLS resources were transferred twice" }
        val resource = prepared.directory.listFiles()!!.first { it.extension == "ts" }
        java.io.RandomAccessFile(resource, "rw").use {
            it.seek(0)
            it.write(42)
        }
        transfer.prepare(address + "encrypted.m3u8", headers, emptyList(), emptyList()) { bytes, _, _ ->
            transferred =
                bytes
        }
        check(transferred == resource.length())
        Log.i(TAG, "CHECK hls-resume-hash-conditional-etag passed")
        return prepared
    }

    private fun metric(path: String, accelerated: Boolean, trial: Int, total: Long, prepare: Long, finalize: Long, bytes: Long) {
        val battery = targetContext.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        Log.i(
            TAG,
            "METRIC path=$path accelerated=$accelerated trial=$trial totalMs=$total prepareMs=$prepare finalizeMs=$finalize bytes=$bytes pssKb=${Debug.getPss()} temperatureTenths=${battery?.getIntExtra(
                "temperature",
                -1,
            )}",
        )
    }
    private fun hash(file: File) = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun hash(
        bytes: ByteArray,
    ) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        private const val TAG = "DownloadProbe"
    }
}
