package eu.kanade.tachiyomi.data.releases

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class AnimeScheduleClientTest {
    private val metadata = """{"route":"sample-series","websites":{
        "aniList":"https://anilist.co/anime/47","mal":"https://myanimelist.net/anime/17",
        "anidb":"https://anidb.net/anime/23"}}"""
    private fun withApi(vararg responses: MockResponse, block: (AnimeScheduleClient, MockWebServer) -> Unit) {
        val http = OkHttpClient.Builder().followRedirects(false).build()
        try {
            MockWebServer().use { server ->
                server.start()
                responses.forEach(server::enqueue)
                block(AnimeScheduleClient(http, { "private-test-token" }, server.url("/api/v3/"), 0), server)
            }
        } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }
    private fun body(value: String) = MockResponse.Builder().body(value).build()
    private fun page(vararg rows: String) = "{\"anime\":[${rows.joinToString(",")}] }"
    private fun failed(api: AnimeScheduleClient) = assertThrows(AnimeScheduleException::class.java) {
        runBlocking { api.timetable(2026, 40) }
    }

    @Test fun onlyExactIdsAreSentAndBearerNeverAppearsInTheUrl() {
        withApi(body(page(metadata))) { api, server ->
            val result = runBlocking { api.resolve(AiringCatalogReference(47, 17), null) }
            assertEquals("sample-series", ScheduleParser.text(result!!, "route"))
            val request = server.takeRequest()
            assertEquals("Bearer private-test-token", request.headers["Authorization"])
            assertTrue(request.target.contains("anilist-ids=47"))
            assertFalse(request.target.contains("private-test-token"))
            assertFalse(request.target.contains("title"))
        }
    }

    @Test fun contradictoryCatalogEvidenceDoesNotChooseAnEdition() {
        withApi(body(page(metadata))) { api, _ ->
            assertNull(runBlocking { api.resolve(AiringCatalogReference(47, 18), null) })
        }
    }

    @Test fun ambiguousRoutesNeverSelectTheFirstResult() {
        val data = page(metadata, metadata.replace("sample-series", "other-edition"))
        withApi(body(data)) { api, _ ->
            assertNull(runBlocking { api.resolve(AiringCatalogReference(47, 17), null) })
        }
    }

    @Test fun anidbOnlyUsesItsExactCatalogId() {
        withApi(body(page(metadata))) { api, server ->
            val result = runBlocking { api.resolve(AiringCatalogReference(null, null), 23) }
            assertEquals("sample-series", ScheduleParser.text(result!!, "route"))
            assertTrue(server.takeRequest().target.contains("anidb-ids=23"))
        }
    }

    @Test fun noIdsMeanNoNetworkLookup() {
        withApi { api, server ->
            assertNull(runBlocking { api.resolve(AiringCatalogReference(null, null), null) })
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun catalogLookalikeHostsAreRejected() {
        withApi(body(page(metadata.replace("anilist.co", "anilist.co.invalid")))) { api, _ ->
            assertNull(runBlocking { api.resolve(AiringCatalogReference(47, 17), null) })
        }
    }

    @Test fun schemeLessCatalogLinksStillRequireTheExactIds() {
        for (prefix in listOf("https://", "http://", "//", "")) {
            val links = metadata.replace("https://", prefix)
            withApi(body(page(links))) { api, _ ->
                assertEquals(
                    "sample-series",
                    ScheduleParser.text(runBlocking { api.resolve(AiringCatalogReference(47, 17), null) }!!, "route"),
                )
            }
        }
    }

    @Test fun schemeLessMalAndAnidbLinksAreVerified() {
        val links = metadata.replace("https://", "")
        withApi(body(page(links))) { api, _ ->
            assertTrue(runBlocking { api.resolve(AiringCatalogReference(null, 17), null) } != null)
        }
        withApi(body(page(links))) { api, _ ->
            assertTrue(runBlocking { api.resolve(AiringCatalogReference(null, null), 23) } != null)
        }
    }

    @Test fun catalogCredentialsAndSchemeLessLookalikesAreRejected() {
        for (link in listOf("anilist.co.invalid", "untrusted.invalid@anilist.co")) {
            withApi(body(page(metadata.replace("https://anilist.co", link)))) { api, _ ->
                assertNull(runBlocking { api.resolve(AiringCatalogReference(47, 17), null) })
            }
        }
    }

    @Test fun missingIdIsAnEmptyMappingRatherThanAnUnreachableService() {
        withApi(MockResponse.Builder().code(404).build()) { api, server ->
            assertNull(runBlocking { api.resolve(AiringCatalogReference(47, 17), null) })
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingTimetableAndServerFailuresAreNotTransportFailures() {
        for (status in listOf(404, 500, 503)) {
            withApi(MockResponse.Builder().code(status).build()) { api, _ ->
                val error = failed(api)
                assertEquals("SERVER", error.reason)
                assertEquals(status, error.status)
            }
        }
    }

    @Test fun anUnpublishedWeekIsAnEmptyScheduleWithoutAnImmediateRetry() {
        withApi(MockResponse.Builder().code(404).build()) { api, server ->
            val body = runBlocking { api.timetable(2027, 5, allowUnavailable = true) }
            assertTrue(ScheduleParser.timetable(body).isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun anUnpublishedWeekCannotHideAnAuthenticationOrServerFailure() {
        for ((status, reason) in listOf(401 to "AUTH", 403 to "AUTH", 500 to "SERVER")) {
            withApi(MockResponse.Builder().code(status).build()) { api, _ ->
                val error = assertThrows(AnimeScheduleException::class.java) {
                    runBlocking { api.timetable(2027, 5, allowUnavailable = true) }
                }
                assertEquals(reason, error.reason)
            }
        }
    }

    @Test fun malformedDataIsNotReportedAsAnUnreachableService() {
        withApi(body("{\"unexpected\":true}")) { api, _ ->
            val error = assertThrows(IllegalStateException::class.java) { runBlocking { api.timetable(2026, 40) } }
            assertEquals("DATA", animeScheduleFailureReason(error))
        }
        assertEquals("NETWORK", animeScheduleFailureReason(IOException("private detail")))
        assertEquals("LOCAL", animeScheduleFailureReason(java.security.GeneralSecurityException("private detail")))
    }

    @Test fun authenticationFailureStopsWithATypedError() {
        for (code in listOf(401, 403)) {
            withApi(MockResponse.Builder().code(code).build()) { api, _ ->
                val exception = failed(api)
                assertEquals("AUTH", exception.reason)
                assertFalse(exception.message.orEmpty().contains("private-test-token"))
            }
        }
    }

    @Test fun rateLimitIsHonoredWithoutAnImmediateRetry() {
        val reset = System.currentTimeMillis() / 1000 + 300
        val response = MockResponse.Builder().code(429).addHeader("X-RateLimit-Reset", reset.toString()).build()
        withApi(response) { api, server ->
            assertEquals(reset * 1000, failed(api).retryAt)
            failed(api)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun aRedirectCannotForwardTheTokenToAnotherHost() {
        val response = MockResponse.Builder().code(302).addHeader("Location", "https://untrusted.invalid/token").build()
        withApi(response) { api, server ->
            assertEquals("SERVER", failed(api).reason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun timetableUsesUtcAndBothWeekParameters() {
        withApi(body("[]")) { api, server ->
            runBlocking { api.timetable(2026, 40) }
            val target = server.takeRequest().target
            assertTrue(target.startsWith("/api/v3/timetables/all?"))
            assertTrue(target.contains("tz=UTC"))
            assertTrue(target.contains("year=2026"))
            assertTrue(target.contains("week=40"))
        }
    }
}
