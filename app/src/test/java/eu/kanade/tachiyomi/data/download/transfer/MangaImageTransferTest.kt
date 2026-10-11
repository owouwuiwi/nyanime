package eu.kanade.tachiyomi.data.download.transfer

import eu.kanade.tachiyomi.data.download.anime.VideoStorageException
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Throttler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class MangaImageTransferTest {
    @Test fun opaqueImageProviderRetainsRequestMethodAndHeadersAndClosesTheDestination() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("original page bytes").build())
            server.start()
            var closed = false
            val output = object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
            val client = OkHttpClient()
            MangaImageTransfer(1101, Throttler()).download(
                {
                    client.newCall(
                        Request.Builder().url(server.url("/image"))
                            .post("opaque image request".toRequestBody()).header("X-Extension", "kept").build(),
                    ).awaitSuccess()
                },
                { output },
                { -1L },
                {},
                {},
            )
            val request = server.takeRequest()
            assertTrue(request.requestLine.startsWith("POST "))
            assertEquals("kept", request.headers["X-Extension"])
            assertEquals("original page bytes", output.toString("UTF-8"))
            assertTrue(closed)
        }
    }

    @Test fun storageFailureIsNotReportedAsCompletedAndReleasesItsConnection() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("page").build())
            server.enqueue(MockResponse.Builder().body("next page").build())
            server.start()
            val transfer = MangaImageTransfer(1102, Throttler())
            val client = OkHttpClient()
            val response =
                suspend { client.newCall(Request.Builder().url(server.url("/image")).build()).awaitSuccess() }
            var finalized = false
            assertThrows(VideoStorageException::class.java) {
                runBlocking {
                    transfer.download(response, { throw IOException("Permission denied") }, { -1L }, {}, {
                        finalized =
                            true
                    })
                }
            }
            assertFalse(finalized)
            val output = ByteArrayOutputStream()
            transfer.download(response, { output }, { -1L }, {}, { finalized = true })
            assertTrue(finalized)
            assertEquals("next page", output.toString("UTF-8"))
        }
    }

    @Test fun emptyPagesCannotBeMarkedAsReady() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("").build())
            server.start()
            var finalized = false
            assertThrows(IOException::class.java) {
                runBlocking {
                    MangaImageTransfer(1103, Throttler()).download(
                        { OkHttpClient().newCall(Request.Builder().url(server.url("/image")).build()).awaitSuccess() },
                        { ByteArrayOutputStream() },
                        { -1L },
                        {},
                        { finalized = true },
                    )
                }
            }
            assertFalse(finalized)
        }
    }
}
