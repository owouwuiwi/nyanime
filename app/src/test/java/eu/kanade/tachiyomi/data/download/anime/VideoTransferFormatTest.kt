package eu.kanade.tachiyomi.data.download.anime

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VideoTransferFormatTest {
    @Test fun signedOpaqueUrlsUseContainerBytesAndPreserveHeaders() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(128 * 1024).apply { "ftypisom".toByteArray().copyInto(this, 4) }
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            server.start()
            assertEquals(
                VideoTransferFormat.MP4,
                probeVideoTransferFormat(
                    OkHttpClient(),
                    9701,
                    server.url("/media?ticket=opaque").toString(),
                    Headers.headersOf("Authorization", "probe"),
                ),
            )
            val request = server.takeRequest()
            assertEquals("bytes=0-63", request.headers["Range"])
            assertEquals("probe", request.headers["Authorization"])
        }
    }

    @Test fun playlistsAreRecognizedEvenWithGenericMime() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("\uFEFF#EXTM3U\n#EXTINF:2,\npart.ts\n#EXT-X-ENDLIST\n").build())
            server.start()
            assertEquals(
                VideoTransferFormat.HLS,
                probeVideoTransferFormat(OkHttpClient(), 9702, server.url("/media").toString(), Headers.EMPTY),
            )
        }
    }

    @Test fun mimeAloneDoesNotAcceptHtmlAsAVideo() {
        assertEquals(
            VideoTransferFormat.OTHER,
            VideoTransferFormat.fromBytes("<html>Denied</html>".toByteArray(), "video/mp4"),
        )
        assertEquals(
            VideoTransferFormat.MKV,
            VideoTransferFormat.fromBytes(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()), null),
        )
    }

    @Test fun rejectedRangeUsesExistingRouteButExpiredAuthorizationIsReported() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(416).build())
            server.enqueue(MockResponse.Builder().code(403).build())
            server.start()
            assertEquals(
                VideoTransferFormat.OTHER,
                runBlocking {
                    probeVideoTransferFormat(OkHttpClient(), 9703, server.url("/media").toString(), Headers.EMPTY)
                },
            )
            val error =
                assertThrows(VideoHttpException::class.java) {
                    runBlocking {
                        probeVideoTransferFormat(OkHttpClient(), 9703, server.url("/media").toString(), Headers.EMPTY)
                    }
                }
            assertEquals(403, error.status)
        }
    }
}
