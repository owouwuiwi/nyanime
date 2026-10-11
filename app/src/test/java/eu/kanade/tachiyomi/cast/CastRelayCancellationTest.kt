package eu.kanade.tachiyomi.cast

import eu.kanade.tachiyomi.data.cast.CastRelay
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CastRelayCancellationTest {
    @Test
    fun cancellingPreparationUnblocksASlowSourceAndPreventsNewRequests() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val origin = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return newFixedLengthResponse(Response.Status.OK, "video/mp4", "video")
            }
        }
        origin.start()
        val executor = Executors.newSingleThreadExecutor()
        val relay = CastRelay("127.0.0.1", OkHttpClient(), Headers.Builder().build())
        relay.start()
        val url = "http://127.0.0.1:${origin.listeningPort}/video"
        try {
            val pending = executor.submit<Pair<String, String>> { relay.prepare(url) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            relay.revoke()
            assertThrows(ExecutionException::class.java) { pending.get(3, TimeUnit.SECONDS) }
            assertThrows(IllegalStateException::class.java) { relay.prepare(url) }
        } finally {
            release.countDown()
            relay.stop()
            origin.stop()
            executor.shutdownNow()
        }
    }
}
