package it.lorelore.nyanime.tracking

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TrackingRequestGuardTest {
    @Test fun `credential switch after authentication prevents sending`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            var current = "fixture-session-one"
            val base = OkHttpClient.Builder().addInterceptor { chain ->
                current = "fixture-session-two"
                chain.proceed(chain.request())
            }.build()
            val client = TrackingRequestGuard.client(base, 70001)
            TrackingRequestGuard.leased(70001, { current }) {
                assertThrows(TrackingCredentialsChanged::class.java) {
                    client.newCall(Request.Builder().url(server.url("/entry")).build()).execute().close()
                }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `lease is released after failure so a new current credential snapshot can retry`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("confirmed").build())
            var current = "fixture-before-renewal"
            val client = TrackingRequestGuard.client(OkHttpClient(), 70002)
            TrackingRequestGuard.leased(70002, { current }) {
                current = "fixture-after-renewal"
                assertThrows(TrackingCredentialsChanged::class.java) {
                    client.newCall(Request.Builder().url(server.url("/entry")).build()).execute().close()
                }
            }
            TrackingRequestGuard.leased(70002, { current }) {
                client.newCall(Request.Builder().url(server.url("/entry")).build()).execute().use {
                    assertEquals(200, it.code)
                }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `service retry after is captured without consuming a second request`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "120").build())
            val client = TrackingRequestGuard.client(OkHttpClient(), 70003)
            val error = assertThrows(TrackingQueueHttpFailure::class.java) {
                runBlocking {
                    TrackingRequestGuard.leased(70003, { "fixture-session" }) {
                        client.newCall(Request.Builder().url(server.url("/entry")).build()).execute().use { response ->
                            assertEquals(429, response.code)
                            throw java.io.IOException("Fictional provider rejected the response")
                        }
                    }
                }
            }
            assertEquals(429, error.code)
            assertEquals(120_000L, error.retryAfter)
            assertEquals(1, server.requestCount)
        }
    }
}
