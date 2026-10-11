package eu.kanade.tachiyomi.data.episodeguide

import eu.kanade.tachiyomi.data.workinfo.WorkIdentity
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

@Serializable
enum class EpisodeClassification { CANON, ANIME_CANON, MIXED, FILLER, UNKNOWN }

@Serializable
data class GuideCandidate(
    val reference: String,
    val title: String,
    val aliases: List<String> = emptyList(),
    val year: Int? = null,
    val format: String? = null,
    val season: Int? = null,
    val part: Int? = null,
) {
    val estimatedBytes get() = (reference.length + title.length + aliases.sumOf(String::length) + 128) * 2L
    fun valid() = reference.length in 1..512 &&
        title.length in 1..256 &&
        aliases.size <= 64 &&
        aliases.all { it.length <= 256 } &&
        (year == null || year in 1900..2200) &&
        (format?.length ?: 0) <= 64 &&
        (season == null || season in 1..1000) &&
        (part == null || part in 1..1000)
}

@Serializable
data class GuideEpisode(
    val number: Int,
    val type: String,
    val title: String = "",
    val airdate: String? = null,
) {
    val classification get() = EpisodeClassification.entries.firstOrNull { it.name == type }
        ?: EpisodeClassification.UNKNOWN
}

@Serializable
data class EpisodeGuide(
    val work: GuideCandidate,
    val episodes: List<GuideEpisode>,
    val attribution: String,
    val url: String,
    val updated: String? = null,
) {
    val byNumber by lazy { episodes.associateBy { it.number } }
    val estimatedBytes by lazy {
        (
            url.length +
                attribution.length +
                (updated?.length ?: 0) +
                episodes.sumOf { it.title.length + it.type.length + (it.airdate?.length ?: 0) + 64 }
            ) *
            2L +
            work.estimatedBytes
    }
    fun valid() = work.valid() &&
        episodes.size <= 10_000 &&
        episodes.all {
            it.number in 1..100_000 &&
                it.title.length <= 512 &&
                it.type.length <= 64 &&
                (it.airdate?.length ?: 0) <= 64
        } &&
        episodes.distinctBy { it.number }.size == episodes.size &&
        url.startsWith("https://") &&
        url.length <= 2048 &&
        (updated?.length ?: 0) <= 64 &&
        attribution.length in 1..200
}

@Serializable
data class GuideBinding(val provider: String? = null, val reference: String? = null, val offset: Int = 0)

data class GuideSnapshot(
    val revision: Long = 0,
    val guide: EpisodeGuide? = null,
    val binding: GuideBinding? = null,
    val candidates: List<Pair<String, GuideCandidate>> = emptyList(),
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    val excluded: Boolean = false,
) {
    val estimatedBytes get() = (guide?.estimatedBytes ?: 0L) + candidates.sumOf { it.second.estimatedBytes }
}

object EpisodeGuidePolicy {
    fun effective(override: Boolean?, nativeFiller: Boolean, automatic: EpisodeClassification): EpisodeClassification =
        when {
            override == true -> EpisodeClassification.FILLER
            override == false -> EpisodeClassification.CANON
            nativeFiller -> EpisodeClassification.FILLER
            else -> automatic
        }
    fun classification(guide: EpisodeGuide?, number: Double, offset: Int): EpisodeClassification {
        if (!number.isFinite() || number < 1 || number % 1.0 != 0.0) return EpisodeClassification.UNKNOWN
        val mapped = number.toLong() + offset
        if (mapped !in 1..100_000L) return EpisodeClassification.UNKNOWN
        return guide?.byNumber?.get(mapped.toInt())
            ?.classification ?: EpisodeClassification.UNKNOWN
    }

    fun automatic(identity: WorkIdentity, candidates: List<GuideCandidate>): GuideCandidate? {
        if (identity.year == null || identity.format?.uppercase(Locale.ROOT) !in setOf("TV", "TV_SHORT")) return null
        // A later season may use absolute numbers in the guide. Never guess its offset.
        if ((identity.season ?: 1) != 1 || (identity.part ?: 1) != 1) return null
        val names = (identity.aliases + identity.title).map(::normalize).toSet()
        return candidates.distinctBy { it.reference }.filter {
            it.year == identity.year &&
                (it.season == null || it.season == 1) &&
                (it.format == null || it.format.equals(identity.format, true)) &&
                (it.part == null || it.part == 1) &&
                (it.aliases + it.title).any { title -> normalize(title) in names }
        }.singleOrNull()
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Explicit opening is never passed here. Unknown or mixed episodes remain eligible. */
    fun <T> next(items: List<T>, start: Int, skip: Boolean, type: (T) -> EpisodeClassification): Pair<T?, Int> {
        var skipped = 0
        val first = (start.toLong() + 1).coerceIn(0, items.size.toLong()).toInt()
        for (index in first until items.size) {
            val item = items[index]
            if (skip && type(item) == EpisodeClassification.FILLER) skipped++ else return item to skipped
        }
        return null to skipped
    }
}
