package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import tachiyomi.domain.discovery.AnimeCatalogRemote
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogPage
import tachiyomi.domain.discovery.CatalogRequest
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Independent public catalogue; no AniList proxy, authentication or tracking writes. */
class KitsuCatalogRemote(
    client: OkHttpClient,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
    private val intervalMillis: Long = 1_100L,
) : AnimeCatalogRemote {
    private val client = client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
    private val gate = Mutex()
    private var nextRequest = 0L
    private var blockedUntil = 0L

    override suspend fun fetch(request: CatalogRequest): CatalogPage {
        require(request.page > 0)
        if (request.feed == CatalogFeed.WEEK) {
            return CatalogPage(
                emptyList(),
                provider = "kitsu",
                notice = "Calendario dettagliato non disponibile nel catalogo alternativo Kitsu.",
            )
        }
        val trending = request.feed == CatalogFeed.TRENDING
        val builder = "$BASE/${if (trending) "trending/anime" else "anime"}".toHttpUrl().newBuilder()
        if (trending) {
            builder.addQueryParameter("limit", "20")
        } else {
            builder.addQueryParameter("filter[ageRating]", "G,PG,R")
                .addQueryParameter("page[limit]", "20")
                .addQueryParameter("page[offset]", ((request.page - 1) * 20).toString())
            when (request.feed) {
                CatalogFeed.SEASON, CatalogFeed.NEXT_SEASON -> {
                    val date = LocalDate.parse(request.date).let {
                        if (request.feed == CatalogFeed.NEXT_SEASON) it.plusMonths(3) else it
                    }
                    builder.addQueryParameter("filter[season]", SEASONS[(date.monthValue - 1) / 3])
                        .addQueryParameter("filter[seasonYear]", date.year.toString())
                        .addQueryParameter("sort", "-userCount")
                }
                CatalogFeed.TOP -> builder.addQueryParameter("sort", "-averageRating")
                CatalogFeed.SEARCH -> builder.addQueryParameter("filter[text]", request.query.trim())
                else -> Unit
            }
        }
        val root = execute(builder.build())
        if (root["data"] !is JsonArray) throw IOException("Kitsu: sezione incompleta")
        return CatalogPage(
            root.objects("data").mapNotNull { KitsuCatalogMapper.map(it, root.objects("included")) },
            hasNextPage = !trending && root.obj("links").text("next") != null,
            provider = "kitsu",
        )
    }

    override suspend fun details(id: CatalogId): CatalogAnime {
        require(id.provider == "kitsu" && id.value > 0)
        val url = "$BASE/anime/${id.value}".toHttpUrl().newBuilder()
            .addQueryParameter("include", "genres,mappings,mediaRelationships.destination").build()
        val root = execute(url)
        return KitsuCatalogMapper.map(root.obj("data"), root.objects("included"))
            ?: throw IOException("Scheda Kitsu non disponibile")
    }

    private suspend fun execute(url: HttpUrl): JsonObject = gate.withLock {
        if (clock.millis() < blockedUntil) throw IOException("Kitsu: attesa richiesta dal servizio. Riprova più tardi.")
        delay((nextRequest - clock.millis()).coerceIn(0, intervalMillis))
        nextRequest = clock.millis() + intervalMillis
        client.newCall(GET(url.toString(), Headers.headersOf("Accept", "application/vnd.api+json")))
            .await().use { response ->
                if (response.code == 429) {
                    val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400) ?: 60L
                    blockedUntil = clock.millis() + wait * 1_000
                }
                if (!response.isSuccessful) throw IOException("Kitsu non disponibile (HTTP ${response.code})")
                json.parseToJsonElement(response.body.string()) as? JsonObject
                    ?: throw IOException("Risposta Kitsu non valida")
            }
    }

    companion object {
        private const val BASE = "https://kitsu.io/api/edge"
        private val SEASONS = listOf("winter", "spring", "summer", "fall")
    }
}
