package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.KitsuCatalogMapper
import eu.kanade.tachiyomi.data.discovery.KitsuCatalogRemote
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogRequest
import java.io.IOException

class KitsuCatalogTest {
    private fun resource(extra: String = "") = Json.parseToJsonElement(
        """{"id":"7","type":"anime","attributes":{
          "canonicalTitle":"Example","nsfw":false,"averageRating":"82.5",
          "startDate":"2026-07-01","status":"finished","episodeCount":12 $extra
        }}""",
    ) as JsonObject

    @Test
    fun `metadata keeps its true provider and does not invent episode or airing time`() {
        val anime = KitsuCatalogMapper.map(resource(), emptyList())!!
        assertEquals("kitsu", anime.id.provider)
        assertEquals(83, anime.score)
        assertEquals("SUMMER", anime.season)
        assertEquals("FINISHED", anime.status)
        assertNull(anime.airingAt)
        assertNull(anime.airingEpisode)
        assertNull(anime.malId)
    }

    @Test
    fun `unrelated included mapping cannot authorize an association`() {
        val mapping = Json.parseToJsonElement(
            """{"type":"mappings","id":"1","attributes":{"externalSite":"myanimelist/anime","externalId":"9"}}""",
        ) as JsonObject
        assertNull(KitsuCatalogMapper.map(resource(), listOf(mapping))!!.malId)
    }

    @Test
    fun `adult catalogue entries are excluded`() {
        assertNull(KitsuCatalogMapper.map(resource(",\"ageRating\":\"R18\""), emptyList()))
    }

    @Test
    fun `calendar is explicitly unavailable without making a request`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Calendar must not fabricate a feed") }.build()
        val page = KitsuCatalogRemote(client, Json).fetch(CatalogRequest(CatalogFeed.WEEK))
        assertEquals("kitsu", page.provider)
        assertTrue(page.items.isEmpty())
        assertTrue(page.notice.orEmpty().contains("non disponibile"))
    }

    @Test
    fun `season filters and page offset use the actual requested date`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            assertNull(url.queryParameter("filter[nsfw]"))
            assertEquals("G,PG,R", url.queryParameter("filter[ageRating]"))
            assertEquals("winter", url.queryParameter("filter[season]"))
            assertEquals("2027", url.queryParameter("filter[seasonYear]"))
            assertEquals("20", url.queryParameter("page[offset]"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[],"links":{"next":null}}""".toResponseBody()).build()
        }.build()
        try {
            val page = KitsuCatalogRemote(client, Json, intervalMillis = 0)
                .fetch(CatalogRequest(CatalogFeed.NEXT_SEASON, page = 2, date = "2026-12-01"))
            assertEquals("kitsu", page.provider)
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `rate limit stops further requests until retry window`() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Wait")
                .header("Retry-After", "60").body("{}".toResponseBody()).build()
        }.build()
        try {
            val remote = KitsuCatalogRemote(client, Json, intervalMillis = 0)
            repeat(2) {
                assertThrows(IOException::class.java) { runBlocking { remote.fetch(CatalogRequest(CatalogFeed.TOP)) } }
            }
            assertEquals(1, calls)
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }
}
