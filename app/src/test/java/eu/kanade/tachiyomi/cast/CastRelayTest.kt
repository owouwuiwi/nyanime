package eu.kanade.tachiyomi.cast

import eu.kanade.tachiyomi.data.cast.CastRelay
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CastRelayTest {
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private fun get(url: String, range: String? = null) = client.newCall(
        Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build(),
    ).execute()

    @Test
    fun receiverGetsRewrittenHlsButNeverSourceHeadersOrAnOpenProxy() {
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        val origin = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                headers.add(session.headers)
                // Deliberately leave NanoHTTPD's legacy HEAD behavior to exercise isolated upstream probes.
                return if (session.uri == "/redirect") {
                    newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
                        addHeader("Location", "/nested/master.m3u8")
                    }
                } else if (session.uri.endsWith(".m3u8")) {
                    newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", "#EXTM3U\n#EXTINF:5,\nseg.ts")
                } else {
                    newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp2t", "segment").apply {
                        addHeader("Content-Range", "bytes 3-9/10")
                    }
                }
            }
        }
        origin.start()
        val relay = CastRelay(
            "127.0.0.1",
            client,
            Headers.Builder().add("Referer", "https://source.example/").add("Cookie", "session=private").build(),
        )
        relay.start()
        try {
            val (url, mime) = relay.prepare("http://127.0.0.1:${origin.listeningPort}/redirect")
            assertEquals("application/x-mpegURL", mime)
            val segmentUrl = get(url).use { response ->
                assertEquals(200, response.code)
                assertEquals("*", response.header("Access-Control-Allow-Origin"))
                assertFalse(response.headers.toString().contains("session=private"))
                response.body.string().lineSequence().last()
            }
            assertTrue(segmentUrl.startsWith("http://127.0.0.1:${relay.listeningPort}/cast/"))
            client.newCall(Request.Builder().url(segmentUrl).head().build()).execute().use {
                assertEquals("7", it.header("Content-Length"))
                assertEquals("", it.body.string())
            }
            get(segmentUrl, "bytes=3-9").use {
                assertEquals(206, it.code)
                assertEquals("bytes 3-9/10", it.header("Content-Range"))
                assertEquals("segment", it.body.string())
            }
            assertTrue(headers.all { it["referer"] == "https://source.example/" && it["cookie"] == "session=private" })
            assertEquals("bytes=3-9", headers.last()["range"])
            val count = headers.size
            get("http://127.0.0.1:${relay.listeningPort}/cast/unknown?url=http://private/").use {
                assertEquals(404, it.code)
            }
            assertEquals(count, headers.size)
        } finally {
            relay.stop()
            origin.stop()
        }
    }

    @Test
    fun downloadedContentSupportsSeekingSuffixesAndRejectsUnsatisfiableRanges() {
        val bytes = "0123456789".toByteArray()
        val relay = CastRelay("127.0.0.1", client, Headers.Builder().build()) {
            CastRelay.LocalResource(ByteArrayInputStream(bytes), bytes.size.toLong(), "video/mp4")
        }
        relay.start()
        try {
            val url = relay.register("content://selected-episode/video")
            get(url, "bytes=3-6").use {
                assertEquals(206, it.code)
                assertEquals("3456", it.body.string())
                assertEquals("bytes 3-6/10", it.header("Content-Range"))
            }
            get(url, "bytes=-2").use { assertEquals("89", it.body.string()) }
            get(url, "bytes=50-").use {
                assertEquals(416, it.code)
                assertEquals("bytes */10", it.header("Content-Range"))
            }
            get(url).use { assertEquals("0123456789", it.body.string()) }
            client.newCall(Request.Builder().url(url).head().build()).execute().use {
                assertEquals("10", it.header("Content-Length"))
                assertEquals("", it.body.string())
            }
            get(url).use { assertEquals("0123456789", it.body.string()) }
        } finally {
            relay.stop()
        }
        assertThrows(Exception::class.java) { relay.register("https://example.test/reuse-after-stop") }
    }

    @Test
    fun slowReceiversKeepTheirSlotUntilTheBodyClosesAndStopClosesAllStreams() {
        val started = CountDownLatch(8)
        val closed = AtomicInteger()
        val relay = CastRelay("127.0.0.1", client, Headers.Builder().build()) {
            val gate = CountDownLatch(1)
            val stream = object : InputStream() {
                override fun read(): Int {
                    started.countDown()
                    gate.await(15, TimeUnit.SECONDS)
                    return -1
                }
                override fun close() {
                    closed.incrementAndGet()
                    gate.countDown()
                }
            }
            CastRelay.LocalResource(stream, 10, "video/mp4")
        }
        relay.start()
        val responses = mutableListOf<okhttp3.Response>()
        try {
            val url = relay.register("content://selected/video")
            repeat(8) { responses += get(url) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            get(url).use { assertEquals(503, it.code) }
            relay.stop()
            assertEquals(8, closed.get())
        } finally {
            relay.stop()
            responses.forEach { it.close() }
        }
    }
}
