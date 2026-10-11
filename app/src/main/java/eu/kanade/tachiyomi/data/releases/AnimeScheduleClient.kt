package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI

internal class AnimeScheduleException(val reason: String, val retryAt: Long = 0, val status: Int = 0) : Exception(
    reason,
)

/** Only transport failures mean unreachable. Never expose exception messages or response bodies. */
internal fun animeScheduleFailureReason(error: Exception): String = when (error) {
    is AnimeScheduleException -> error.reason
    is IOException -> "NETWORK"
    is IllegalArgumentException, is IllegalStateException -> "DATA"
    else -> "LOCAL"
}

/** Application tokens only: OAuth account tokens cannot authorize these catalogue endpoints. */
internal class AnimeScheduleClient(
    private val client: OkHttpClient,
    private val token: () -> String?,
    private val endpoint: HttpUrl = "https://animeschedule.net/api/v3/".toHttpUrl(),
    private val spacingMillis: Long = 750,
) {
    private val mutex = Mutex()
    private var nextRequestAt = 0L

    suspend fun timetable(year: Int, week: Int, allowUnavailable: Boolean = false): String = request(
        "timetables/all",
        listOf("year" to "$year", "week" to "$week", "tz" to "UTC"),
        allowUnavailable,
    ).also { ScheduleParser.timetable(it) }

    suspend fun detail(route: String): JsonObject {
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,199}").matches(route))
        return kotlinx.serialization.json.Json.parseToJsonElement(request("anime/$route", emptyList())).jsonObject
    }

    suspend fun resolve(reference: AiringCatalogReference, anidb: Long?): JsonObject? {
        val filter = when {
            reference.anilistId != null -> "anilist-ids" to "${reference.anilistId}"
            reference.malId != null -> "mal-ids" to "${reference.malId}"
            anidb != null && anidb > 0 -> "anidb-ids" to "$anidb"
            else -> return null
        }
        val matches = mutableListOf<JsonObject>()
        var page = 1
        do {
            val body = try {
                request("anime", listOf(filter, "page" to "$page"))
            } catch (error: AnimeScheduleException) {
                // The API returns 404, rather than an empty page, when no anime has this ID.
                if (error.reason == "IDENTITY" && page == 1) return null
                throw error
            }
            val rows = ScheduleParser.animePage(body)
            matches += rows.filter { matchesReference(it, reference, anidb) }
            require(page < 100 || rows.size < 18) { "Schedule pagination did not terminate" }
            page++
        } while (rows.size == 18)
        return matches.distinctBy { ScheduleParser.text(it, "route") }.singleOrNull()
    }

    private suspend fun request(
        path: String,
        parameters: List<Pair<String, String>>,
        allowUnavailableTimetable: Boolean = false,
    ): String = mutex.withLock {
        val now = System.currentTimeMillis()
        if (nextRequestAt - now > 5_000) throw AnimeScheduleException("RATE_LIMIT", nextRequestAt)
        if (nextRequestAt > now) delay(nextRequestAt - now)
        val credential = token()?.takeIf { it.isNotBlank() } ?: throw AnimeScheduleException("AUTH")
        val url = endpoint.newBuilder().addPathSegments(path).apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        client.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer $credential").build(),
        ).await().use { response ->
            val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
            nextRequestAt = System.currentTimeMillis() + spacingMillis
            if (response.header("X-RateLimit-Remaining") ==
                "0"
            ) {
                nextRequestAt = maxOf(nextRequestAt, reset ?: nextRequestAt)
            }
            when (response.code) {
                401, 403 -> throw AnimeScheduleException("AUTH", status = response.code)
                429 -> {
                    nextRequestAt = maxOf(nextRequestAt, reset ?: (System.currentTimeMillis() + 60_000))
                    throw AnimeScheduleException("RATE_LIMIT", nextRequestAt, response.code)
                }
                404 -> {
                    // Unpublished weeks return an empty 404, even with a valid application token.
                    // Connection checks and the current week remain strict to catch an endpoint failure.
                    if (path == "timetables/all" && allowUnavailableTimetable) return@use "[]"
                    if (path == "anime" || path.startsWith("anime/")) {
                        throw AnimeScheduleException("IDENTITY", status = response.code)
                    }
                }
            }
            if (!response.isSuccessful) throw AnimeScheduleException("SERVER", status = response.code)
            response.body.string()
        }
    }

    companion object {
        private fun catalogId(url: String, hosts: Set<String>, prefix: String): Long? = try {
            val normalized = when {
                url.startsWith("//") -> "https:$url"
                "://" !in url -> "https://$url"
                else -> url
            }
            val uri = URI(normalized)
            if (uri.scheme !in setOf("https", "http") ||
                uri.userInfo != null ||
                uri.host?.lowercase() !in hosts ||
                !uri.path.startsWith(prefix)
            ) {
                null
            } else {
                uri.path.removePrefix(prefix).substringBefore('/').toLongOrNull()?.takeIf { it > 0 }
            }
        } catch (_: Exception) {
            null
        }

        fun matchesReference(metadata: JsonObject, reference: AiringCatalogReference, anidb: Long?): Boolean {
            val websites = metadata["websites"] as? JsonObject ?: return false
            val ani =
                catalogId(ScheduleParser.text(websites, "aniList"), setOf("anilist.co", "www.anilist.co"), "/anime/")
            val mal =
                catalogId(
                    ScheduleParser.text(websites, "mal"),
                    setOf("myanimelist.net", "www.myanimelist.net"),
                    "/anime/",
                )
            val db = catalogId(ScheduleParser.text(websites, "anidb"), setOf("anidb.net", "www.anidb.net"), "/anime/")
            return when {
                reference.anilistId != null ->
                    ani == reference.anilistId &&
                        (reference.expectedMalId == null || mal == reference.expectedMalId)
                reference.malId != null -> mal == reference.malId
                else -> anidb != null && db == anidb
            }
        }
    }
}
