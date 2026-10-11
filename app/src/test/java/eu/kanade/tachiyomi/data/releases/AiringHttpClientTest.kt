package eu.kanade.tachiyomi.data.releases

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress

class AiringHttpClientTest {
    @Test fun calendarRequestsUseTheConfiguredDnsAndApiInterceptors() {
        val lookups = mutableListOf<String>()
        val apiClient = OkHttpClient.Builder()
            .dns(
                Dns { hostname ->
                    lookups += hostname
                    listOf(InetAddress.getByName("127.0.0.1"))
                },
            )
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-App-Test", "configured-api").build())
            }
            .build()
        val client = AiringHttpClient.create(apiClient)
        try {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body("{\"data\":{}}").build())
                val url = server.url("/schedule").newBuilder().host("catalog.invalid").build()
                client.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(200, it.code)
                }
                assertEquals(listOf("catalog.invalid"), lookups)
                assertEquals("configured-api", server.takeRequest().headers["X-App-Test"])
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
