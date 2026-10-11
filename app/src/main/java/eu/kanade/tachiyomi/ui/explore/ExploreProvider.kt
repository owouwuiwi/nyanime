package eu.kanade.tachiyomi.ui.explore

import android.content.Context
import eu.kanade.tachiyomi.data.addon.RinAddonRuntime
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nyanime.addon.api.ExploreFeedV1
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.discovery.CatalogAnime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.util.zip.ZipFile

internal val exploreJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
internal data class ExploreClip(
    val anime: CatalogAnime,
    val youtubeId: String,
    val metric: ExploreMetric? = null,
) {
    val key get() = "${anime.id.provider}:${anime.id.value}"

    fun validated(): ExploreClip? {
        if (anime.id.value <= 0 ||
            !anime.id.provider.matches(PROVIDER) ||
            anime.title.isBlank() ||
            anime.title.length > 400 ||
            !youtubeId.matches(YOUTUBE_ID)
        ) return null
        return copy(
            anime = anime.copy(
                cover = anime.cover?.takeIf(::https),
                banner = anime.banner?.takeIf(::https),
                alternateTitles = anime.alternateTitles.take(12).map { it.take(400) },
                synopsis = anime.synopsis?.take(16_384),
            ),
            metric = metric?.takeIf { it.value >= 0 && it.label.length in 1..80 && it.label.none(Char::isISOControl) },
        )
    }

    companion object {
        private val PROVIDER = Regex("[a-z][a-z0-9_-]{0,31}")
        private val YOUTUBE_ID = Regex("[a-zA-Z0-9_-]{11}")
        private fun https(value: String): Boolean = value.length <= 2048 && runCatching {
            val url = URI(value)
            url.scheme == "https" && url.host != null && url.rawUserInfo == null
        }.getOrDefault(false)
    }
}

@Serializable internal data class ExploreMetric(val label: String, val value: Long, val kind: String = "")

@Serializable
internal data class ExplorePage(val items: List<ExploreClip>, val hasMore: Boolean)

@Serializable
internal data class ExploreSaved(
    val liked: List<ExploreClip> = emptyList(),
    val favorite: List<ExploreClip> = emptyList(),
    val watchlist: List<ExploreClip> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val seen: List<String> = emptyList(),
) {
    fun rememberSeen(key: String) = if (seen.lastOrNull() == key) this else copy(
        seen = (seen.filterNot { it == key } + key).takeLast(200),
    )
}

@Serializable
internal data class ExplorePreferences(
    val seed: Int,
    val affinity: Map<String, Int>,
    val hidden: Set<String>,
    val seen: List<String>,
    val delivered: List<String>,
)

internal class ExploreProvider(private val context: Context, private val record: RinRecord) {
    private val preferences = context.getSharedPreferences(
        RinAddonRuntime.namespace(record) + "_explore",
        Context.MODE_PRIVATE,
    )

    fun saved(): ExploreSaved = preferences.getString("saved", null)?.let {
        runCatching { exploreJson.decodeFromString<ExploreSaved>(it) }.getOrNull()
    } ?: ExploreSaved()

    fun save(value: ExploreSaved) {
        preferences.edit().putString("saved", exploreJson.encodeToString(ExploreSaved.serializer(), value)).apply()
    }

    private val network by lazy {
        val client = Injekt.get<NetworkHelper>().catalogueClient.newBuilder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        ExploreFeedV1.Network { url, payload ->
            val endpoint = URI(url)
            require(url.length <= 2048 && endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() &&
                endpoint.rawUserInfo == null && endpoint.rawFragment == null && payload.length <= 1_048_576)
            val request = Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Explore feed HTTP ${response.code}")
                RinRepository.boundedRead(response.body.byteStream(), 2_097_152).decodeToString()
            }
        }
    }

    suspend fun load(page: Int, seed: Int, saved: ExploreSaved, delivered: List<ExploreClip>): ExplorePage =
        runInterruptible(Dispatchers.IO) {
            val addon = RinAddonRuntime.load(context, record.manifest.packageName)
            check(addon.record.sha256 == record.sha256)
            val entry = addon.entry as ExploreFeedV1
            val affinity = mutableMapOf<String, Int>()
            fun add(items: List<ExploreClip>, weight: Int) {
                items.takeLast(100).forEach { clip ->
                    (clip.anime.genres + clip.anime.studios).distinct().forEach { tag ->
                        affinity[tag] = (affinity[tag] ?: 0) + weight
                    }
                }
            }
            add(saved.liked, 3)
            add(saved.favorite, 4)
            add(saved.watchlist, 5)
            val request = ExplorePreferences(seed, affinity, saved.hidden, saved.seen, delivered.map { it.key })
            val response = entry.loadPage(page, exploreJson.encodeToString(ExplorePreferences.serializer(), request), network)
            check(response.length <= 1_048_576)
            val decoded = exploreJson.decodeFromString<ExplorePage>(response)
            val active = RinAddonRuntime.active(context, record.manifest.packageName)
            check(active.sha256 == record.sha256)
            decoded.copy(items = decoded.items.take(50).mapNotNull(ExploreClip::validated))
        }

    suspend fun license(): String? = runInterruptible(Dispatchers.IO) {
        val addon = RinAddonRuntime.load(context, record.manifest.packageName)
        check(addon.record.sha256 == record.sha256)
        val file = eu.kanade.tachiyomi.data.rin.RinStorage(context).file(addon.record)
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("assets/nyanime/explore-license.txt") ?: return@use null
            if (entry.size !in 1L..32_768L) return@use null
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }
}
