package eu.kanade.tachiyomi.ui.search

import eu.kanade.tachiyomi.data.discovery.MangaGenreLabels
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomeFilter
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.SourceHomeSection
import tachiyomi.domain.discovery.SourceHomeSource
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.search.SearchMedium

class AtlasSearchPolicyTest {
    @Test
    fun homeMangaAliasesRemainAvailableAfterConversionToLocalEntries() {
        val manga = Manga.create().copy(id = 9, source = 1, title = "Primary title", url = "/item")
        val item = MangaHomeItem(manga, searchAliases = listOf("Alternate name", "Alternate name"))
        val entry = AtlasEntry.manga(item, "manga", "Manga")
        assertEquals(listOf("Alternate name"), entry.aliases)
        assertEquals(9L, entry.targets.single().id)
    }
    private fun entry(
        source: Long,
        ids: Map<String, Long> = emptyMap(),
        medium: SearchMedium = SearchMedium.VIDEO,
        title: String = "Example title",
    ) = AtlasEntry(
        "$medium:$source", medium, source, "/entry", title, "category", "Category",
        catalogIds = ids, targets = listOf(AtlasTarget(source, source, medium, title)),
    )

    @Test
    fun identicalNamesDoNotMergeWithoutVerifiedIdentity() {
        assertEquals(2, AtlasCards.merge(listOf(entry(1), entry(2))).size)
        assertEquals(1, AtlasCards.merge(listOf(entry(1), entry(1))).size)
    }

    @Test
    fun verifiedIdentityRetainsEveryAvailableSource() {
        val cards = AtlasCards.merge(
            listOf(entry(1, mapOf("anilist" to 100)), entry(2, mapOf("anilist" to 100), title = "Another alias")),
        )
        assertEquals(1, cards.size)
        assertEquals(listOf(1L, 2L), cards.single().targets.map { it.source })
        assertEquals("Example title", cards.single().entry.title)
    }

    @Test
    fun differentMediaAndConflictingCatalogsStayDistinct() {
        val original = entry(1, mapOf("anilist" to 100, "myanimelist" to 200))
        val conflicting = entry(2, mapOf("anilist" to 100, "myanimelist" to 201))
        val manga = entry(3, mapOf("anilist" to 100), SearchMedium.MANGA)
        assertEquals(3, AtlasCards.merge(listOf(original, conflicting, manga)).size)
    }

    @Test
    fun aPartialIdentityCannotHideConflictsAlreadyInTheGroup() {
        val cards = AtlasCards.merge(
            listOf(
                entry(1, mapOf("anilist" to 100)),
                entry(2, mapOf("anilist" to 100, "myanimelist" to 200)),
                entry(3, mapOf("anilist" to 100, "myanimelist" to 201)),
            ),
        )
        assertEquals(2, cards.size)
        assertEquals(listOf(1L, 2L), cards.first().targets.map { it.source })
    }

    @Test
    fun verifiedBridgesCombineBothExistingGroupsWithoutLosingChoices() {
        val cards = AtlasCards.merge(
            listOf(
                entry(1, mapOf("anilist" to 100)),
                entry(2, mapOf("myanimelist" to 200)),
                entry(3, mapOf("anilist" to 100, "myanimelist" to 200)),
            ),
        )
        assertEquals(1, cards.size)
        assertEquals(setOf(1L, 2L, 3L), cards.single().targets.map { it.source }.toSet())
    }

    private val categories = listOf(
        SourceHomeSection("adventure", "Adventure", emptyMap(), browseValues = mapOf("Genres" to listOf("Adventure"))),
        SourceHomeSection("drama", "Drama", emptyMap(), browseValues = mapOf("Genres" to listOf("Drama"))),
    )
    private val home = SourceHomeSource(
        1,
        "revision",
        emptyList(),
        categories,
        search = SourceHomeSection("search", "Search", mapOf("Scope" to "Catalog")),
        browseFilters = listOf(
            SourceHomeFilter("Genres", SourceHomeFilter.Kind.MULTIPLE, listOf("Adventure", "Drama")),
        ),
    )
    private val video = AtlasRoute("video:1", SearchMedium.VIDEO, 1, "Source A", "en", "video", "Video", home)
    private val native = AtlasRoute("video:2", SearchMedium.VIDEO, 2, "Source B", "en", "native", "Video")

    @Test
    fun rememberedScopeSurvivesColdExtensionInitialization() {
        val selected = setOf(MangaGenreLabels.key("Adventure"))
        val pending = AtlasSelection.resolve("video", selected, emptySet(), emptyList(), initializing = true)
        assertEquals("video", pending.category)
        assertEquals(selected, pending.selected)
        val ready = AtlasSelection.resolve(pending.category, pending.selected, setOf("video"), listOf(video), false)
        assertEquals("video", ready.category)
        assertEquals(selected, ready.selected)
    }

    @Test
    fun missingRememberedOptionsAreRemovedOnlyAfterInitialization() {
        val pending = AtlasSelection.resolve("removed", setOf("missing"), emptySet(), emptyList(), true)
        val ready = AtlasSelection.resolve(pending.category, pending.selected, setOf("video"), listOf(video), false)
        assertNull(ready.category)
        assertEquals(emptySet<String>(), ready.selected)
    }

    @Test
    fun nativeSourcesRemainSearchableWithoutInventedGenreSupport() {
        assertNotNull(AtlasFilters.request(native, emptyList()))
        val genre = AtlasFilters.genres(listOf(video)).first()
        assertNull(AtlasFilters.request(native, listOf(genre)))
        assertEquals("adventure", AtlasFilters.request(video, listOf(genre))?.sectionId)
    }

    @Test
    fun multipleGenresUseOnlyDeclaredMultipleControls() {
        val genres = AtlasFilters.genres(listOf(video))
        val request = requireNotNull(AtlasFilters.request(video, genres))
        assertEquals(SourceHomeRequest.SEARCH, request.sectionId)
        assertEquals(mapOf("Genres" to listOf("Adventure", "Drama")), request.filters)
        val single = video.copy(
            home = home.copy(
                browseFilters = listOf(
                    SourceHomeFilter("Genres", SourceHomeFilter.Kind.SINGLE, listOf("Adventure", "Drama")),
                ),
            ),
        )
        assertNull(AtlasFilters.request(single, genres))
    }

    @Test
    fun mangaSelectFiltersCannotSilentlyDiscardASecondGenre() {
        val route = video.copy(medium = SearchMedium.MANGA)
        assertNull(AtlasFilters.request(route, AtlasFilters.genres(listOf(route))))
    }

    @Test
    fun categoryAndAdvancedNavigationRetainTheDeclaredRequest() {
        val selected = AtlasFilters.genres(listOf(video))
        val state =
            AtlasState(routes = listOf(video, native), selectedGenres = setOf(selected.first().key), genres = selected)
        assertEquals(listOf(video), state.eligible)
        assertEquals(mapOf("Genres" to listOf("Adventure")), AtlasFilters.section(video, state.selected)?.browseValues)
        val all = requireNotNull(AtlasFilters.section(video, emptyList()))
        assertEquals(mapOf("Scope" to "Catalog"), all.selections)
    }

    @Test
    fun translatedGenreLabelsKeepConcreteSourceValues() {
        val translated = video.copy(
            key = "video:3",
            home = home.copy(
                categories = listOf(SourceHomeSection("translated", "Avventura", mapOf("Genere" to "Avventura"))),
            ),
        )
        val genres = AtlasFilters.genres(listOf(video, translated))
        val shared = genres.first { it.sections.size == 2 }
        assertEquals("adventure", AtlasFilters.request(video, listOf(shared))?.sectionId)
        assertEquals("translated", AtlasFilters.request(translated, listOf(shared))?.sectionId)
    }
}
