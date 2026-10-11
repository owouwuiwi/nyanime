package it.lorelore.nyanime.tracking

import android.content.Context
import eu.kanade.tachiyomi.data.discovery.AnilistCatalogTransport
import eu.kanade.tachiyomi.data.discovery.number
import eu.kanade.tachiyomi.data.discovery.obj
import eu.kanade.tachiyomi.data.discovery.objects
import eu.kanade.tachiyomi.data.discovery.text
import eu.kanade.tachiyomi.data.workinfo.FileWorkInfoCache
import eu.kanade.tachiyomi.data.workinfo.WorkCacheEntry
import eu.kanade.tachiyomi.data.workinfo.WorkKind
import eu.kanade.tachiyomi.data.workinfo.WorkProvider
import eu.kanade.tachiyomi.data.workinfo.WorkReference
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class CommunityRatings(val counts: List<Int>, val average: Double? = null, val totalParts: Long? = null) {
    val total: Long get() = counts.sumOf(Int::toLong)
    fun valid() = counts.size == 10 &&
        counts.all { it in 0..100_000_000 } &&
        (average == null || average.isFinite() && average in 0.0..10.0) &&
        (totalParts == null || totalParts in 1..1_000_000)
}

/** Public IDs only; the caller owns catalogue consent, offline and incognito policy. */
class CommunityRatingsRepository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = FileWorkInfoCache(File(context.cacheDir, "work-info-v1"), json)
    private val gate = Mutex()
    private val transport by lazy {
        AnilistCatalogTransport(
            Injekt.get<NetworkHelper>().client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
                .cache(null).callTimeout(12, TimeUnit.SECONDS).build(),
            json,
        )
    }

    suspend fun load(reference: WorkReference, network: Boolean, persist: Boolean): CommunityRatings? = gate.withLock {
        require(reference.isValid() && reference.provider == WorkProvider.ANILIST)
        val key = "tracking-ratings-v1:$reference"
        val cached = cache.read(key)
        val ratings = cached?.let {
            runCatching {
                json.decodeFromString<CommunityRatings>(it.payload).takeIf(CommunityRatings::valid)
            }.getOrNull()
        }
        if (!network || ratings != null && cached.fresh(System.currentTimeMillis())) return@withLock ratings
        val type = if (reference.kind == WorkKind.MANGA) "MANGA" else "ANIME"
        val media = transport.execute(
            "query(\$id: Int!, \$type: MediaType!) { Media(id: \$id, type: \$type) { " +
                "id type averageScore episodes chapters stats { scoreDistribution { score amount } } } }",
            buildJsonObject {
                put("id", reference.id.toInt())
                put("type", type)
            },
        ).obj("Media")
        val result = parseCommunityRatings(media, reference)
        if (persist) {
            cache.write(key, WorkCacheEntry(System.currentTimeMillis(), json.encodeToString(result)))
        }
        result
    }
}

internal fun parseCommunityRatings(media: JsonObject, reference: WorkReference): CommunityRatings {
    require(reference.provider == WorkProvider.ANILIST && reference.isValid())
    val type = if (reference.kind == WorkKind.MANGA) "MANGA" else "ANIME"
    check(media.number("id")?.toString() == reference.id && media.text("type") == type)
    val buckets = IntArray(10)
    val seen = mutableSetOf<Int>()
    media.obj("stats").objects("scoreDistribution").forEach { bucket ->
        val score = bucket.number("score") ?: error("Missing score")
        val amount = bucket.number("amount") ?: error("Missing amount")
        check(score in 10..100 && score % 10 == 0 && amount in 0..100_000_000 && seen.add(score))
        buckets[score / 10 - 1] = amount
    }
    val parts = media.number(
        if (reference.kind ==
            WorkKind.MANGA
        ) {
            "chapters"
        } else {
            "episodes"
        },
    )?.toLong()?.takeIf { it > 0 }
    return CommunityRatings(buckets.toList(), media.number("averageScore")?.div(10.0), parts).also { check(it.valid()) }
}
