package eu.kanade.tachiyomi.data.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.search.SearchMedium

class CatalogSearchCandidatesTest {
    @Test
    fun `public catalog titles and aliases remain suggestions of the requested medium`() {
        val root = Json.parseToJsonElement(
            """{"data":[{"id":"42","attributes":{"canonicalTitle":"Synthetic Portal","titles":{"en":"Golden Passage","ja_jp":null},"abbreviatedTitles":["SP"]}}]}""",
        ) as JsonObject
        val item = CatalogSearchCandidates.parseKitsu(root, SearchMedium.MANGA).single()
        assertEquals("Synthetic Portal", item.title)
        assertEquals(listOf("Golden Passage", "SP"), item.aliases)
        assertEquals(SearchMedium.MANGA, item.medium)
        assertEquals(null, item.sourceId)
        assertEquals("kitsu", item.origin)
    }

    @Test
    fun `alternate catalog handles missing names and unknown schema fields`() {
        val root = Json.parseToJsonElement(
            """{"Page":{"media":[{"id":12,"title":{"romaji":"Synthetic Garden","english":"Made Up Garden","native":null},"synonyms":["Alternate Garden"],"extra":true},{"id":13,"title":null}]}}""",
        ) as JsonObject
        val items = CatalogSearchCandidates.parseAniList(root, SearchMedium.VIDEO)
        assertEquals(1, items.size)
        assertEquals(listOf("Synthetic Garden", "Made Up Garden", "Alternate Garden"), items.single().aliases)
        assertEquals(SearchMedium.VIDEO, items.single().medium)
        assertEquals(emptyList<Any>(), CatalogSearchCandidates.parseKitsu(root, SearchMedium.MANGA))
    }
}
