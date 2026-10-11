package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaTrackingMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tachiyomi.domain.entries.anime.model.Anime

/** The extension owns extraction; the app only understands this versioned, source-neutral data. */
internal data class SourceTrackingHints(
    val anilistId: Long? = null,
    val malId: Long? = null,
    val mangaUpdatesId: Long? = null,
    val titles: List<String> = emptyList(),
) {
    companion object {
        private const val KEY = "nyanime.tracking.v1"

        fun from(anime: Anime): SourceTrackingHints? = parse(anime.memo[KEY] as? JsonObject)

        fun from(anime: SAnime): SourceTrackingHints? = parse(anime.memo[KEY] as? JsonObject)

        fun from(manga: SManga): SourceTrackingHints? {
            val raw = (manga as? SMangaTrackingMetadata)?.trackingMetadata ?: return null
            return runCatching { parse(Json.parseToJsonElement(raw).jsonObject) }.getOrNull()
        }

        fun parse(objectValue: JsonObject?): SourceTrackingHints? = runCatching {
            if (objectValue == null) return@runCatching null
            val ids = objectValue["ids"]?.jsonObject ?: JsonObject(emptyMap())
            fun id(name: String) = ids[name]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
            val titles = objectValue["titles"]?.jsonArray
                ?.asSequence()
                ?.mapNotNull {
                    runCatching {
                        it.jsonPrimitive.content.trim().takeIf { title -> title.length in 1..256 }
                    }.getOrNull()
                }
                ?.distinct()
                ?.take(32)
                ?.toList()
                .orEmpty()
            SourceTrackingHints(id("anilist"), id("myanimelist"), id("mangaupdates"), titles)
                .takeIf {
                    it.anilistId != null ||
                        it.malId != null ||
                        it.mangaUpdatesId != null ||
                        it.titles.isNotEmpty()
                }
        }.getOrNull()
    }
}
