package tachiyomi.domain.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SearchResultRelevanceTest {
    private val ranker = SearchResultRanker()
    private fun title(
        name: String,
        aliases: List<String> = emptyList(),
    ) = SearchTitle(name, name, SearchMedium.VIDEO, aliases)
    private fun ordered(query: String, titles: List<SearchTitle>): List<String> {
        val ranks = ranker.rank(query, titles)
        return titles.sortedBy { ranks.getValue(it.key) }.map { it.title }
    }

    @Test
    fun `full titles and declared aliases precede words corrections and unrelated results`() {
        val titles = listOf(
            title("Lunar Garden"),
            title("Sloar Gate"),
            title("Beyond the Solar Gate"),
            title("Portale Solare", listOf("Solar Gate")),
            title("Solar;Gate"),
        )
        ordered("Solar Gate", titles) shouldBe
            listOf("Solar;Gate", "Portale Solare", "Beyond the Solar Gate", "Sloar Gate", "Lunar Garden")
    }

    @Test
    fun `numbered editions precede base fallback and aliases cannot erase season numbers`() {
        ordered(
            "Solar Gate 2",
            listOf(title("Solar Gate"), title("Solar Gate 3", listOf("Solar Gate 2")), title("Solar Gate 2")),
        ) shouldBe
            listOf("Solar Gate 2", "Solar Gate", "Solar Gate 3")
        ordered("Solar Gate", listOf(title("Solar Gate 2", listOf("Solar Gate")), title("Solar Gate"))) shouldBe
            listOf("Solar Gate", "Solar Gate 2")
    }

    @Test
    fun `complete query words prefer the concise title before longer companion titles`() {
        ordered(
            "Solar Gate",
            listOf(title("The Solar Gate Behind the Scenes"), title("The Solar Gate")),
        ) shouldBe listOf("The Solar Gate", "The Solar Gate Behind the Scenes")
        ordered(
            "Golden Portal",
            listOf(title("Work A", listOf("The Golden Portal Archive")), title("Work B", listOf("The Golden Portal"))),
        ) shouldBe listOf("Work B", "Work A")
    }

    @Test
    fun `equal relevance retains input order and no result is removed`() {
        val titles = listOf(title("Unknown B"), title("Unknown A"), title("Unrelated"))
        ordered("Solar Gate", titles) shouldBe titles.map { it.title }
        ranker.rank("", titles) shouldBe emptyMap()
    }

    @Test
    fun `diacritics and punctuation stay exact while similar names stay distinct`() {
        ranker.rank("Cafe Horizon", listOf(title("Café Horizon"))).values.single().kind shouldBe SearchMatchKind.TITLE
        val titles = listOf(title("Solar Gate"), title("Solar Gates"))
        ordered("SolarGate", titles) shouldBe listOf("Solar Gate", "Solar Gates")
    }
}
