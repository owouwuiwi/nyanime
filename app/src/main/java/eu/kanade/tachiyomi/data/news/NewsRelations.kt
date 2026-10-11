package eu.kanade.tachiyomi.data.news

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import nyanime.news.api.NewsCatalogId
import nyanime.news.api.NewsMedium
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Direct catalog edges only. No title search, inferred franchise or tracker mutation. */
class NewsRelations(network: NetworkHelper, private val store: NewsStore) {
    private val client = network.client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun refresh(roots: Set<NewsCatalogId>) {
        val now = System.currentTimeMillis()
        val snapshot = store.state.value
        val due = roots.filter {
            it.provider in setOf("anilist", "myanimelist") &&
                it.value.toLongOrNull()?.let { value -> value > 0 } == true
        }
            // Separate from old graph timestamps so existing installations fetch names once after upgrading.
            .filter { now - (snapshot.catalogChecks[NewsRules.catalogKey(it)] ?: 0) >= 86_400_000 }
            .filter { it.value.toIntOrNull() != null }
            .sortedBy { snapshot.catalogChecks[NewsRules.catalogKey(it)] ?: 0 }.take(150)
        for ((group, allIds) in due.groupBy { it.provider to it.medium }) {
            for (ids in allIds.chunked(50)) {
                val field = if (group.first == "anilist") "id_in" else "idMal_in"
                val query = "query(\$ids:[Int],\$type:MediaType){Page(perPage:50){media($field:\$ids,type:\$type){" +
                    "id idMal type title{romaji english native} synonyms " +
                    "relations{edges{relationType node{id idMal type title{romaji english native} synonyms}}}}}}"
                val request = buildJsonObject {
                    put("query", query)
                    put(
                        "variables",
                        buildJsonObject {
                            putJsonArray("ids") {
                                ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.value.toLong())) }
                            }
                            put("type", group.second.name)
                        },
                    )
                }
                try {
                    val response = client.newCall(
                        POST("https://graphql.anilist.co", body = request.toString().toRequestBody(jsonMime)),
                    ).awaitSuccess().use { response ->
                        Json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject
                    }
                    require(response["errors"] == null)
                    val media = response["data"]!!.jsonObject["Page"]!!.jsonObject["media"]!!.jsonArray
                    val relations = mutableMapOf<String, Set<NewsCatalogId>>()
                    val works = mutableMapOf<String, NewsCatalogWork>()
                    for (element in media) {
                        val root = element.jsonObject
                        val rootId = root[
                            if (group.first ==
                                "anilist"
                            ) {
                                "id"
                            } else {
                                "idMal"
                            },
                        ]?.jsonPrimitive?.longOrNull?.toString()
                        val requested = ids.firstOrNull { it.value == rootId } ?: continue
                        require(root["type"]?.jsonPrimitive?.content == requested.medium.name)
                        val rootWork = parseWork(root) ?: continue
                        works[NewsRules.catalogKey(rootWork.ids.first())] = rootWork
                        val edges = root["relations"]?.jsonObject?.get("edges")?.jsonArray.orEmpty()
                        val matched = edges.filter {
                            it.jsonObject["relationType"]?.jsonPrimitive?.content in
                                setOf("SEQUEL", "PREQUEL", "ADAPTATION", "SOURCE")
                        }
                            .flatMap { edge ->
                                val node = edge.jsonObject["node"]!!.jsonObject
                                val work = parseWork(node) ?: return@flatMap emptyList()
                                works[NewsRules.catalogKey(work.ids.first())] = work
                                work.ids.toList()
                            }.toSet()
                        // The same work's IDs are equivalent; anime and manga IDs stay typed.
                        relations[NewsRules.catalogKey(requested)] = matched + rootWork.ids
                    }
                    store.update {
                        it.copy(
                            relations = it.relations + relations,
                            relationChecks =
                            it.relationChecks + ids.associate { id -> NewsRules.catalogKey(id) to now },
                            catalogChecks = it.catalogChecks + ids.associate { id -> NewsRules.catalogKey(id) to now },
                            works = (it.works + works).entries.toList().takeLast(1500)
                                .associate { item -> item.toPair() },
                        )
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Preserve known relations and postpone failed requests for one hour.
                    store.update {
                        it.copy(
                            relationChecks =
                            it.relationChecks +
                                ids.associate { id ->
                                    NewsRules.catalogKey(id) to (now - 23 * 3_600_000)
                                },
                            catalogChecks = it.catalogChecks +
                                ids.associate { id ->
                                    NewsRules.catalogKey(id) to (now - 23 * 3_600_000)
                                },
                        )
                    }
                }
            }
        }
    }

    companion object {
        internal fun parseWork(node: JsonObject): NewsCatalogWork? {
            val medium = NewsMedium.entries.firstOrNull {
                it.name == node["type"]?.jsonPrimitive?.content
            } ?: return null
            val ids = listOf("anilist" to "id", "myanimelist" to "idMal").mapNotNull { (provider, key) ->
                node[key]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let {
                    NewsCatalogId(provider, it.toString(), medium)
                }
            }.toSet()
            if (ids.isEmpty()) return null
            val names = (
                node["title"]?.jsonObject?.values.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } +
                    node["synonyms"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                )
                .map(String::trim).filter { it.length in 3..256 }.distinct().take(32)
            return names.firstOrNull()?.let { NewsCatalogWork(ids, it, names, medium) }
        }
    }
}
