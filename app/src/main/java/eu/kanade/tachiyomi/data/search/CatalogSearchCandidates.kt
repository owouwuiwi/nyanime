package eu.kanade.tachiyomi.data.search

import eu.kanade.tachiyomi.data.discovery.AnilistCatalogTransport
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import tachiyomi.domain.search.LexicalTitleMatcher
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchRequestBudget
import tachiyomi.domain.search.SearchTitle
import tachiyomi.domain.search.TitleNormalizer
import java.io.IOException

/** Three requests at most per session, independent of how many extensions are searched. */
class CatalogSearchCandidates(private val client: OkHttpClient, private val json: Json) {
    private val aniList = AnilistCatalogTransport(client, json)
    private val kitsuGate = Mutex()
    private var kitsuNextAt = 0L
    private val matcher = LexicalTitleMatcher()

    suspend fun fetch(query: String, medium: SearchMedium, budget: SearchRequestBudget): List<SearchTitle> {
        val found = mutableListOf<SearchTitle>()
        var failure: Exception? = null
        val base = TitleNormalizer.trailingNumberBase(query)
        val queries = listOf(query, base ?: TitleNormalizer.words(query)).distinct().take(2)
        for (variant in queries) {
            if (!budget.take()) return found
            try {
                found += kitsu(variant, medium)
                if (matcher.rank(query, found).firstOrNull()?.score?.let { it >= 85 } == true) return found
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                break // Includes 429: do not amplify a rejected request.
            }
        }
        try {
            if (budget.take()) found += anilist(base ?: query, medium)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        if (found.isEmpty() && failure != null) throw failure
        return found.distinctBy { it.key }
    }

    private suspend fun kitsu(query: String, medium: SearchMedium): List<SearchTitle> = kitsuGate.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        if (kitsuNextAt > now) delay(kitsuNextAt - now)
        kitsuNextAt = android.os.SystemClock.elapsedRealtime() + 1_100
        val type = if (medium == SearchMedium.VIDEO) "anime" else "manga"
        val url = "https://kitsu.io/api/edge/$type".toHttpUrl().newBuilder()
            .addQueryParameter("filter[text]", query).addQueryParameter("page[limit]", "12").build()
        client.newCall(GET(url.toString())).await().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 429) {
                    val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 300) ?: 60
                    kitsuNextAt = android.os.SystemClock.elapsedRealtime() + seconds * 1_000
                }
                throw IOException("Catalog HTTP ${response.code}")
            }
            parseKitsu(json.parseToJsonElement(response.body.string()) as JsonObject, medium)
        }
    }

    private suspend fun anilist(query: String, medium: SearchMedium): List<SearchTitle> {
        val data = aniList.execute(
            "query(\u0024search:String!,\u0024type:MediaType!){Page(perPage:12){" +
                "media(search:\u0024search,type:\u0024type){id title{romaji english native} synonyms}}}",
            buildJsonObject {
                put("search", query)
                put(
                    "type",
                    if (medium ==
                        SearchMedium.VIDEO
                    ) {
                        "ANIME"
                    } else {
                        "MANGA"
                    },
                )
            },
        )
        return parseAniList(data, medium)
    }

    companion object {
        fun parseKitsu(root: JsonObject, medium: SearchMedium): List<SearchTitle> =
            (root["data"] as? JsonArray).orEmpty().mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                val attributes = item["attributes"] as? JsonObject ?: return@mapNotNull null
                val names = (attributes["titles"] as? JsonObject).orEmpty().values.mapNotNull(::text) +
                    (attributes["abbreviatedTitles"] as? JsonArray).orEmpty().mapNotNull(::text)
                val title = text(attributes["canonicalTitle"]) ?: names.firstOrNull() ?: return@mapNotNull null
                val id = text(item["id"]) ?: return@mapNotNull null
                SearchTitle("kitsu:$medium:$id", title, medium, names.distinct().take(15), "kitsu")
            }

        fun parseAniList(root: JsonObject, medium: SearchMedium): List<SearchTitle> =
            ((root["Page"] as? JsonObject)?.get("media") as? JsonArray).orEmpty().mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                val names = (item["title"] as? JsonObject).orEmpty().values.mapNotNull(::text) +
                    (item["synonyms"] as? JsonArray).orEmpty().mapNotNull(::text)
                val id = text(item["id"]) ?: return@mapNotNull null
                SearchTitle(
                    "anilist:$medium:$id",
                    names.firstOrNull() ?: return@mapNotNull null,
                    medium,
                    names.distinct().take(15),
                    "anilist",
                )
            }

        private fun text(value: kotlinx.serialization.json.JsonElement?) = (value as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 256 }
    }
}
