package eu.kanade.tachiyomi.data.discovery

import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogRelation
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** JSON:API relationship IDs are resolved only within the matching resource's linkage. */
object KitsuCatalogMapper {
    fun map(resource: JsonObject, included: List<JsonObject>): CatalogAnime? {
        if (resource.text("type") != "anime") return null
        val attributes = resource.obj("attributes")
        if (attributes.text("nsfw") == "true" || attributes.text("ageRating") == "R18") return null
        val id = resource.long("id") ?: return null
        val title = attributes.text("canonicalTitle") ?: return null
        val index = included.associateBy { it.text("type") to it.text("id") }
        fun related(name: String) = resource.obj("relationships").obj(name).objects("data").mapNotNull {
            index[it.text("type") to it.text("id")]
        }
        val start = attributes.text("startDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val mappings = related("mappings").map { it.obj("attributes") }
        return CatalogAnime(
            id = CatalogId("kitsu", id),
            title = title,
            alternateTitles = (
                attributes.obj("titles").keys.mapNotNull { attributes.obj("titles").text(it) } +
                    attributes.strings("abbreviatedTitles")
                ).distinct().filterNot { it == title },
            malId = mappings.singleOrNull { it.text("externalSite") == "myanimelist/anime" }
                ?.text("externalId")?.toLongOrNull(),
            cover = attributes.obj("posterImage").text("large"),
            banner = attributes.obj("coverImage").text("small"),
            synopsis = attributes.text("synopsis")?.let { Jsoup.parse(it).text() },
            score = attributes.text("averageRating")?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.roundToInt(),
            genres = related("genres").mapNotNull { it.obj("attributes").text("name") },
            format = attributes.text("subtype")?.uppercase(Locale.ROOT),
            status = when (attributes.text("status")) {
                "finished" -> "FINISHED"
                "current" -> "RELEASING"
                "upcoming", "tba", "unreleased" -> "NOT_YET_RELEASED"
                else -> null
            },
            season = start?.let { listOf("WINTER", "SPRING", "SUMMER", "FALL")[(it.monthValue - 1) / 3] },
            year = start?.year,
            episodes = attributes.number("episodeCount"),
            duration = attributes.number("episodeLength"),
            relations = related("mediaRelationships").mapNotNull { relationship ->
                val destinationId = relationship.obj("relationships").obj("destination").obj("data")
                val destination = index[destinationId.text("type") to destinationId.text("id")]
                    ?: return@mapNotNull null
                val anime = map(destination, emptyList()) ?: return@mapNotNull null
                CatalogRelation(
                    anime.id,
                    anime.title,
                    anime.cover,
                    relationship.obj("attributes").text("role")?.uppercase(Locale.ROOT) ?: "",
                )
            },
        )
    }
}
