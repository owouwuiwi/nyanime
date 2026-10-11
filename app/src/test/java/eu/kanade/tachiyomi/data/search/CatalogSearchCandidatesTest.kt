package eu.kanade.tachiyomi.data.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchRequestBudget

class CatalogSearchCandidatesTest {
    @Test fun `novels never call anime catalogs or consume their shared allowance`() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().addInterceptor { error("No catalog request is allowed for novels") }.build()
        val catalog = CatalogSearchCandidates(client, Json)
        val budget = SearchRequestBudget(3)
        assertEquals(emptyList<Any>(), catalog.fetch("Fictional book", SearchMedium.NOVEL, budget))
        repeat(3) { assertTrue(budget.take()) }
        assertFalse(budget.take())
        assertEquals(emptyList<Any>(), CatalogSearchCandidates.parseKitsu(JsonObject(emptyMap()), SearchMedium.NOVEL))
        assertEquals(emptyList<Any>(), CatalogSearchCandidates.parseAniList(JsonObject(emptyMap()), SearchMedium.NOVEL))
    }

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
