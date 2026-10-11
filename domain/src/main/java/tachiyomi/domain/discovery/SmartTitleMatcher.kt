package tachiyomi.domain.discovery

import java.text.Normalizer
import java.util.Locale

/** Pure ranking policy: source preference never outweighs a different work or season. */
object SmartTitleMatcher {
    private val seasonPattern =
        Regex("""\b(?:season|stagione|saison|series|s)\s*(\d+)\b|\b(\d+)(?:st|nd|rd|th)?\s*(?:season|stagione)\b""")
    private val partPattern = Regex("""\b(?:part|parte|cour)\s*(\d+)\b""")
    private val qualifiers =
        Regex("""\b(?:sub|dub|dubbed|subbed|ita|italiano|italian|eng|english|subita|subeng|bd|bluray)\b""")
    private val yearPattern = Regex("""\b(?:19|20)\d{2}\b""")

    fun normalized(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .replace(Regex("\\s+"), " ")

    fun season(title: String): Int? {
        val match = seasonPattern.find(normalized(title)) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
    }

    private fun clean(title: String): String = normalized(
        title,
    ).replace(qualifiers, "").replace(Regex("\\s+"), " ").trim()

    fun baseTitle(title: String): String = clean(title).replace(seasonPattern, "").replace(partPattern, "")
        .replace(Regex("\\s+"), " ").trim()

    fun score(anime: CatalogAnime, candidate: String, collection: Boolean = false): Int {
        val names = (listOf(anime.title) + anime.alternateTitles).filter { it.isNotBlank() }
        val actual = clean(candidate)
        val candidateSeason = season(candidate)
        val candidateYear = yearPattern.find(actual)?.value?.toIntOrNull()
        if (candidateYear != null && anime.year != null && candidateYear != anime.year) return 0
        return names.maxOfOrNull { title ->
            val expected = clean(title)
            val expectedSeason = season(title) ?: season(anime.title)
            val expectedPart = partPattern.find(expected)?.groupValues?.get(1)
            val actualPart = partPattern.find(actual)?.groupValues?.get(1)
            when {
                candidateSeason != null && expectedSeason != null && candidateSeason != expectedSeason -> 0
                candidateSeason != null && expectedSeason == null && candidateSeason != 1 -> 0
                expectedPart != null && actualPart != null && expectedPart != actualPart -> 0
                expectedSeason != null &&
                    expectedSeason > 1 &&
                    candidateSeason == null &&
                    baseTitle(title) == baseTitle(candidate) -> if (collection) 85 else 70
                expected == actual -> 100
                baseTitle(title).length < 3 -> 0
                baseTitle(title) == baseTitle(candidate) &&
                    anime.relations.none { it.relationship == "PREQUEL" } &&
                    (expectedSeason ?: 1) == 1 &&
                    (candidateSeason ?: 1) == 1 &&
                    (expectedPart ?: "1") == "1" &&
                    (actualPart ?: "1") == "1" -> 95
                baseTitle(title) == baseTitle(candidate) &&
                    expectedSeason == candidateSeason &&
                    expectedPart == actualPart -> 95
                baseTitle(title) == baseTitle(candidate) && collection -> 85
                baseTitle(title) == actual && candidateSeason == null -> 70
                else -> 0
            }
        } ?: 0
    }

    fun searchQueries(anime: CatalogAnime): List<String> = (
        listOf(anime.title) + anime.alternateTitles.take(2) + listOf(baseTitle(anime.title))
        ).filter { it.isNotBlank() }.distinctBy(::normalized)
}
