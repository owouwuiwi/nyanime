package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.AnilistCatalogTransport
import eu.kanade.tachiyomi.ui.discovery.DiscoveryScreenModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.CatalogFailureReason
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogServiceUnavailableException
import tachiyomi.domain.discovery.SectionState
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class AnilistCatalogTransportTest {
    private class TestClock(var now: Long = 1_000_000) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private fun <T> withTransport(
        code: Int = 403,
        body: String = OUTAGE,
        test: (AnilistCatalogTransport, AtomicInteger, TestClock) -> T,
    ): T {
        val calls = AtomicInteger()
        val clock = TestClock()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Test").header("Server", "cloudflare")
                .header("Content-Type", "application/json").body(body.toResponseBody()).build()
        }.build()
        return try {
            test(AnilistCatalogTransport(client, Json, clock), calls, clock)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun request(transport: AnilistCatalogTransport) = runBlocking {
        transport.execute("query { Page { media { id } } }", JsonObject(emptyMap()))
    }

    @Test
    fun `documented outage behind Cloudflare is a service error not a challenge`() = withTransport { api, calls, _ ->
        val error = assertThrows(CatalogServiceUnavailableException::class.java) { request(api) }
        assertFalse(error.message.orEmpty().contains("bypass"))
        assertEquals(1, calls.get())
    }

    @Test
    fun `concurrent sections share one outage probe`() = withTransport { api, calls, _ ->
        runBlocking {
            (1..5).map {
                async {
                    try {
                        api.execute("query { Page { media { id } } }", JsonObject(emptyMap()))
                        false
                    } catch (_: CatalogServiceUnavailableException) {
                        true
                    }
                }
            }.awaitAll().forEach { assertEquals(true, it) }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `manual retry cannot bypass cooldown and expiry permits one new probe`() = withTransport { api, calls, clock ->
        repeat(2) { assertThrows(CatalogServiceUnavailableException::class.java) { request(api) } }
        assertEquals(1, calls.get())
        clock.now += AnilistCatalogTransport.OUTAGE_COOLDOWN_MILLIS
        assertThrows(CatalogServiceUnavailableException::class.java) { request(api) }
        assertEquals(2, calls.get())
    }

    @Test
    fun `clock rollback cannot leave catalogue blocked indefinitely`() = withTransport { api, calls, clock ->
        assertThrows(CatalogServiceUnavailableException::class.java) { request(api) }
        clock.now -= 1_000
        assertThrows(CatalogServiceUnavailableException::class.java) { request(api) }
        assertEquals(2, calls.get())
    }

    @Test
    fun `ordinary forbidden is not mislabeled as global service outage`() = withTransport(body = "{}") { api, _, _ ->
        val error = assertThrows(IOException::class.java) { request(api) }
        assertFalse(error is CatalogServiceUnavailableException)
        assertEquals("AniList ha rifiutato la richiesta (HTTP 403). Riprova più tardi.", error.message)
    }

    @Test
    fun `successful response remains usable`() = withTransport(200, """{"data":{"Page":{}}}""") { api, _, _ ->
        assertNotNull(request(api)["Page"])
    }

    @Test
    fun `home summarizes a confirmed outage once without touching local sections`() {
        val state = DiscoveryScreenModel.State(
            catalog = listOf(CatalogFeed.TRENDING, CatalogFeed.SEASON).associateWith {
                SectionState(
                    loading = false,
                    error = "Service unavailable",
                    failureReason = CatalogFailureReason.SERVICE_UNAVAILABLE,
                )
            },
            resume = SectionState(emptyList(), loading = false),
        )
        assertEquals("Service unavailable", state.catalogueOutage)
        assertEquals(emptyList<Any>(), state.resume.data)
    }

    companion object {
        private const val OUTAGE = """
            {"errors":[{
                "message":"The AniList API has been temporarily disabled due to severe stability issues.",
                "status":403
            }],"data":null}
        """
    }
}
