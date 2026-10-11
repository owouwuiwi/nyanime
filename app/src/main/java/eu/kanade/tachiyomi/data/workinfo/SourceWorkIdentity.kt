package eu.kanade.tachiyomi.data.workinfo

import eu.kanade.tachiyomi.data.discovery.number
import eu.kanade.tachiyomi.data.discovery.objects
import eu.kanade.tachiyomi.data.discovery.strings
import eu.kanade.tachiyomi.data.discovery.text
import kotlinx.serialization.json.JsonObject

/** Optional nyanime.work.v1 memo. Unknown providers/fields are ignored, never inferred from source names. */
object SourceWorkIdentity {
    fun fromManga(sourceId: Long, source: eu.kanade.tachiyomi.source.model.SManga): WorkIdentity {
        val hints = eu.kanade.tachiyomi.data.track.SourceTrackingHints.from(source)
        return apply(
            WorkIdentity(
                WorkInfoPreferences.localKey(true, sourceId, source.url),
                source.title,
                WorkKind.MANGA,
                aliases = hints?.titles.orEmpty(),
                references = listOfNotNull(
                    hints?.anilistId?.let {
                        WorkReference(WorkProvider.ANILIST, it.toString(), WorkKind.MANGA)
                    },
                ),
                malId = hints?.malId,
            ),
            source.memo["nyanime.work.v1"] as? JsonObject,
        )
    }

    fun apply(identity: WorkIdentity, metadata: JsonObject?): WorkIdentity {
        if (metadata == null) return identity
        val references = metadata.objects("references").mapNotNull { item ->
            val provider = WorkProvider.entries.firstOrNull { it.name.equals(item.text("provider"), true) }
                ?: return@mapNotNull null
            val kind = WorkKind.entries.firstOrNull { it.name.equals(item.text("type"), true) }
                ?: return@mapNotNull null
            WorkReference(provider, item.text("id") ?: return@mapNotNull null, kind).takeIf { it.isValid() }
        }.filter { (identity.kind == WorkKind.MANGA) == (it.kind == WorkKind.MANGA) }
        val imdb = metadata.objects("references").firstOrNull {
            it.text("provider").equals("imdb", true) && it.text("type") in listOf("film", "series")
        }?.text("id")?.takeIf { identity.kind != WorkKind.MANGA && Regex("tt[0-9]{7,12}").matches(it) }
        val kind = WorkKind.entries.firstOrNull { it.name.equals(metadata.text("type"), true) }
            ?.takeIf { identity.kind == WorkKind.VIDEO && it != WorkKind.MANGA } ?: identity.kind
        return identity.copy(
            kind = kind,
            aliases = (
                identity.aliases +
                    metadata.strings("titles").filter {
                        it.length in 1..256
                    }.take(32)
                ).distinct(),
            year = metadata.number("year")?.takeIf { it in 1800..3000 } ?: identity.year,
            format = metadata.text("format")?.takeIf { it.length <= 40 } ?: identity.format,
            season = metadata.number("season")?.takeIf { it in 1..1000 } ?: identity.season,
            part = metadata.number("part")?.takeIf { it in 1..1000 } ?: identity.part,
            references = (identity.references + references).distinct(),
            imdbId = imdb ?: identity.imdbId,
        )
    }
}
