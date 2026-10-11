package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared, ID-verified chapter references for both directions of the adaptation link. */
internal object AdaptationChapterCatalog {
    data class Metadata(
        val beginning: List<AnimeMangaContinuity.Checkpoint> = emptyList(),
        val ending: List<AnimeMangaContinuity.Checkpoint> = emptyList(),
        val coverUrl: String? = null,
    ) {
        fun forAdaptation(format: String?, context: AniListMediaLookup.AdaptationContext): Range {
            // A film, special or unidentified sequel must not inherit a TV season's chapters.
            if (format !in setOf("TV", "TV_SHORT", "ONA") || context.season == null) return Range()
            val unscoped = context.standaloneSeason && context.season == 1
            return Range(
                AnimeMangaContinuity.selectCheckpoint(beginning, context.season, unscoped),
                AnimeMangaContinuity.selectCheckpoint(ending, context.season, unscoped),
            ).validated()
        }
    }

    data class Range(
        val beginning: AnimeMangaContinuity.Checkpoint? = null,
        val ending: AnimeMangaContinuity.Checkpoint? = null,
    ) {
        val complete: Boolean get() = beginning != null && ending != null

        fun withFallback(other: Range) = Range(beginning ?: other.beginning, ending ?: other.ending).validated()

        fun validated(): Range =
            if (beginning != null && ending != null && beginning.chapter > ending.chapter) Range() else this
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LinkedHashMap<Pair<String, Long>, Pair<Long, Metadata>>(64, 0.75f, true)

    // Concurrent requests for the same catalog record share one fetch, including reverse navigation.
    private val gates = List(8) { Mutex() }

    suspend fun mangaBaka(id: Long): Metadata = cached("anilist" to id) {
        client.newCall(GET("https://api.mangabaka.org/v1/source/anilist/$id")).awaitSuccess().use {
            parseMangaBaka(it.body.string(), id)
        }
    }

    suspend fun mangaUpdates(id: Long): Metadata = cached("mangaupdates" to id) {
        client.newCall(GET("https://api.mangaupdates.com/v1/series/$id")).awaitSuccess().use {
            metadata(json.parseToJsonElement(it.body.string()).jsonObject.obj("anime"))
        }
    }

    private suspend fun cached(key: Pair<String, Long>, fetch: suspend () -> Metadata): Metadata =
        withContext(Dispatchers.IO) {
            if (key.second <= 0) return@withContext Metadata()
            gates[(key.hashCode() and Int.MAX_VALUE) % gates.size].withLock {
                synchronized(cache) {
                    cache[key]?.takeIf { it.first > System.currentTimeMillis() }?.second
                }?.let { return@withLock it }
                try {
                    val result = fetch()
                    synchronized(cache) {
                        cache[key] = (System.currentTimeMillis() + 24 * 60 * 60 * 1000L) to result
                        if (cache.size > 64) cache.remove(cache.keys.first())
                    }
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed fetch remains retryable; it must not become a cached empty catalog record.
                    Metadata()
                }
            }
        }

    internal fun parseMangaBaka(raw: String, id: Long): Metadata {
        val series = json.parseToJsonElement(raw).jsonObject.obj("data")?.get("series")?.jsonArray.orEmpty()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .filter { it.obj("source")?.obj("anilist")?.get("id")?.jsonPrimitive?.longOrNull == id }
            .singleOrNull() ?: return Metadata()
        // Merged/duplicated mappings cannot silently provide a chapter range.
        return metadata(series.obj("anime")).copy(
            coverUrl = series.obj("cover")?.obj("x250")?.string("x1")
                ?: series.obj("cover")?.obj("raw")?.string("url"),
        )
    }

    private fun metadata(anime: JsonObject?) = Metadata(
        AnimeMangaContinuity.checkpoints(anime?.string("start")),
        AnimeMangaContinuity.checkpoints(anime?.string("end")),
    )

    private fun JsonObject.obj(key: String): JsonObject? = runCatching { get(key)?.jsonObject }.getOrNull()
    private fun JsonObject.string(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
}
