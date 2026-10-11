package eu.kanade.tachiyomi.data.cast

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Optional live protocol fixture, never a television or a source. See docs/companion-protocol.md. */
class CompanionInteropTest {
    @Test
    fun `Android wire pairs and retries against the independent TypeScript receiver`() = runBlocking {
        val endpoint = System.getenv("NYANIME_COMPANION_TEST_URL")
        assumeTrue(endpoint != null, "Start the loopback receiver fixture to run cross-runtime integration")
        val loseReply = AtomicBoolean(false)
        val http = CompanionClient.networkClient().newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (loseReply.compareAndSet(true, false)) {
                response.close()
                throw IOException("Simulated lost response after server execution")
            }
            response
        }.build()
        val client = CompanionClient(CompanionClient.describe(endpoint!!.toHttpUrl(), http), http)
        try {
            client.pair("JVM test sender") { code -> assertTrue(code.matches(Regex("[0-9]{6}"))) }
            assertTrue(client.approved)
            loseReply.set(true)
            val loaded = client.command(
                buildJsonObject {
                    put("type", "load")
                    put(
                        "media",
                        buildJsonObject {
                            put("id", "a".repeat(32))
                            put("title", "Titolo di prova")
                            put("episode", "Episodio 1")
                            put("url", "http://127.0.0.1:32123/video.mp4")
                            put("mimeType", "video/mp4")
                            put("positionMs", 5000)
                            put("durationMs", 60000)
                            put("subtitles", buildJsonArray {})
                        },
                    )
                },
            )
            assertEquals("5000", loaded.getValue("playback").jsonObject.getValue("positionMs").jsonPrimitive.content)
            val stopped = client.command(
                buildJsonObject {
                    put("type", "stop")
                    put("mediaId", "a".repeat(32))
                },
            )
            assertEquals("true", stopped.getValue("playback").jsonObject.getValue("stopped").jsonPrimitive.content)
        } finally {
            client.clear()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
