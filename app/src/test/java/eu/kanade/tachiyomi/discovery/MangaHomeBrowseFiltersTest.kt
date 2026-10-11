package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.ExtensionHomeManifest
import eu.kanade.tachiyomi.data.discovery.MangaHomeFilters
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomeFilter

class MangaHomeBrowseFiltersTest {
    @Test
    fun `declared controls preserve defaults and selections without leaking between requests`() {
        val first = filters()
        val second = filters()
        val definitions = MangaHomeFilters.browseFilters(manifest(), first)
        assertEquals(listOf("Format", "Tags", "Year"), definitions.map { it.name })
        assertEquals(listOf("Any"), definitions.first().defaults)
        assertEquals(SourceHomeFilter.Kind.MULTIPLE, definitions[1].kind)
        MangaHomeFilters.applyBrowse(
            first,
            definitions,
            mapOf(
                "Format" to listOf("Serial"),
                "Tags" to listOf("B"),
                "Year" to listOf("2026"),
            ),
        )
        assertEquals(1, (first[0] as Filter.Select<*>).state)
        assertEquals(
            listOf(false, true),
            (first[1] as Filter.Group<*>).state.filterIsInstance<Filter.CheckBox>().map {
                it.state
            },
        )
        assertEquals("2026", (first[2] as Filter.Text).state)
        assertEquals(0, (second[0] as Filter.Select<*>).state)
        assertEquals("", (second[2] as Filter.Text).state)
    }

    @Test
    fun `unknown or stale controls are rejected before requesting a source`() {
        val values = filters()
        val definitions = MangaHomeFilters.browseFilters(manifest(), values)
        assertThrows(IllegalArgumentException::class.java) {
            MangaHomeFilters.applyBrowse(values, definitions, mapOf("Unknown" to listOf("A")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MangaHomeFilters.applyBrowse(values, definitions, mapOf("Format" to listOf("Missing")))
        }
    }

    private fun manifest() = ExtensionHomeManifest(
        "reading",
        "Reading",
        ExtensionHomeManifest.Source("TestSource", "en"),
        sections = emptyList(),
        browseFilters = listOf("Format", "Tags", "Year", "Not available"),
    )

    private fun filters() = FilterList(
        object : Filter.Select<String>("Format", arrayOf("Any", "Serial")) {},
        object : Filter.Group<Filter.CheckBox>(
            "Tags",
            listOf(object : Filter.CheckBox("A") {}, object : Filter.CheckBox("B") {}),
        ) {},
        object : Filter.Text("Year") {},
    )
}
