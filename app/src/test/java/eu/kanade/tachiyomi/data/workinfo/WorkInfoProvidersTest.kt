package eu.kanade.tachiyomi.data.workinfo

import eu.kanade.tachiyomi.data.discovery.AnilistCatalogTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class WorkInfoProvidersTest {
    private fun client(status: Int = 200, body: String, inspect: (Request) -> Unit = {}) =
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test").body(body.toResponseBody()).build()
        }.build()

    private fun OkHttpClient.close() {
        dispatcher.executorService.shutdown()
        connectionPool.evictAll()
    }

    @Test fun `short HTTP responses do not require a full buffer and no credentials are sent`() = runBlocking {
        val client = client(body = """{"ok":true}""") { request ->
            assertEquals(null, request.header("Authorization"))
            assertEquals(null, request.header("Cookie"))
            assertFalse(request.url.toString().contains("api_key"))
            assertTrue(request.header("User-Agent").orEmpty().contains("Nyanime"))
        }
        try {
            assertEquals(
                """{"ok":true}""",
                WorkInfoHttp(client, Json).get("https://api.tvmaze.com/shows/1".toHttpUrl()).toString(),
            )
        } finally {
            client.close()
        }
    }

    @Test fun `quota rejection does not cause a retry storm`() = runBlocking {
        val calls = AtomicInteger()
        val client = client(429, "{}") { calls.incrementAndGet() }
        try {
            val http = WorkInfoHttp(client, Json)
            repeat(3) { assertTrue(runCatching { http.get("https://api.tvmaze.com/shows/1".toHttpUrl()) }.isFailure) }
            assertEquals(1, calls.get())
        } finally {
            client.close()
        }
    }

    @Test fun `transport never accepts extension URLs`() = runBlocking {
        val calls = AtomicInteger()
        val client = client(body = "{}") { calls.incrementAndGet() }
        try {
            assertTrue(
                runCatching {
                    WorkInfoHttp(client, Json).get("https://example.invalid/title/1".toHttpUrl())
                }.isFailure,
            )
            assertEquals(0, calls.get())
        } finally {
            client.close()
        }
    }

    @Test fun `IMDb lookup not found is an empty result rather than an outage`() = runBlocking {
        val client = client(404, "{}")
        try {
            val result = TvMazeWorkInformation(WorkInfoHttp(client, Json)).search(
                WorkIdentity("local", "A fictional film", WorkKind.FILM, imdbId = "tt1234567"),
                "it",
            )
            assertTrue(result.isEmpty())
        } finally {
            client.close()
        }
    }

    @Test fun `TV details separate declared seasons from source episodes`() = runBlocking {
        val requests = mutableListOf<String>()
        val client =
            client(
                body = """
            {"id":55,"name":"A fictional series","type":"Scripted","premiered":"2020-01-01",
             "summary":"<p>A catalogue description.</p>","language":"English","runtime":42,"status":"Running",
             "externals":{"imdb":"tt1234567"},"network":{"country":{"code":"GB"}},
             "_embedded":{"cast":[{"person":{"id":9,"name":"Actor A"},"character":{"name":"Character B"}}],
             "crew":[{"type":"Creator","person":{"id":10,"name":"Creator A"}}],
             "seasons":[{"number":1,"episodeOrder":8,"premiereDate":"2020-01-01"}],"akas":[]}}
                """.trimIndent(),
            ) { requests += it.url.toString() }
        try {
            val result = TvMazeWorkInformation(WorkInfoHttp(client, Json)).information(
                WorkReference(WorkProvider.TVMAZE, "55", WorkKind.SERIES),
                "it",
            )
            assertEquals("A catalogue description.", result.synopsis)
            assertEquals("en", result.textLanguage)
            assertEquals(8, result.seasons.single().episodes)
            assertEquals("Character B", result.cast.single().character)
            assertEquals("Creator A", result.creators.single().name)
            assertEquals("https://www.imdb.com/title/tt1234567/", result.imdbUrl)
            assertEquals(1, requests.size)
            assertFalse(requests.any { it.contains("/episodes") })
        } finally {
            client.close()
        }
    }

    @Test fun `AniList information retains manga identity creator roles and adaptation links`() = runBlocking {
        val client =
            client(
                body = """
            {"data":{"Media":{"id":123,"type":"MANGA","title":{"romaji":"Fictional Book","native":"Original name"},
            "synonyms":["Book alias"],"startDate":{"year":2020},"format":"MANGA",
            "description":"<p>Public synopsis.</p>",
             "status":"RELEASING","chapters":20,"volumes":3,"staff":{"pageInfo":{"hasNextPage":true},
             "edges":[{"role":"Story & Art","node":{"id":7,"name":{"full":"Author A"}}}]},
            "characters":{"pageInfo":{"hasNextPage":false},"edges":[
              {"role":"MAIN","node":{"name":{"full":"Character A"}},"voiceActors":[]}]},
            "relations":{"edges":[{"relationType":"ADAPTATION","node":{"id":456,"type":"ANIME",
              "title":{"romaji":"Fictional Film"},"format":"MOVIE"}}]}}}}
                """.trimIndent(),
            )
        try {
            val result = AniListWorkInformation(AnilistCatalogTransport(client, Json)).information(
                WorkReference(WorkProvider.ANILIST, "123", WorkKind.MANGA),
                "it",
            )
            assertEquals(WorkKind.MANGA, result.work.reference.kind)
            assertEquals("Story & Art", result.creators.single().role)
            assertEquals(20, result.chapters)
            assertTrue(result.moreCreators)
            assertEquals(WorkKind.ANIME, result.relations.single().work.reference.kind)
            assertEquals("ADAPTATION", result.relations.single().relation)
        } finally {
            client.close()
        }
    }
}
