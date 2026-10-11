package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeFilters
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomeGroup
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.SourceHomeSource

class ExtensionHomeBrowseTest {
    @Test fun mergedCataloguesKeepCategoriesSupportedByAtLeastOneSource() {
        val definitions = ExtensionHomeFilters.browseFilters(manifest, controls())
        val provider = SourceHomeSource(
            1,
            "1",
            emptyList(),
            ExtensionHomeFilters.categories(manifest, controls()),
            search = ExtensionHomeFilters.search(manifest, controls()),
            browseFilters = definitions,
        )
        assertEquals(2, SourceHomeGroup("video", "Video", listOf(provider)).categories.size)
        val incompatible = provider.copy(id = 2, browseFilters = definitions.filterNot { it.name == "Categories" })
        val merged = SourceHomeGroup("video", "Video", listOf(provider, incompatible))
        assertEquals(SourceHomeGroup("video", "Video", listOf(provider)).categories, merged.categories)
    }

    private fun controls() = AnimeFilterList(
        object : AnimeFilter.Select<String>("Order", arrayOf("Title", "Recent")) {},
        object : AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Categories",
            listOf(
                object : AnimeFilter.CheckBox("Adventure") {},
                object : AnimeFilter.CheckBox("Drama") {},
            ),
        ) {},
        object : AnimeFilter.Text("Studio") {},
        object : AnimeFilter.Select<String>("Internal", arrayOf("Catalogue", "Home")) {},
    )

    private val manifest = ExtensionHomeManifest(
        "video",
        "Video",
        ExtensionHomeManifest.Source("Example", "it"),
        defaults = mapOf("Internal" to "Catalogue"),
        sections = listOf(
            ExtensionHomeManifest.Section(
                "recent",
                "Recent",
                moreFilters = mapOf("Order" to "Recent"),
            ),
        ),
        search = ExtensionHomeManifest.Section("search", "Search", mapOf("Order" to "Recent")),
        categories = ExtensionHomeManifest.Categories("Categories"),
        browseFilters = listOf("Order", "Categories", "Studio"),
    )

    @Test fun combinesQueryControlsWithoutSharingMutableSourceFilters() {
        val first = controls()
        val second = controls()
        val definitions = ExtensionHomeFilters.browseFilters(manifest, first)
        assertEquals(listOf("Order", "Categories", "Studio"), definitions.map { it.name })
        assertEquals(listOf("Recent"), definitions.first().defaults)
        ExtensionHomeFilters.applyBrowse(
            first,
            definitions,
            mapOf("Categories" to listOf("Adventure", "Drama"), "Studio" to listOf("Studio A")),
        )
        val chosen = first.filterIsInstance<AnimeFilter.Group<*>>().single().state
            .filterIsInstance<AnimeFilter.CheckBox>()
        assertEquals(listOf(true, true), chosen.map { it.state })
        assertEquals("Studio A", first.filterIsInstance<AnimeFilter.Text>().single().state)
        assertEquals("", second.filterIsInstance<AnimeFilter.Text>().single().state)
        ExtensionHomeFilters.applyBrowse(first, definitions, mapOf("Categories" to emptyList()))
        assertEquals(listOf(false, false), chosen.map { it.state })
    }

    @Test fun categoriesAlsoSupportCheckboxGroupsAndOlderDeclarations() {
        val category = ExtensionHomeFilters.categories(manifest, controls()).first()
        assertEquals(mapOf("Categories" to listOf("Adventure")), category.browseValues)
        val older = manifest.copy(browseFilters = emptyList())
        assertEquals(listOf("Categories"), ExtensionHomeFilters.browseFilters(older, controls()).map { it.name })
        assertEquals(
            mapOf("Internal" to "Catalogue", "Order" to "Recent"),
            ExtensionHomeFilters.sections(manifest, controls()).single().moreSelections,
        )
    }

    @Test fun unknownControlsAndStaleOptionsCannotSilentlyProduceUnfilteredResults() {
        val filters = controls()
        val definitions = ExtensionHomeFilters.browseFilters(manifest, filters)
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionHomeFilters.applyBrowse(filters, definitions, mapOf("Internal" to listOf("Home")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionHomeFilters.applyBrowse(filters, definitions, mapOf("Order" to listOf("Removed")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionHomeFilters.applyBrowse(filters, definitions, mapOf("Order" to emptyList()))
        }
    }

    @Test fun brandingRejectsLocalFilesCredentialsAndRetainsOrdinaryMetadata() {
        listOf("file:///private/logo.png", "content://private/image", "https://secret@example.test/logo").forEach {
            assertNull(SourceHomePresentation(logoUrl = it).bounded().logoUrl)
        }
        val data = SourceHomePresentation(badges = listOf("New"), logoUrl = "https://cdn.example.test/season.gif?v=3")
        val memo = data.attachTo(Json.parseToJsonElement("{}").jsonObject)
        assertEquals(data, SourceHomePresentation.from(memo))
        assertEquals(emptyMap<String, Any>(), SourceHomePresentation.without(memo))
    }
}
