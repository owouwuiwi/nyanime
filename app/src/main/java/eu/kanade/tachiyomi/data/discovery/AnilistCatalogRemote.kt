package eu.kanade.tachiyomi.data.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import tachiyomi.domain.discovery.AnimeCatalogRemote
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogPage
import tachiyomi.domain.discovery.CatalogRequest
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

class AnilistCatalogRemote(client: OkHttpClient, json: Json) : AnimeCatalogRemote {
    private val transport = AnilistCatalogTransport(client, json)

    override suspend fun fetch(request: CatalogRequest): CatalogPage {
        val date = LocalDate.parse(request.date)
        val variables = buildJsonObject {
            put("page", request.page)
            put("perPage", 20)
            if (request.feed == CatalogFeed.SEARCH) put("search", request.query.trim())
            if (request.feed == CatalogFeed.SEASON || request.feed == CatalogFeed.NEXT_SEASON) {
                val target = if (request.feed == CatalogFeed.NEXT_SEASON) date.plusMonths(3) else date
                put("season", listOf("WINTER", "SPRING", "SUMMER", "FALL")[(target.monthValue - 1) / 3])
                put("year", target.year)
            }
            if (request.feed == CatalogFeed.WEEK) {
                put("start", date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond())
                put("end", date.plusDays(7).atStartOfDay(ZoneId.systemDefault()).toEpochSecond())
            }
        }
        val extraArgs = when (request.feed) {
            CatalogFeed.SEARCH -> ", ${'$'}search: String"
            CatalogFeed.SEASON, CatalogFeed.NEXT_SEASON -> ", ${'$'}season: MediaSeason, ${'$'}year: Int"
            CatalogFeed.WEEK -> ", ${'$'}start: Int, ${'$'}end: Int"
            else -> ""
        }
        val mediaArgs = when (request.feed) {
            CatalogFeed.SEARCH -> "search: ${'$'}search, sort: SEARCH_MATCH"
            CatalogFeed.TRENDING -> "sort: TRENDING_DESC"
            CatalogFeed.TOP -> "sort: SCORE_DESC"
            CatalogFeed.SEASON, CatalogFeed.NEXT_SEASON ->
                "season: ${'$'}season, seasonYear: ${'$'}year, sort: POPULARITY_DESC"
            CatalogFeed.WEEK -> ""
        }
        val selection = if (request.feed == CatalogFeed.WEEK) {
            """
                airingSchedules(airingAt_greater: ${'$'}start, airingAt_lesser: ${'$'}end, sort: TIME) {
                    airingAt episode media { $FIELDS }
                }
            """
        } else {
            "media(type: ANIME, isAdult: false, $mediaArgs) { $FIELDS }"
        }
        val data = execute(
            """
                query(${'$'}page: Int, ${'$'}perPage: Int$extraArgs) {
                    Page(page: ${'$'}page, perPage: ${'$'}perPage) { pageInfo { hasNextPage } $selection }
                }
            """,
            variables,
        ).obj("Page")
        if (data.isEmpty()) throw IOException("AniList: sezione non disponibile")
        val field = if (request.feed == CatalogFeed.WEEK) "airingSchedules" else "media"
        if (data[field] !is JsonArray) throw IOException("AniList: dati della sezione incompleti")
        val items = if (request.feed == CatalogFeed.WEEK) {
            data.objects("airingSchedules").mapNotNull { airing ->
                val media = airing.obj("media")
                if (media.text("isAdult") == "true") {
                    null
                } else {
                    AnilistCatalogMapper.map(media)?.copy(
                        airingAt = airing.long("airingAt"),
                        airingEpisode = airing.number("episode"),
                    )
                }
            }
        } else {
            data.objects("media").mapNotNull(AnilistCatalogMapper::map)
        }
        return CatalogPage(items, data.obj("pageInfo").text("hasNextPage") == "true")
    }

    override suspend fun details(id: CatalogId): CatalogAnime {
        require(id.provider == "anilist")
        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME, isAdult: false) {
                    $FIELDS synonyms
                    relations {
                        edges { relationType node { id type isAdult title { romaji } coverImage { large } } }
                    }
                }
            }
        """
        return AnilistCatalogMapper.map(execute(query, buildJsonObject { put("id", id.value) }).obj("Media"))
            ?: throw IOException("Scheda anime non disponibile")
    }

    private suspend fun execute(query: String, variables: JsonObject): JsonObject {
        return transport.execute(query, variables)
    }

    companion object {
        private const val FIELDS = """
            id idMal isAdult title { romaji english native } coverImage { large } bannerImage
            description(asHtml: false) averageScore genres format status season seasonYear episodes duration
            studios(isMain: true) { nodes { name } } nextAiringEpisode { airingAt episode }
        """
    }
}
