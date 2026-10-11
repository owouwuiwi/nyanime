package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.AnilistCatalogRemote
import eu.kanade.tachiyomi.data.discovery.KitsuCatalogRemote
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogRequest
import tachiyomi.domain.discovery.FailoverAnimeCatalogRemote

@EnabledIfEnvironmentVariable(named = "ANIYOMI_VERIFY_FALLBACK", matches = "true")
class KitsuLiveCatalogTest {
    @Test
    fun `independent public catalogue works and production failover reaches real data`() = runBlocking {
        val client = OkHttpClient()
        val fallback = KitsuCatalogRemote(client, Json)
        try {
            val trending = fallback.fetch(CatalogRequest(CatalogFeed.TRENDING))
            assertTrue(trending.items.isNotEmpty())
            val first = trending.items.first()
            assertEquals(first.id, fallback.details(first.id).id)
            listOf(CatalogFeed.SEASON, CatalogFeed.NEXT_SEASON, CatalogFeed.TOP).forEach {
                val page = fallback.fetch(CatalogRequest(it))
                assertEquals("kitsu", page.provider)
            }
            assertTrue(fallback.fetch(CatalogRequest(CatalogFeed.SEARCH, query = first.title)).items.isNotEmpty())
            val router = FailoverAnimeCatalogRemote(AnilistCatalogRemote(client, Json), fallback)
            assertTrue(router.fetch(CatalogRequest(CatalogFeed.TRENDING)).items.isNotEmpty())
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
