package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.MangaHomeFeatured
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomePage
import eu.kanade.tachiyomi.data.discovery.MangaHomePresentation
import eu.kanade.tachiyomi.ui.discovery.manga.MangaHomeRowState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomeSection
import tachiyomi.domain.entries.manga.model.Manga

class MangaHomeFeaturedTest {
    private val sections = listOf(
        SourceHomeSection("highlights", "Highlights", emptyMap(), "featured"),
        SourceHomeSection("recommended", "Recommended", emptyMap(), "featured"),
        SourceHomeSection("latest", "Latest", emptyMap()),
    )

    private fun item(source: Long, id: Long, title: String) = MangaHomeItem(
        Manga.create().copy(source = source, url = "/work/$id", title = title),
        MangaHomePresentation(catalogIds = mapOf("anilist" to id)),
    )

    @Test
    fun `all featured sections merge by catalogue id with source choice intact`() {
        val rows = mapOf(
            "highlights" to MangaHomeRowState(page = MangaHomePage(listOf(item(1, 42, "Title A")), false)),
            "recommended" to MangaHomeRowState(
                page = MangaHomePage(listOf(item(2, 42, "Titolo diverso"), item(2, 55, "Title B")), false),
            ),
            "latest" to MangaHomeRowState(page = MangaHomePage(listOf(item(3, 99, "Latest")), false)),
        )
        val result = MangaHomeFeatured.collect(sections, rows)
        assertFalse(result.loading)
        assertEquals(listOf(42L, 55L), result.items.map { it.presentation?.catalogIds?.get("anilist") })
        assertEquals(listOf(2L), result.items.first().alternateSources.map { it.source })
    }

    @Test
    fun `shared section keeps featured contribution even if another provider declares a different layout`() {
        val declarations = listOf(
            SourceHomeSection("shared", "Shared", emptyMap(), "chapters"),
            SourceHomeSection("shared", "Shared", emptyMap(), "featured"),
            SourceHomeSection("shared", "Shared", emptyMap(), "featured"),
        )
        val rows = mapOf("shared" to MangaHomeRowState(page = MangaHomePage(listOf(item(2, 55, "Title")), false)))
        assertEquals(listOf("Title"), MangaHomeFeatured.collect(declarations, rows).items.map { it.manga.title })
    }

    @Test
    fun `slow or failed provider does not hide another providers loaded highlights`() {
        val firstFailed = MangaHomeRowState(loading = false, error = "Unavailable")
        val secondReady = MangaHomeRowState(page = MangaHomePage(listOf(item(2, 55, "Ready")), false))
        assertTrue(MangaHomeFeatured.collect(sections, emptyMap()).loading)
        val result = MangaHomeFeatured.collect(
            sections,
            mapOf("highlights" to firstFailed, "recommended" to secondReady),
        )
        assertFalse(result.loading)
        assertEquals("Ready", result.items.single().manga.title)
        assertFalse(
            MangaHomeFeatured.collect(
                sections,
                mapOf("highlights" to firstFailed, "recommended" to firstFailed),
            ).loading,
        )
    }
}
