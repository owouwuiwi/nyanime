package eu.kanade.tachiyomi.data.download.anime

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

class ResumableVideoTransferTest {
    private val url = "https://example.test/video.mp4"
    private val headers = Headers.Builder().build()
    private class Store : PartialVideoStore {
        var bytes = byteArrayOf()
        override var metadata: PartialVideoMetadata? = null
        override fun size() = bytes.size.toLong()
        override fun open(offset: Long): OutputStream {
            val stream = ByteArrayOutputStream()
            stream.write(bytes.copyOf(offset.toInt()))
            return object : OutputStream() {
                override fun write(value: Int) {
                    stream.write(value)
                    bytes = stream.toByteArray()
                }
                override fun write(value: ByteArray, offset: Int, length: Int) {
                    stream.write(value, offset, length)
                    bytes = stream.toByteArray()
                }
            }
        }
    }
    private fun response(
        request: Request,
        text: String,
        length: Long = text.length.toLong(),
        code: Int = 200,
        etag: String? = "\"v1\"",
        range: String? = null,
    ): Response {
        val body = object : ResponseBody() {
            private val buffer = Buffer().writeUtf8(text)
            override fun contentType() = null
            override fun contentLength() = length
            override fun source() = buffer
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body).apply {
                etag?.let { header("ETag", it) }
                range?.let { header("Content-Range", it) }
            }.build()
    }
    private fun client(vararg answers: (Request) -> Response): OkHttpClient {
        val queue = ConcurrentLinkedQueue(answers.toList())
        return OkHttpClient.Builder().addInterceptor { queue.remove()(it.request()) }.build()
    }

    @Test fun resumesOnlyTheMissingBytesAfterTruncation() = runBlocking {
        val store = Store()
        val http = client(
            { response(it, "abc", 6) },
            {
                assertEquals("bytes=3-", it.header("Range"))
                assertEquals("\"v1\"", it.header("If-Range"))
                response(it, "def", code = 206, range = "bytes 3-5/6")
            },
        )
        val transfer = ResumableVideoTransfer(http, store)
        assertThrows(IOException::class.java) { runBlocking { transfer.download(url, headers) { _, _ -> } } }
        transfer.download(url, headers) { _, _ -> }
        assertEquals("abcdef", store.bytes.toString(Charsets.UTF_8))
    }

    @Test fun serverIgnoringRangeReplacesPartialInsteadOfAppending() = runBlocking {
        val store = Store()
        val transfer =
            ResumableVideoTransfer(client({ response(it, "abc", 6) }, { response(it, "NEW", etag = "\"v2\"") }), store)
        runCatching { transfer.download(url, headers) { _, _ -> } }
        transfer.download(url, headers) { _, _ -> }
        assertEquals("NEW", store.bytes.toString(Charsets.UTF_8))
    }

    @Test fun changedValidatorOrWrongOffsetCannotCorruptPartial() = runBlocking {
        for ((etag, range) in listOf(
            "\"v2\"" to "bytes 3-5/6",
            "\"v1\"" to "bytes 2-5/6",
            "\"v1\"" to "bytes 3-6/7",
            "\"v1\"" to "malformed",
            "\"v1\"" to "bytes 3-99999999999999999999999/6",
        )) {
            val store = Store()
            val transfer = ResumableVideoTransfer(
                client(
                    { response(it, "abc", 6) },
                    { response(it, "def", code = 206, etag = etag, range = range) },
                    {
                        assertNull(it.header("Range"))
                        response(it, "whole")
                    },
                ),
                store,
            )
            runCatching { transfer.download(url, headers) { _, _ -> } }
            assertThrows(IOException::class.java) { runBlocking { transfer.download(url, headers) { _, _ -> } } }
            assertEquals("abc", store.bytes.toString(Charsets.UTF_8))
            assertNull(store.metadata)
            transfer.download(url, headers) { _, _ -> }
            assertEquals("whole", store.bytes.toString(Charsets.UTF_8))
        }
    }

    @Test fun cancellationPreservesOnlyWrittenBytesForTheNextAttempt() = runBlocking {
        val store = Store()
        val payload = "a".repeat(100_000)
        val transfer = ResumableVideoTransfer(
            client(
                { response(it, payload) },
                {
                    val offset = store.size().toInt()
                    assertEquals("bytes=$offset-", it.header("Range"))
                    response(it, payload.substring(offset), code = 206, range = "bytes $offset-99999/100000")
                },
            ),
            store,
        )
        val job = launch {
            transfer.download(url, headers) { _, _ -> cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(store.size() in 1 until payload.length.toLong())
        transfer.download(url, headers) { _, _ -> }
        assertEquals(payload, store.bytes.toString(Charsets.UTF_8))
    }

    @Test fun changedUrlAndMissingStrongValidatorStartFromZero() = runBlocking {
        for (etag in listOf<String?>(null, "W/\"weak\"")) {
            val store = Store()
            val transfer = ResumableVideoTransfer(
                client(
                    { response(it, "abc", 6, etag = etag) },
                    {
                        assertNull(it.header("Range"))
                        response(it, "whole")
                    },
                ),
                store,
            )
            runCatching { transfer.download(url, headers) { _, _ -> } }
            transfer.download(url, headers) { _, _ -> }
            assertEquals("whole", store.bytes.toString(Charsets.UTF_8))
        }
        val store = Store()
        val transfer = ResumableVideoTransfer(
            client(
                { response(it, "abc", 6) },
                {
                    assertNull(it.header("Range"))
                    response(it, "fresh")
                },
            ),
            store,
        )
        runCatching { transfer.download(url, headers) { _, _ -> } }
        transfer.download("$url?renewed", headers) { _, _ -> }
        assertEquals("fresh", store.bytes.toString(Charsets.UTF_8))
    }

    @Test fun disguisedManifestOrErrorPageLeavesTheFfmpegRouteAvailable() {
        for (body in listOf(
            "#EXTM3U\n#EXT-X-VERSION:3",
            "<MPD>test</MPD>",
            "  <html>expired</html>",
            "{\"error\":true}",
        )) {
            val store = Store()
            val transfer = ResumableVideoTransfer(client({ response(it, body) }), store)
            assertThrows(UnsupportedDirectVideo::class.java) {
                runBlocking { transfer.download(url, headers) { _, _ -> } }
            }
            assertTrue(store.bytes.isEmpty())
        }
    }

    @Test fun restartingAccountsForSpaceReclaimedFromTheOldPartial() = runBlocking {
        val store = Store()
        var free = Long.MAX_VALUE
        val transfer = ResumableVideoTransfer(
            client({ response(it, "abc", 6) }, { response(it, "whole!") }),
            store,
        ) { free }
        runCatching { transfer.download(url, headers) { _, _ -> } }
        free = 32L * 1024 * 1024 + 4
        transfer.download(url, headers) { _, _ -> }
        assertEquals("whole!", store.bytes.toString(Charsets.UTF_8))
    }

    @Test fun rejectsInsufficientStorageBeforeOpeningTheTarget() {
        val store = Store()
        val transfer = ResumableVideoTransfer(client({ response(it, "abc") }), store) { 16L }
        assertThrows(VideoStorageException::class.java) { runBlocking { transfer.download(url, headers) { _, _ -> } } }
        assertTrue(store.bytes.isEmpty())
    }

    @Test fun expiredLinksExposeStatusForExtensionRefresh() {
        val store = Store()
        val transfer = ResumableVideoTransfer(client({ response(it, "expired", code = 403) }), store)
        val error =
            assertThrows(VideoHttpException::class.java) { runBlocking { transfer.download(url, headers) { _, _ -> } } }
        assertEquals(403, error.status)
        assertTrue(store.bytes.isEmpty())
    }

    @Test fun interruptedCompletedPartRequiresServerConfirmation() = runBlocking {
        val store = Store()
        val transfer = ResumableVideoTransfer(
            client(
                { response(it, "abc") },
                { response(it, "", code = 416, range = "bytes */3") },
            ),
            store,
        )
        runCatching { transfer.download(url, headers) { _, _ -> throw IOException("interrupted") } }
        transfer.download(url, headers) { _, _ -> }
        assertEquals("abc", store.bytes.toString(Charsets.UTF_8))
    }
}
