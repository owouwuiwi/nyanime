package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.AnilistCatalogRemote
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogRequest
import java.util.concurrent.TimeUnit

/** Opt-in integration probe: ordinary unit tests must not depend on the public service. */
@EnabledIfEnvironmentVariable(named = "ANIYOMI_VERIFY_CATALOG", matches = "true")
class AnilistLiveCatalogTest {
    @Test
    fun `public catalogue feeds search calendar and details work without authentication`() = runBlocking {
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val remote = AnilistCatalogRemote(client, Json { ignoreUnknownKeys = true })
        try {
            val trending = remote.fetch(CatalogRequest(CatalogFeed.TRENDING))
            assertTrue(trending.items.isNotEmpty())
            val first = trending.items.first()
            assertEquals(first.id, remote.details(first.id).id)
            listOf(CatalogFeed.SEASON, CatalogFeed.TOP, CatalogFeed.NEXT_SEASON).forEach {
                assertTrue(remote.fetch(CatalogRequest(it)).items.isNotEmpty())
            }
            remote.fetch(CatalogRequest(CatalogFeed.WEEK))
            assertTrue(remote.fetch(CatalogRequest(CatalogFeed.SEARCH, query = first.title)).items.isNotEmpty())
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
