package eu.kanade.tachiyomi.ui.search

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchResultRanker
import tachiyomi.domain.search.searchAliases
import tachiyomi.domain.search.withSearchAliases

class AtlasRankingTest {
    private val ranker = SearchResultRanker()
    private fun entry(
        source: Long,
        title: String,
        aliases: List<String> = emptyList(),
        ids: Map<String, Long> = emptyMap(),
    ) = AtlasEntry(
        key = "video:$source:$title",
        medium = SearchMedium.VIDEO,
        source = source,
        url = "/$title",
        title = title,
        category = "video",
        categoryTitle = "Video",
        aliases = aliases,
        catalogIds = ids,
        targets = listOf(AtlasTarget(source, source, SearchMedium.VIDEO, title)),
    )
    private fun ranked(
        entries: List<AtlasEntry>,
        previous: List<AtlasCard> = emptyList(),
        query: String = "Solar Gate",
    ) =
        AtlasCards.ranked(query, AtlasCards.merge(entries, previous), entries, ranker)

    @Test
    fun `late exact result is promoted and existing card keys remain stable`() {
        val unrelated = entry(2, "Lunar Garden")
        val first = ranked(listOf(unrelated))
        val exact = entry(1, "Solar Gate")
        val updated = ranked(listOf(exact, unrelated), first)
        assertEquals(listOf("Solar Gate", "Lunar Garden"), updated.map { it.entry.title })
        assertEquals(first.single().key, updated.last().key)
    }

    @Test
    fun `configured source and page order break ties regardless of arrival`() {
        val a = entry(1, "Solar Gate")
        val b = entry(2, "Solar;Gate")
        val late = ranked(listOf(a, b), ranked(listOf(b)))
        assertEquals(listOf(a.key, b.key), late.map { it.key })
        assertEquals(listOf(a.key, b.key), ranked(listOf(a, b)).map { it.key })
    }

    @Test
    fun `verified group shows most relevant variant while retaining alternatives and key`() {
        val alias = entry(2, "Portale Solare", listOf("Solar Gate"), mapOf("catalog" to 100))
        val previous = ranked(listOf(alias))
        val exact = entry(1, "Solar Gate", ids = mapOf("catalog" to 100))
        val card = ranked(listOf(exact, alias), previous).single()
        assertEquals("Solar Gate", card.entry.title)
        assertEquals(previous.single().key, card.key)
        assertEquals(setOf(1L, 2L), card.targets.map { it.source }.toSet())
    }

    @Test
    fun `paginated incomplete copy keeps aliases without joining similar titles`() {
        val known = entry(1, "Portale Solare", listOf("Solar Gate"))
        val incomplete = known.copy(aliases = emptyList(), category = "other")
        val similar = entry(2, "Portale Solare")
        val cards = ranked(listOf(known, incomplete, similar))
        assertEquals(2, cards.size)
        assertEquals(listOf("Solar Gate"), cards.first().entry.aliases)
        assertNotEquals(cards.first().key, cards.last().key)
    }

    @Test
    fun `new conflicting identity can split a group without duplicate or stolen keys`() {
        val a = entry(1, "Solar Gate", ids = mapOf("catalog" to 100))
        val b = entry(2, "Other Title", ids = mapOf("catalog" to 100))
        val previous = ranked(listOf(b, a))
        val cards = ranked(listOf(a, b.copy(catalogIds = mapOf("catalog" to 101))), previous)
        assertEquals(2, cards.size)
        assertEquals(2, cards.map { it.key }.toSet().size)
    }

    @Test
    fun `unrelated source answers are optional only with convincing title evidence`() {
        val cards = ranked(listOf(entry(1, "Solar Gate"), entry(2, "Lunar Garden"), entry(3, "Solar Gaze")))
        val results = AtlasCards.results(cards)
        assertEquals(listOf("Solar Gate", "Solar Gaze"), results.cards.map { it.entry.title })
        assertEquals(1, results.otherCount)
        assertEquals(cards, AtlasCards.results(cards, includeOthers = true).cards)
        assertEquals(cards.map { it.key }, AtlasCards.results(cards, includeOthers = true).cards.map { it.key })
    }

    @Test
    fun `missing aliases and uncertain corrections cannot empty either medium`() {
        for (medium in SearchMedium.entries) {
            val cards =
                ranked(
                    listOf(
                        entry(1, "Portale Solare").copy(medium = medium),
                        entry(2, "Slar Gate").copy(medium = medium),
                    ),
                )
            assertEquals(cards, AtlasCards.results(cards).cards)
            assertEquals(false, AtlasCards.results(cards).narrowed)
        }
    }

    @Test
    fun `aliases words numbered fallback and exact mode keep relevant cards in the same grid`() {
        val entries = listOf(
            entry(1, "Portale Solare", listOf("Solar Gate 2")),
            entry(2, "Solar Gate"),
            entry(3, "Solar Gate 3"),
            entry(4, "Solar Gate 2 Bonus"),
        )
        val cards = ranked(entries, query = "Solar Gate 2")
        val state = AtlasState(query = "Solar Gate 2", cards = cards, exact = true)
        assertEquals(
            listOf("Portale Solare", "Solar Gate 2 Bonus", "Solar Gate"),
            state.results.cards.map {
                it.entry.title
            },
        )
        assertEquals(1, state.results.otherCount)
        assertEquals(cards, state.copy(includeOtherResults = true).results.cards)
        assertEquals(cards, state.copy(query = "").results.cards)
    }

    @Test
    fun `broad reading survives a late precise match when explicitly preserved`() {
        val broad = ranked(listOf(entry(2, "Lunar Garden")))
        val state = AtlasState(query = "Solar Gate", cards = broad, includeOtherResults = true)
        val arrived = ranked(listOf(entry(1, "Solar Gate"), entry(2, "Lunar Garden")), broad)
        assertEquals(arrived, state.copy(cards = arrived).results.cards)
        assertEquals(broad.single().key, state.copy(cards = arrived).results.cards.last().key)
        assertEquals(true, AtlasState(query = "Solar Gate", cards = broad).broadResultsVisible)
        assertEquals(false, AtlasState(query = "Solar Gate", cards = arrived).broadResultsVisible)
        assertEquals(false, AtlasState(query = "Solar Gate", cards = arrived.take(1)).broadResultsVisible)
    }

    @Test
    fun `incomplete remote memo cannot remove local search or tracking aliases`() {
        val memo = Json.parseToJsonElement(
            """{"nyanime.search.v1":{"aliases":["Solar Gate"]},"nyanime.tracking.v1":{"titles":["Portale Solare"]},"other":{"flag":true}}""",
        ) as kotlinx.serialization.json.JsonObject
        val merged = withSearchAliases(memo, listOf("Solar Gate", "  Golden Portal  "))
        assertEquals(listOf("Solar Gate", "Portale Solare", "Golden Portal"), searchAliases(merged))
        assertEquals(memo["other"], merged["other"])
        assertEquals(searchAliases(memo), searchAliases(withSearchAliases(memo, emptyList())))
    }

    @Test
    fun `home refresh preserves names for the same entry and keeps the new artwork fields`() {
        val original = entry(500, "Portale Solare", listOf("Solar Gate"))
        AtlasExploreCache.offer(listOf(original))
        AtlasExploreCache.offer(listOf(original.copy(aliases = emptyList(), categoryTitle = "Updated category")))
        val cached = AtlasExploreCache.snapshots.value.single { it.key == original.key }
        assertEquals(listOf("Solar Gate"), cached.aliases)
        assertEquals("Updated category", cached.categoryTitle)
    }
}
