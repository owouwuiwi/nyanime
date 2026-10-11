package eu.kanade.tachiyomi.util

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.simkl.Simkl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.ui.entries.anime.track.AnimeTrackItem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.entries.anime.model.Anime
import java.time.OffsetDateTime
import java.util.Calendar

class AniChartApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    internal suspend fun loadAiringTime(
        anime: Anime,
        trackItems: List<AnimeTrackItem>,
        manualFetch: Boolean,
    ): Pair<Int, Long>? {
        if (anime.status == SAnime.COMPLETED.toLong() && !manualFetch) return null

        val item = trackItems.firstOrNull {
            it.track != null && (it.tracker is Anilist || it.tracker is MyAnimeList || it.tracker is Simkl)
        } ?: return null
        val remoteId = item.track?.remoteId?.takeIf { it > 0L } ?: return null
        return when (item.tracker) {
            is Anilist -> getAnilistAiringEpisodeData(remoteId)
            is MyAnimeList -> getAlIdFromMal(remoteId)?.let { getAnilistAiringEpisodeData(it) }
            is Simkl -> getSimklAiringEpisodeData(remoteId)
            else -> null
        }
    }

    private suspend fun queryAniList(query: String): String? = withIOContext {
        try {
            client.newCall(
                POST(
                    "https://graphql.anilist.co",
                    body = buildJsonObject { put("query", query) }.toString().toRequestBody(jsonMime),
                ),
            ).execute().use { response ->
                if (response.isSuccessful) response.body.string() else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAniListMedia(body: String): JsonObject? = try {
        val root = Json.parseToJsonElement(body) as? JsonObject
        if (root?.get("errors") != null) null else (root?.get("data") as? JsonObject)?.get("Media") as? JsonObject
    } catch (e: Exception) {
        null
    }

    private suspend fun getAlIdFromMal(idMal: Long): Long? {
        val query = """
            query {
                Media(idMal:$idMal,type: ANIME) {
                    id
                }
            }
        """.trimIndent()
        val media = queryAniList(query)?.let(::parseAniListMedia) ?: return null
        return (media["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
    }

    private suspend fun getAnilistAiringEpisodeData(id: Long): Pair<Int, Long>? {
        val query = """
            query {
                Media(id:$id) {
                    nextAiringEpisode {
                        episode
                        airingAt
                    }
                }
            }
        """.trimIndent()
        return queryAniList(query)?.let(::parseAniListAiringResponse)
    }

    internal fun parseAniListAiringResponse(body: String): Pair<Int, Long>? {
        val media = parseAniListMedia(body) ?: return null
        val next = media["nextAiringEpisode"] ?: return null
        if (next == JsonNull) return 1 to 0L
        val airing = next as? JsonObject ?: return null
        val episode = (airing["episode"] as? JsonPrimitive)?.intOrNull ?: return null
        val airingAt = (airing["airingAt"] as? JsonPrimitive)?.longOrNull ?: return null
        return if (episode > 0 && airingAt > 0L) episode to airingAt else null
    }

    private suspend fun getSimklAiringEpisodeData(id: Long): Pair<Int, Long>? {
        var episodeNumber = 1
        var airingAt = 0L
        return withIOContext {
            val calendarTypes = listOf("anime", "tv", "movie_release")
            calendarTypes.forEach {
                val body = try {
                    client.newCall(GET("https://data.simkl.in/calendar/$it.json")).execute().use { response ->
                        if (response.isSuccessful) response.body.string() else return@withIOContext null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withIOContext null
                }
                try {
                    Json.parseToJsonElement(body)
                } catch (e: Exception) {
                    return@withIOContext null
                }

                val data = removeAiredSimkl(body) ?: return@withIOContext null

                val malId = data.substringAfter("\"simkl_id\":$id,", "").substringAfter(
                    "\"mal\":\"",
                ).substringBefore("\"").toLongOrNull() ?: 0L
                if (malId != 0L) {
                    val anilistId = getAlIdFromMal(malId) ?: return@withIOContext null
                    return@withIOContext getAnilistAiringEpisodeData(anilistId)
                }

                val epNum = data.substringAfter("\"simkl_id\":$id,", "").substringBefore("\"}}").substringAfterLast(
                    "\"episode\":",
                )
                episodeNumber = epNum.substringBefore(",").toIntOrNull() ?: episodeNumber

                val date = data.substringBefore("\"simkl_id\":$id,", "").substringAfterLast(
                    "\"date\":\"",
                ).substringBefore("\"")
                airingAt = if (date.isNotBlank()) {
                    try {
                        toUnixTimestamp(date)
                    } catch (e: Exception) {
                        return@withIOContext null
                    }
                } else {
                    airingAt
                }

                if (airingAt != 0L) return@withIOContext Pair(episodeNumber, airingAt)
            }
            return@withIOContext Pair(episodeNumber, airingAt)
        }
    }

    private fun removeAiredSimkl(body: String): String? {
        val currentTimeInMillis = Calendar.getInstance().timeInMillis
        val index = body.split("\"date\":\"").drop(1).indexOfFirst {
            val date = it.substringBefore("\"")
            val time = try {
                if (date.isNotBlank()) toUnixTimestamp(date) else 0L
            } catch (e: Exception) {
                return null
            }
            time.times(1000) > currentTimeInMillis
        }
        return if (index >= 0) body.substring(index) else ""
    }

    private fun toUnixTimestamp(dateFormat: String): Long {
        val offsetDateTime = OffsetDateTime.parse(dateFormat)
        val instant = offsetDateTime.toInstant()
        return instant.epochSecond
    }
}
