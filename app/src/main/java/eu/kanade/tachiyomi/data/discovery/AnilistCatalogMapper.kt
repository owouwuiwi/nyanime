package eu.kanade.tachiyomi.data.discovery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogRelation

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf {
    it.isNotBlank()
}
internal fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.objects(
    key: String,
): List<JsonObject> = (get(key) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
internal fun JsonObject.strings(key: String): List<String> =
    (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal object AnilistCatalogMapper {
    fun map(media: JsonObject): CatalogAnime? {
        val id = media.long("id") ?: return null
        val names = media.obj("title")
        val title = names.text("romaji") ?: names.text("english") ?: names.text("native") ?: return null
        val next = media.obj("nextAiringEpisode")
        return CatalogAnime(
            id = CatalogId(value = id),
            title = title,
            alternateTitles = (
                listOfNotNull(
                    names.text("english"),
                    names.text("native"),
                ) +
                    media.strings("synonyms")
                ).distinct().filterNot {
                it ==
                    title
            },
            malId = media.long("idMal"),
            cover = media.obj("coverImage").text("large"),
            banner = media.text("bannerImage"),
            synopsis = media.text("description")?.let { Jsoup.parse(it).text().takeIf(String::isNotBlank) },
            score = media.number("averageScore"),
            genres = media.strings("genres"),
            format = media.text("format"),
            status = media.text("status"),
            season = media.text("season"),
            year = media.number("seasonYear"),
            episodes = media.number("episodes"),
            duration = media.number("duration"),
            studios = media.obj("studios").objects("nodes").mapNotNull { it.text("name") },
            airingAt = next.long("airingAt"),
            airingEpisode = next.number("episode"),
            relations = media.obj("relations").objects("edges").mapNotNull { edge ->
                val node = edge.obj("node")
                if (node.text("type") != "ANIME" || node.text("isAdult") == "true") return@mapNotNull null
                val relatedId = node.long("id") ?: return@mapNotNull null
                val relatedTitle = node.obj("title").text("romaji") ?: return@mapNotNull null
                CatalogRelation(
                    CatalogId(value = relatedId),
                    relatedTitle,
                    node.obj("coverImage").text("large"),
                    edge.text("relationType") ?: "",
                )
            },
        )
    }
}
