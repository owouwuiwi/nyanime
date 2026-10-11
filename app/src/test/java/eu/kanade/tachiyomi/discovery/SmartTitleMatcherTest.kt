package eu.kanade.tachiyomi.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogIdentityMatcher
import tachiyomi.domain.discovery.CatalogRelation
import tachiyomi.domain.discovery.SmartTitleMatcher

class SmartTitleMatcherTest {
    private fun anime(title: String) = CatalogAnime(CatalogId(value = 1), title)

    @Test
    fun `normalizes diacritics punctuation and source language suffixes`() {
        assertEquals(100, SmartTitleMatcher.score(anime("Pokémon: XY"), "Pokemon XY [Sub ITA]"))
    }

    @Test
    fun `does not confuse distinct numbered seasons or parts`() {
        assertEquals(0, SmartTitleMatcher.score(anime("Example 2nd Season"), "Example Stagione 3", collection = true))
        assertEquals(0, SmartTitleMatcher.score(anime("Example Part 2"), "Example Part 1", collection = true))
    }

    @Test
    fun `combined series requires inspecting episodes before accepting`() {
        val target = anime("Example Season 2")
        assertEquals(70, SmartTitleMatcher.score(target, "Example"))
        assertEquals(85, SmartTitleMatcher.score(target, "Example", collection = true))
    }

    @Test
    fun `unrelated titles and remakes are excluded`() {
        assertEquals(0, SmartTitleMatcher.score(anime("Naruto"), "Boruto Naruto Next Generations", collection = true))
        assertEquals(0, SmartTitleMatcher.score(anime("Example").copy(year = 2024), "Example 1999"))
    }

    @Test
    fun `alternate catalogue titles are eligible and unrelated synonyms do not match`() {
        val target = anime("Shingeki no Kyojin").copy(alternateTitles = listOf("Attack on Titan"))
        assertEquals(100, SmartTitleMatcher.score(target, "Attack on Titan"))
        assertEquals(0, SmartTitleMatcher.score(target, "Attack on Titan Junior High"))
    }

    @Test
    fun `identity matching requires provider IDs and one eligible version`() {
        val target = anime("Example").copy(malId = 12)
        assertTrue(CatalogIdentityMatcher.matches(target, 2, 1))
        assertTrue(CatalogIdentityMatcher.matches(target, 1, 12))
        assertFalse(CatalogIdentityMatcher.matches(target, 3, 1))
        assertNull(CatalogIdentityMatcher.uniqueMatch(listOf(7, 8)))
        assertEquals(7L, CatalogIdentityMatcher.uniqueMatch(listOf(7, 7)))
    }

    @Test
    fun `implicit first season matches explicit first season but not a known sequel`() {
        assertEquals(95, SmartTitleMatcher.score(anime("Example"), "Example Season 1"))
        assertEquals(95, SmartTitleMatcher.score(anime("Example Season 1"), "Example"))
        val sequel = anime("Example").copy(
            relations = listOf(CatalogRelation(CatalogId(value = 2), "Prequel", null, "PREQUEL")),
        )
        assertTrue(SmartTitleMatcher.score(sequel, "Example Season 1") < 95)
    }

    @Test
    fun `source query alternatives include unsplit series name`() {
        assertTrue("example" in SmartTitleMatcher.searchQueries(anime("Example Season 2")))
    }
}
