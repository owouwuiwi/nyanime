package eu.kanade.tachiyomi.ui.explore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogId

class ExploreClipTest {
    private val item = ExploreClip(
        CatalogAnime(CatalogId("fixture", 1), "Example Season 2", cover = "https://example.org/cover.jpg"),
        "aBcDef012_-",
        ExploreMetric("Fixture", 42, "ratings"),
    )

    @Test
    fun `only a valid trailer ID and catalogue identity can enter a feed`() {
        assertEquals(item, item.validated())
        assertNull(item.copy(youtubeId = "bad';alert(1)").validated())
        assertNull(item.copy(anime = item.anime.copy(id = CatalogId("bad/provider", 1))).validated())
    }

    @Test
    fun `untrusted artwork and negative counts are dropped independently`() {
        val validated = item.copy(
            anime = item.anime.copy(cover = "file:///private", banner = "https://user@example.org/banner.jpg"),
            metric = ExploreMetric("Fixture", -1),
        ).validated()
        assertEquals(item.youtubeId, validated?.youtubeId)
        assertNull(validated?.anime?.cover)
        assertNull(validated?.anime?.banner)
        assertNull(validated?.metric)
    }

    @Test
    fun `trailer input can omit nullable artwork on a related title`() {
        val page = exploreJson.decodeFromString<ExplorePage>(
            """
            {"items":[{"youtubeId":"aBcDef012_-","anime":{
              "id":{"provider":"fixture","value":1},"title":"Example Season 2",
              "relations":[{"id":{"provider":"fixture","value":2},"title":"Example","relationship":"PREQUEL"}]
            }}],"hasMore":true}
            """.trimIndent(),
        )
        assertEquals("PREQUEL", page.items.single().anime.relations.single().relationship)
        assertNull(page.items.single().anime.relations.single().cover)
    }

    @Test
    fun `personal likes never change the count and saved data round trips`() {
        val saved = ExploreSaved(liked = listOf(item), watchlist = listOf(item), hidden = setOf("fixture:2"))
            .rememberSeen(item.key)
        val encoded = exploreJson.encodeToString(ExploreSaved.serializer(), saved)
        assertEquals(saved, exploreJson.decodeFromString<ExploreSaved>(encoded))
        assertEquals(42L, saved.liked.single().metric?.value)
    }
}
