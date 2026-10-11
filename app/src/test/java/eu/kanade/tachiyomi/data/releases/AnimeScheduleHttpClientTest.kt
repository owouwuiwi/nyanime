package eu.kanade.tachiyomi.data.releases

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimeScheduleHttpClientTest {
    @Test fun verboseNetworkLoggingAndCookiesCannotLeakTheBearerOrLogin() {
        val logs = mutableListOf<String>()
        val logger = HttpLoggingInterceptor { logs += it }.apply { level = HttpLoggingInterceptor.Level.HEADERS }
        val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(
                url: HttpUrl,
            ) = listOf(Cookie.Builder().name("session").value("login-secret").domain(url.host).build())
        }
        val original = OkHttpClient.Builder().addNetworkInterceptor(logger).addInterceptor(logger).cookieJar(cookieJar)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-App-Test", "configured").build())
            }.build()
        val http = AnimeScheduleHttpClient.create(original)
        try {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body("[]").build())
                http.newCall(
                    Request.Builder().url(
                        server.url("/schedule"),
                    ).header("Authorization", "Bearer private-test-token").build(),
                ).execute().close()
                val request = server.takeRequest()
                assertEquals("Bearer private-test-token", request.headers["Authorization"])
                assertEquals("configured", request.headers["X-App-Test"])
                assertNull(request.headers["Cookie"])
                assertTrue(logs.isEmpty())
                assertFalse(http.followRedirects)
                assertNull(http.cache)
            }
        } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }
}
