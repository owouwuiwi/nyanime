package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.MangaHomeCategories
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomeListing
import tachiyomi.domain.discovery.SourceHomeSource

class MangaHomeCategoriesTest {
    private fun source(id: Long, home: String, title: String) = SourceHomeSource(
        id,
        "1",
        emptyList(),
        emptyList(),
        key = "provider:$id",
        homeId = home,
        title = title,
    )

    @Test
    fun declaredCategoriesKeepTheirTitlesAndMergeOnlyMatchingIdentities() {
        val listing = SourceHomeListing(
            false,
            listOf(
                source(1, MangaHomeCategories.DEFAULT_HOME, "Manga"),
                source(2, "sequential", "Sequential"),
                source(3, "sequential", "Sequential"),
            ),
        )
        val groups = MangaHomeCategories.groups(listing)
        assertEquals(2, groups.size)
        assertEquals(listOf(2L, 3L), groups.single { it.title == "Sequential" }.sourceIds.toList())
        assertEquals("sequential", MangaHomeCategories.homeId(MangaHomeCategories.key("sequential")))
        assertNull(MangaHomeCategories.homeId("video:sequential"))
    }

    @Test
    fun providersOfDifferentLanguagesShareCategorySectionsAndSearchScope() {
        val shared = tachiyomi.domain.discovery.SourceHomeSection("latest", "Latest", emptyMap())
        val secondOnly = tachiyomi.domain.discovery.SourceHomeSection("recommended", "Recommended", emptyMap())
        val listing = SourceHomeListing(
            false,
            listOf(
                source(1, "manga", "Manga").copy(language = "it", sections = listOf(shared), search = shared),
                source(2, "manga", "Manga").copy(
                    language = "en",
                    sections = listOf(shared, secondOnly),
                    search = shared,
                ),
            ),
        )
        val group = MangaHomeCategories.groups(listing).single()
        assertEquals(setOf(1L, 2L), group.sourceIds)
        assertEquals(listOf("latest", "recommended"), group.sections.map { it.id })
        assertEquals(listOf(1L, 2L), MangaHomeCategories.sources(listing, "manga").map { it.id })
        assertTrue(MangaHomeCategories.includesSource(listing, "manga", 2))
    }

    @Test
    fun nativeReadingLibraryRemainsReachableWithoutDeclarations() {
        val groups = MangaHomeCategories.groups(SourceHomeListing(false))
        assertEquals(listOf(MangaHomeCategories.DEFAULT_CATEGORY), groups.map { it.id })
        assertTrue(MangaHomeCategories.includesSource(SourceHomeListing(false), MangaHomeCategories.DEFAULT_HOME, 9))
        assertFalse(MangaHomeCategories.includesSource(SourceHomeListing(false), "sequential", 9))
    }

    @Test
    fun categoryFeedsAndResumeRowsDoNotMixDifferentDeclaredKinds() {
        val listing = SourceHomeListing(
            false,
            listOf(
                source(1, MangaHomeCategories.DEFAULT_HOME, "Manga"),
                source(2, "sequential", "Sequential"),
            ),
        )
        assertEquals(listOf(2L), MangaHomeCategories.sources(listing, "sequential").map { it.id })
        assertFalse(MangaHomeCategories.includesSource(listing, MangaHomeCategories.DEFAULT_HOME, 2))
        assertTrue(MangaHomeCategories.includesSource(listing, "sequential", 2))
    }

    @Test
    fun readingCategoryNamespaceCannotCollideWithAnIdenticallyNamedVideoCategory() {
        assertFalse(MangaHomeCategories.key("sequential") == "sequential")
        assertEquals(
            2,
            MangaHomeCategories.groups(SourceHomeListing(false, listOf(source(2, "sequential", "Sequential")))).size,
        )
    }
}
