package eu.kanade.tachiyomi.ui.player.utils

import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Original integration: https://github.com/saikou-app/saikou/blob/main/app/src/main/java/ani/saikou/others/AniSkip.kt
class AniSkipApi(private val client: Call.Factory = sharedClient) {
    suspend fun getResult(malId: Long, episodeNumber: Double, episodeLength: Long): List<TimeStamp>? =
        withContext(Dispatchers.IO) {
            if (malId <= 0 || !episodeNumber.isFinite() || episodeNumber < 0 || episodeLength <= 0) {
                return@withContext null
            }
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber".toHttpUrl().newBuilder()
                .apply {
                    listOf("op", "ed", "mixed-op", "mixed-ed", "recap").forEach { addQueryParameter("types[]", it) }
                }
                .addQueryParameter("episodeLength", episodeLength.toString())
                .build()
            try {
                client.newCall(GET(url.toString())).awaitSuccess().use { response ->
                    parseResult(response.body.string(), episodeLength)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    suspend fun getMalIdFromAL(id: Long): Long? = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext null
        val body = buildJsonObject {
            put("query", "query { Media(id: $id, type: ANIME) { idMal } }")
        }.toString().toRequestBody(jsonMime)
        try {
            client.newCall(POST("https://graphql.anilist.co", body = body)).awaitSuccess().use { response ->
                json.decodeFromString<AnilistResponse>(response.body.string()).data?.media?.idMal?.takeIf { it > 0 }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        internal fun parseResult(body: String, episodeLength: Long): List<TimeStamp>? {
            val response = json.decodeFromString<AniSkipResponse>(body)
            if (!response.found) return null
            return response.results.orEmpty().mapNotNull { stamp ->
                val interval = stamp.interval ?: return@mapNotNull null
                val type = stamp.skipType ?: return@mapNotNull null
                if (!interval.startTime.isFinite() ||
                    !interval.endTime.isFinite() ||
                    interval.startTime < 0 ||
                    interval.endTime <= interval.startTime ||
                    interval.endTime > episodeLength
                ) {
                    return@mapNotNull null
                }
                TimeStamp(interval.startTime, interval.endTime, type.getString(), type.toChapterType())
            }.distinct().sortedBy { it.start }.takeIf { it.isNotEmpty() }
        }
    }
}

@Serializable
private data class AnilistResponse(val data: AnilistData? = null)

@Serializable
private data class AnilistData(@SerialName("Media") val media: AnilistMedia? = null)

@Serializable
private data class AnilistMedia(val idMal: Long? = null)

@Serializable
data class AniSkipResponse(val found: Boolean, val results: List<Stamp>? = null)

@Serializable
data class Stamp(val interval: AniSkipInterval? = null, val skipType: SkipType? = null)

@Serializable
enum class SkipType {
    @SerialName("op")
    OP,

    @SerialName("ed")
    ED,

    @SerialName("recap")
    RECAP,

    @SerialName("mixed-op")
    MIXED_OP,

    @SerialName("mixed-ed")
    MIXED_ED,

    ;

    fun getString(): String = when (this) {
        OP -> "Opening"
        ED -> "Ending"
        RECAP -> "Recap"
        MIXED_OP -> "Mixed-op"
        MIXED_ED -> "Mixed-ed"
    }

    fun toChapterType(): ChapterType = when (this) {
        OP -> ChapterType.Opening
        ED, MIXED_ED -> ChapterType.Ending
        RECAP -> ChapterType.Recap
        MIXED_OP -> ChapterType.MixedOp
    }
}

@Serializable
data class AniSkipInterval(val startTime: Double = -1.0, val endTime: Double = -1.0)
