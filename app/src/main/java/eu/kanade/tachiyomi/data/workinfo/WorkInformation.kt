package eu.kanade.tachiyomi.data.workinfo

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

/** Public catalogue identities are deliberately independent of source and database identities. */
@Serializable
enum class WorkKind { ANIME, MANGA, FILM, SERIES, VIDEO }

@Serializable
enum class WorkProvider(val label: String) { ANILIST("AniList"), TVMAZE("TVmaze"), WIKIDATA("Wikidata") }

@Serializable
data class WorkReference(val provider: WorkProvider, val id: String, val kind: WorkKind) {
    fun isValid(): Boolean = when (provider) {
        WorkProvider.ANILIST -> id.toLongOrNull()?.let { it in 1..Int.MAX_VALUE } == true &&
            kind in setOf(WorkKind.ANIME, WorkKind.MANGA)
        WorkProvider.TVMAZE -> id.toLongOrNull()?.let { it in 1..Int.MAX_VALUE } == true && kind == WorkKind.SERIES
        WorkProvider.WIKIDATA -> Regex("Q[1-9][0-9]{0,11}").matches(id) && kind == WorkKind.FILM
    }

    val url: String get() = when (provider) {
        WorkProvider.ANILIST -> "https://anilist.co/${if (kind == WorkKind.MANGA) "manga" else "anime"}/$id"
        WorkProvider.TVMAZE -> "https://www.tvmaze.com/shows/$id"
        WorkProvider.WIKIDATA -> "https://www.wikidata.org/wiki/$id"
    }
}

@Serializable
data class WorkSummary(
    val reference: WorkReference,
    val title: String,
    val aliases: List<String> = emptyList(),
    val year: Int? = null,
    val format: String? = null,
    val season: Int? = null,
    val part: Int? = null,
    val malId: Long? = null,
)

@Serializable
data class WorkPersonReference(val provider: WorkProvider, val id: String) {
    fun isValid() = when (provider) {
        WorkProvider.WIKIDATA -> Regex("Q[1-9][0-9]{0,11}").matches(id)
        else -> id.toLongOrNull()?.let { it in 1..Int.MAX_VALUE } == true
    }
}

@Serializable
data class WorkCredit(
    val name: String,
    val role: String,
    val person: WorkPersonReference? = null,
    val character: String? = null,
    val language: String? = null,
)

@Serializable
data class WorkRelation(val work: WorkSummary, val relation: String)

@Serializable
data class WorkSeason(val number: Int, val episodes: Int? = null, val year: Int? = null)

@Serializable
data class WorkInformation(
    val work: WorkSummary,
    val synopsis: String? = null,
    val textLanguage: String = "en",
    val originalTitle: String? = null,
    val status: String? = null,
    val countries: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val minutes: Int? = null,
    val episodes: Int? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
    val studios: List<String> = emptyList(),
    val creators: List<WorkCredit> = emptyList(),
    val cast: List<WorkCredit> = emptyList(),
    val relations: List<WorkRelation> = emptyList(),
    val seasons: List<WorkSeason> = emptyList(),
    val imdbId: String? = null,
    val moreCreators: Boolean = false,
    val moreCast: Boolean = false,
    val collections: List<String> = emptyList(),
    val adaptedFrom: List<String> = emptyList(),
) {
    val imdbUrl: String? get() = imdbId?.takeIf { Regex("tt[0-9]{7,12}").matches(it) }
        ?.let { "https://www.imdb.com/title/$it/" }
}

@Serializable
data class WorkPerson(
    val reference: WorkPersonReference,
    val name: String,
    val biography: String? = null,
    val language: String = "en",
)

@Serializable
data class WorkPage(val works: List<WorkRelation> = emptyList(), val hasNext: Boolean = false)

@Serializable
data class WorkCreditsPage(val credits: List<WorkCredit> = emptyList(), val hasNext: Boolean = false)

data class WorkIdentity(
    val localKey: String,
    val title: String,
    val kind: WorkKind,
    val aliases: List<String> = emptyList(),
    val year: Int? = null,
    val format: String? = null,
    val season: Int? = null,
    val part: Int? = null,
    val references: List<WorkReference> = emptyList(),
    val malId: Long? = null,
    val imdbId: String? = null,
)

/** A matching name is a suggestion, never sufficient evidence for an automatic association. */
object WorkIdentityMatcher {
    fun match(identity: WorkIdentity, candidates: List<WorkSummary>): WorkSummary? {
        val possible = candidates.distinctBy { it.reference }.filter { compatible(identity, it) }
        val references = identity.references.filter { it.isValid() }.toSet()
        if (references.isNotEmpty()) return possible.filter { it.reference in references }.singleOrNull()
        if (identity.year == null || identity.format == null) return null
        val titles = (identity.aliases + identity.title).map(::normalize).filter(String::isNotEmpty).toSet()
        return possible.filter { candidate ->
            candidate.year == identity.year &&
                candidate.format.equals(identity.format, ignoreCase = true) &&
                (candidate.aliases + candidate.title).any { normalize(it) in titles } &&
                candidate.season == identity.season &&
                candidate.part == identity.part
        }.singleOrNull()
    }

    fun compatible(identity: WorkIdentity, candidate: WorkSummary): Boolean =
        candidate.reference.isValid() &&
            (
                identity.kind == WorkKind.VIDEO &&
                    candidate.reference.kind != WorkKind.MANGA ||
                    identity.kind == candidate.reference.kind
                ) &&
            (identity.year == null || candidate.year == null || identity.year == candidate.year) &&
            (identity.malId == null || candidate.malId == null || identity.malId == candidate.malId) &&
            (identity.season == null || candidate.season == null || identity.season == candidate.season) &&
            (identity.part == null || candidate.part == null || identity.part == candidate.part)

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

interface WorkInformationProvider {
    val provider: WorkProvider
    suspend fun search(identity: WorkIdentity, language: String): List<WorkSummary>
    suspend fun information(reference: WorkReference, language: String): WorkInformation
    suspend fun person(reference: WorkPersonReference, language: String): WorkPerson
    suspend fun works(reference: WorkPersonReference, page: Int, language: String): WorkPage
    suspend fun credits(reference: WorkReference, cast: Boolean, page: Int, language: String): WorkCreditsPage
}
