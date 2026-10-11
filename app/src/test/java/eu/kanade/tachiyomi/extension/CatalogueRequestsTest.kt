package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.catalogueRequest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CatalogueRequestsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `refresh discovers new versions inside the normal cache lifetime`() {
        MockWebServer().use { server ->
            server.start()
            Cache(directory.toFile(), 1024 * 1024).use { cache ->
                val client = OkHttpClient.Builder().cache(cache).build()
                val url = server.url("/index.json").toString()
                server.enqueue(
                    MockResponse.Builder().addHeader("Cache-Control", "max-age=600")
                        .addHeader("ETag", "\"revision-1\"").body("{\"version\":1}").build(),
                )
                client.newCall(GET(url)).execute().use { assertEquals("{\"version\":1}", it.body.string()) }
                client.newCall(GET(url)).execute().use { assertEquals("{\"version\":1}", it.body.string()) }
                assertEquals(1, server.requestCount)

                server.enqueue(
                    MockResponse.Builder().addHeader("Cache-Control", "max-age=600")
                        .addHeader("ETag", "\"revision-2\"").body("{\"version\":2}").build(),
                )
                client.newCall(catalogueRequest(url)).execute().use {
                    assertEquals("{\"version\":2}", it.body.string())
                }
                server.takeRequest(5, TimeUnit.SECONDS)
                assertEquals("\"revision-1\"", server.takeRequest(5, TimeUnit.SECONDS)?.headers?.get("If-None-Match"))

                server.enqueue(MockResponse.Builder().code(304).build())
                client.newCall(catalogueRequest(url)).execute().use {
                    assertEquals(200, it.code)
                    assertEquals(304, it.networkResponse?.code)
                    assertEquals("{\"version\":2}", it.body.string())
                }
                assertEquals("\"revision-2\"", server.takeRequest(5, TimeUnit.SECONDS)?.headers?.get("If-None-Match"))
            }
        }
    }
}
