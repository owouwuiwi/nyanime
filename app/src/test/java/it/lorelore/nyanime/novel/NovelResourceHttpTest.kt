package it.lorelore.nyanime.novel

import it.lorelore.nyanime.novel.source.NovelResourceHttp
import it.lorelore.nyanime.source.novel.NovelResourceRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class NovelResourceHttpTest {
    @Test
    fun `a cross origin redirect drops all source session headers`() =
        runBlocking<Unit> {
            MockWebServer().use { first ->
                MockWebServer().use { second ->
                    first.start()
                    second.start()
                    first.enqueue(
                        MockResponse.Builder()
                            .code(302)
                            .addHeader("Location", second.url("/image"))
                            .build(),
                    )
                    second.enqueue(MockResponse.Builder().body("image-bytes").build())
                    val http = NovelResourceHttp(OkHttpClient())
                    val result =
                        http.read(
                            NovelResourceRequest(
                                first.url("/start").toString(),
                                mapOf(
                                    "Authorization" to "source-session",
                                    "Cookie" to "source-cookie",
                                    "X-Source-Token" to "private",
                                ),
                            ),
                        )
                    assertEquals("image-bytes", result.toString(Charsets.UTF_8))
                    assertEquals("source-session", first.takeRequest().headers["Authorization"])
                    val redirected = second.takeRequest()
                    for (name in listOf("Authorization", "Cookie", "X-Source-Token")) {
                        assertNull(redirected.headers[name])
                    }
                }
            }
        }

    @Test
    fun `a same origin redirect preserves the explicit session`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse.Builder().code(302).addHeader("Location", "/image").build(),
                )
                server.enqueue(MockResponse.Builder().body("bytes").build())
                val http = NovelResourceHttp(OkHttpClient())
                http.read(
                    NovelResourceRequest(
                        server.url("/start").toString(),
                        mapOf("X-Source-Token" to "session"),
                    ),
                )
                server.takeRequest()
                assertEquals("session", server.takeRequest().headers["X-Source-Token"])
            }
        }

    @Test
    fun `unrelated application cookies cannot be attached to a source image`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body("bytes").build())
                var globalCookieReads = 0
                val base =
                    OkHttpClient.Builder()
                        .cookieJar(
                            object : CookieJar {
                                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                                    globalCookieReads++
                                    return listOf(
                                        Cookie.Builder()
                                            .name("private")
                                            .value("unrelated")
                                            .hostOnlyDomain(url.host)
                                            .build(),
                                    )
                                }

                                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
                                    Unit
                            },
                        )
                        .build()
                NovelResourceHttp(base).read(NovelResourceRequest(server.url("/image").toString()))
                assertEquals(0, globalCookieReads)
                assertNull(server.takeRequest().headers["Cookie"])
            }
        }

    @Test
    fun `an oversized response fails before returning partial data`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body("123456789").build())
                assertThrows(IOException::class.java) {
                    runBlocking {
                        NovelResourceHttp(OkHttpClient())
                            .read(
                                NovelResourceRequest(server.url("/image").toString()),
                                maxBytes = 8,
                            )
                    }
                }
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `non HTTP URLs and embedded credentials are rejected before sending`() {
        val http = NovelResourceHttp(OkHttpClient())
        for (
        url in
        listOf(
            "file:///private",
            "content://private/document",
            "https://user:secret@example.invalid/image",
        )
        ) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { http.read(NovelResourceRequest(url)) }
            }
        }
    }

    @Test
    fun `host interceptors and header loggers are not run for source resources`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body("bytes").build())
                var calls = 0
                val base =
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            calls++
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .header("X-Unrelated-Secret", "private")
                                    .build(),
                            )
                        }
                        .addNetworkInterceptor { chain ->
                            calls++
                            chain.proceed(chain.request())
                        }
                        .build()
                NovelResourceHttp(base).read(NovelResourceRequest(server.url("/image").toString()))
                assertEquals(0, calls)
                assertNull(server.takeRequest().headers["X-Unrelated-Secret"])
            }
        }

    @Test
    fun `cancelling an image read does not wait for the response body`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body("delayed").bodyDelay(2, TimeUnit.SECONDS).build(),
            )
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(100) {
                        NovelResourceHttp(OkHttpClient())
                            .read(NovelResourceRequest(server.url("/image").toString()))
                    }
                }
            }
        }
    }
}
