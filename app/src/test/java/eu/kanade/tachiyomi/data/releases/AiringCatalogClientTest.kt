package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AiringCatalogClientTest {
    private val media = """{"data":{"Media":{"id":93,"idMal":31,"status":"FINISHED","episodes":12,
        "airingSchedule":{"pageInfo":{"hasNextPage":false},"nodes":[]}}}}"""

    private fun withApi(vararg responses: MockResponse, block: (AiringCatalogClient, MockWebServer) -> Unit) {
        val http = OkHttpClient()
        try {
            MockWebServer().use { server ->
                server.start()
                responses.forEach(server::enqueue)
                block(AiringCatalogClient(http, server.url("/").toString(), spacingMillis = 0), server)
            }
        } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    @Test fun savedMalBindingWinsOverAnInvalidAnilistHint() {
        val reference = AiringCatalogReference.choose(11, 31, null, 31)
        assertEquals(AiringCatalogReference(null, 31), reference)
        withApi(MockResponse.Builder().body(media).build()) { api, server ->
            assertEquals(media, runBlocking { api.page(reference, 1) })
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingAnilistIdCanUseAnExactlyVerifiedMalMapping() {
        withApi(MockResponse.Builder().code(404).build(), MockResponse.Builder().body(media).build()) { api, server ->
            assertEquals(media, runBlocking { api.page(AiringCatalogReference(11, 31), 1) })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun twoExistingButContradictoryIdsNeverSelectADifferentTitleSilently() {
        withApi(MockResponse.Builder().body(media).build()) { api, server ->
            assertThrows(AiringCatalogIdentityException::class.java) {
                runBlocking { api.page(AiringCatalogReference(93, 32), 1) }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun aFallbackThatDoesNotMatchTheRequestedMalIdIsRejected() {
        withApi(MockResponse.Builder().code(404).build(), MockResponse.Builder().body(media).build()) { api, _ ->
            assertThrows(AiringCatalogIdentityException::class.java) {
                runBlocking { api.page(AiringCatalogReference(11, 32), 1) }
            }
        }
    }

    @Test fun networkFailuresDoNotLookLikeMissingIdentityAndDoNotTriggerAnotherLookup() {
        withApi(MockResponse.Builder().code(503).build()) { api, server ->
            assertThrows(HttpException::class.java) {
                runBlocking { api.page(AiringCatalogReference(11, 31), 1) }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun absentIdsAreReportedAsUnresolvedRatherThanAConnectionFailure() {
        withApi(MockResponse.Builder().code(404).build()) { api, _ ->
            assertThrows(AiringCatalogIdentityException::class.java) {
                runBlocking { api.page(AiringCatalogReference(11, null), 1) }
            }
        }
    }

    @Test fun aSavedAnilistBindingDoesNotInheritAnUnverifiedConflictingMalHint() {
        val reference = AiringCatalogReference.choose(11, 32, 93, null)
        assertEquals(AiringCatalogReference(93, 32, expectedMalId = null), reference)
        withApi(MockResponse.Builder().body(media).build()) { api, server ->
            assertEquals(media, runBlocking { api.page(reference, 1) })
            assertEquals(1, server.requestCount)
        }
    }
}
