package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.AnilistCatalogRemote
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogRequest

class AnilistCatalogRemoteTest {
    private suspend fun fetch(body: String, code: Int = 200) =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Test response").body(body.toResponseBody()).build()
        }.build().let { client ->
            try {
                AnilistCatalogRemote(client, Json).fetch(CatalogRequest(CatalogFeed.TRENDING))
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

    @Test
    fun `partial optional metadata keeps valid catalogue results`() = runBlocking {
        val page =
            fetch(
                """
            {"data":{"Page":{"pageInfo":{"hasNextPage":false},"media":[
              {"id":1,"title":{"romaji":"Example"},"description":"<b>Synopsis</b>","episodes":null},
              {"id":2,"title":null}
            ]}},"errors":[{"message":"Optional field unavailable"}]}
        """,
            )
        assertEquals(1, page.items.size)
        assertEquals("Synopsis", page.items.single().synopsis)
        assertNull(page.items.single().episodes)
    }

    @Test
    fun `missing section data is an error not an empty successful cache`() {
        assertThrows(Exception::class.java) {
            runBlocking { fetch("""{"data":{"Page":null},"errors":[{"message":"Forbidden"}]}""") }
        }
    }

    @Test
    fun `HTTP forbidden and throttling errors reach the cache error handler`() {
        listOf(403, 429).forEach { status ->
            assertThrows(Exception::class.java) { runBlocking { fetch("{}", status) } }
        }
    }
}
