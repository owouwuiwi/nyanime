package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.coil.ArtworkHttpException
import eu.kanade.tachiyomi.data.coil.ArtworkRequestPolicy
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ArtworkRequestPolicyTest {
    @Test fun transientFailuresRetryOnceButPermanentFailuresAndCancellationDoNot() {
        for (error in listOf(
            IOException("Interrupted transfer"),
            ArtworkHttpException(408),
            ArtworkHttpException(503),
        )) {
            assertTrue(ArtworkRequestPolicy.shouldRetry(error, 0))
            assertFalse(ArtworkRequestPolicy.shouldRetry(error, 1))
            assertFalse(ArtworkRequestPolicy.shouldRetry(error, 2))
        }
        for (error in listOf(
            ArtworkHttpException(404),
            ArtworkHttpException(403),
            ArtworkHttpException(429),
            IllegalArgumentException("No cover"),
            CancellationException(),
        )) {
            assertFalse(ArtworkRequestPolicy.shouldRetry(error, 0))
        }
    }

    @Test fun homeLimitDoesNotChangeTheSourceClientOrItsShorterTimeout() {
        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build()
        val request = Request.Builder().url(
            "https://images.test/cover.jpg",
        ).header("Referer", "https://source.test/").build()
        val limited = ArtworkRequestPolicy.limit(client.newCall(request), ArtworkRequestPolicy.HOME_TIMEOUT_MILLIS)
        assertEquals(TimeUnit.SECONDS.toNanos(15), limited.timeout().timeoutNanos())
        assertEquals("https://source.test/", limited.request().header("Referer"))
        assertEquals(120_000, client.callTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toNanos(2), client.newCall(request).timeout().timeoutNanos())
        val shorter = client.newBuilder().callTimeout(2, TimeUnit.SECONDS).build().newCall(request)
        ArtworkRequestPolicy.limit(shorter, ArtworkRequestPolicy.HOME_TIMEOUT_MILLIS)
        assertEquals(TimeUnit.SECONDS.toNanos(2), shorter.timeout().timeoutNanos())
    }

    @Test fun anOptedInUnlimitedCallGetsABoundWhileOtherImagesKeepTheirConfiguration() {
        val client = OkHttpClient.Builder().callTimeout(0, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("https://images.test/cover.jpg").build()
        assertEquals(0L, ArtworkRequestPolicy.limit(client.newCall(request), 0).timeout().timeoutNanos())
        assertEquals(
            TimeUnit.SECONDS.toNanos(15),
            ArtworkRequestPolicy.limit(
                client.newCall(request),
                ArtworkRequestPolicy.HOME_TIMEOUT_MILLIS,
            ).timeout().timeoutNanos(),
        )
    }

    @Test fun anUnresponsiveImageConnectionActuallyTimesOut() {
        val client = OkHttpClient.Builder().build()
        ServerSocket(0).use { server ->
            val connected = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                server.accept().use {
                    connected.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            try {
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/cover.jpg").build()
                assertThrows(IOException::class.java) {
                    runBlocking { ArtworkRequestPolicy.limit(client.newCall(request), 250).await().close() }
                }
                assertTrue(connected.await(1, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                worker.join(1_000)
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
