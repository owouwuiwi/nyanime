package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime

/** Reuses source-declared identity in either supported metadata contract; never guesses from titles. */
internal object SourceHomeMetadata {
    private const val CONFLICT = "nyanime.home.identity-conflict.v1"

    fun presentation(remote: SAnime): SourceHomePresentation? = merge(
        SourceHomePresentation.from(remote.memo),
        SourceTrackingHints.from(remote),
    ).first

    fun attach(remote: SAnime, destination: JsonObject): JsonObject = attach(
        merge(SourceHomePresentation.from(remote.memo), SourceTrackingHints.from(remote)),
        destination,
    )

    fun enrich(card: Anime): Anime {
        if (card.memo[CONFLICT] == JsonPrimitive(true)) return card
        val result = merge(card.homePresentation, SourceTrackingHints.from(card))
        if (result.first == null) return card
        return card.copy(memo = attach(result, card.memo))
    }

    fun withWorkUrl(card: Anime, url: String?): Anime {
        val enriched = enrich(card)
        val presentation = (enriched.homePresentation ?: SourceHomePresentation()).copy(workUrl = url).bounded()
        return enriched.copy(memo = presentation.attachTo(enriched.memo))
    }

    private fun attach(result: Pair<SourceHomePresentation?, Boolean>, destination: JsonObject): JsonObject {
        val memo = result.first?.attachTo(destination) ?: SourceHomePresentation.without(destination)
        return JsonObject(
            if (result.second) memo + (CONFLICT to JsonPrimitive(true)) else memo - CONFLICT,
        )
    }

    private fun merge(
        home: SourceHomePresentation?,
        hints: SourceTrackingHints?,
    ): Pair<SourceHomePresentation?, Boolean> {
        if (home == null && hints == null) return null to false
        val declared = home?.catalogIds.orEmpty()
        val tracking = buildMap {
            hints?.anilistId?.let { put("anilist", it) }
            hints?.malId?.let { put("myanimelist", it) }
            hints?.mangaUpdatesId?.let { put("mangaupdates", it) }
        }
        val conflict = home?.identityConflict == true ||
            (declared.keys intersect tracking.keys).any { declared[it] != tracking[it] }
        return (home ?: SourceHomePresentation()).copy(
            catalogIds = if (conflict) emptyMap() else declared + tracking,
            releaseYear = if (conflict) null else home?.releaseYear,
            aliases = (home?.aliases.orEmpty() + hints?.titles.orEmpty()).distinct(),
            identityConflict = conflict,
        ).bounded() to conflict
    }
}
