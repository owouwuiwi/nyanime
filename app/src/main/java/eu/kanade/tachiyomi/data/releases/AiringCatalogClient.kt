package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

internal class AiringCatalogIdentityException(message: String) : Exception(message)

/** Only exact catalog IDs are used; an invalid ID never triggers a title search. */
internal class AiringCatalogClient(
    private val client: OkHttpClient,
    private val endpoint: String = "https://graphql.anilist.co",
    private val spacingMillis: Long = 2_500,
) {
    private val gate = Mutex()
    private var nextRequestAt = 0L

    suspend fun page(reference: AiringCatalogReference, page: Int): String = gate.withLock {
        try {
            request(reference.anilistId, reference.malId, reference.expectedMalId, page)
        } catch (e: HttpException) {
            if (e.code != 404) throw e
            if (reference.anilistId == null || reference.malId == null) throw missing()
            // A linked alternative ID is usable only after the catalog confirms its exact mapping.
            try {
                request(null, reference.malId, reference.malId, page)
            } catch (e: HttpException) {
                if (e.code == 404) throw missing()
                throw e
            }
        }
    }

    private suspend fun request(id: Long?, mal: Long?, expectedMal: Long?, page: Int): String {
        if (id == null && mal == null) throw missing()
        val selector = if (id != null) "id: $id" else "idMal: $mal"
        val query = """
            query { Media($selector, type: ANIME) { id idMal status episodes
              airingSchedule(page: $page, perPage: 50, notYetAired: true) {
                pageInfo { hasNextPage }
                nodes { episode airingAt }
              }
            } }
        """.trimIndent()
        val wait = nextRequestAt - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val body = try {
            client.newCall(
                POST(
                    endpoint,
                    body = buildJsonObject { put("query", query) }.toString().toRequestBody(jsonMime),
                ),
            ).await().use { response ->
                if (response.code == 429) {
                    val retry = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60
                    nextRequestAt = System.currentTimeMillis() + retry * 1000
                }
                if (!response.isSuccessful) throw HttpException(response.code)
                response.body.string()
            }
        } finally {
            nextRequestAt = maxOf(nextRequestAt, System.currentTimeMillis() + spacingMillis)
        }
        val root = Json.parseToJsonElement(body).jsonObject
        require(root["errors"] == null) { "Airing query failed" }
        val media = root["data"]?.jsonObject?.get("Media") as? JsonObject ?: throw missing()
        val catalogId = media["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: throw missing()
        if ((id != null && catalogId != id) ||
            (expectedMal != null && media["idMal"]?.jsonPrimitive?.longOrNull != expectedMal)
        ) {
            throw AiringCatalogIdentityException("Catalog IDs refer to different titles")
        }
        return body
    }

    private fun missing() = AiringCatalogIdentityException("Catalog identity was not found")
}
