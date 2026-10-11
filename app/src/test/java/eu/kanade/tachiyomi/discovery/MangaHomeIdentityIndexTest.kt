package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.MangaHomeChapter
import eu.kanade.tachiyomi.data.discovery.MangaHomeIdentityIndex
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomeMerge
import eu.kanade.tachiyomi.data.discovery.MangaHomePage
import eu.kanade.tachiyomi.data.discovery.MangaHomePresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.manga.model.Manga

class MangaHomeIdentityIndexTest {
    private fun item(source: Long, id: Long = 41, event: String = "entry") = MangaHomeItem(
        Manga.create().copy(id = source, source = source, url = "/series/$source", title = "Title $source"),
        MangaHomePresentation(
            id = event,
            catalogIds = mapOf("catalog" to id),
            chapters = listOf(MangaHomeChapter("/chapter/$source", "Chapter $source")),
        ),
    )

    @Test fun alternativeInAnotherSectionIsAvailableWithoutReplacingItsChapterLinks() {
        val index = MangaHomeIdentityIndex()
        index.configure(mapOf(1L to "1", 2L to "1"))
        index.remember(item(2))
        val original = item(1)
        val combined = index.attach(original)
        assertEquals(listOf(2L), combined.alternateSources.map { it.source })
        assertEquals("/chapter/1", combined.presentation!!.chapters.single().url)
        assertEquals("/chapter/2", combined.sourceVariants.single().presentation!!.chapters.single().url)
        assertEquals(original.key, combined.key)
    }

    @Test fun changingPreferenceKeepsCardKeyAndUsesTheSelectedProvidersChapters() {
        val original = item(1)
        val merged = MangaHomeMerge.merge(
            listOf(MangaHomePage(listOf(original), false), MangaHomePage(listOf(item(2)), false)),
        ).items.single()
        val chosen = MangaHomeMerge.merge(listOf(MangaHomePage(listOf(merged), false)), 2).items.single()
        // Choosing a known variant also works when no new provider page arrives.
        assertEquals(original.key, chosen.key)
        assertEquals(2L, chosen.manga.source)
        assertEquals(
            "/chapter/2",
            chosen.sourceVariants.firstOrNull { it.manga.source == 2L }?.presentation?.chapters?.single()?.url
                ?: chosen.presentation!!.chapters.single().url,
        )
    }

    @Test fun revisionsAndDisabledSourcesInvalidateCachedAlternatives() {
        val index = MangaHomeIdentityIndex()
        index.configure(mapOf(1L to "1", 2L to "1"))
        index.remember(item(2))
        index.configure(mapOf(1L to "1", 2L to "2"))
        assertTrue(index.attach(item(1)).alternateSources.isEmpty())
        index.remember(item(2))
        index.configure(mapOf(1L to "1"))
        assertTrue(index.attach(item(1)).alternateSources.isEmpty())
    }

    @Test fun previouslyMergedVariantsSurviveAnotherPageWithoutDuplicates() {
        val first = MangaHomeMerge.merge(
            listOf(MangaHomePage(listOf(item(1)), false), MangaHomePage(listOf(item(2)), true)),
        )
        val again = MangaHomeMerge.merge(listOf(first, MangaHomePage(listOf(item(2), item(3)), false)))
        assertEquals(1, again.items.size)
        assertEquals(setOf(2L, 3L), again.items.single().alternateSources.map { it.source }.toSet())
        assertTrue(again.hasNextPage)
    }

    @Test fun conflictingThirdProviderCannotBridgeTwoDifferentWorks() {
        val one = item(1).copy(presentation = MangaHomePresentation(catalogIds = mapOf("a" to 1, "b" to 2)))
        val two = item(2).copy(presentation = MangaHomePresentation(catalogIds = mapOf("a" to 1)))
        val three = item(3).copy(presentation = MangaHomePresentation(catalogIds = mapOf("a" to 1, "b" to 3)))
        assertEquals(2, MangaHomeMerge.merge(listOf(MangaHomePage(listOf(one, two, three), false))).items.size)
    }

    @Test fun identitiesDoNotTruncateAtTheEighthEntry() {
        val index = MangaHomeIdentityIndex()
        index.configure(mapOf(1L to "1", 2L to "1"))
        val later = (1..20).map { n -> item(2, n.toLong()).copy(manga = item(2).manga.copy(url = "/series/2/$n")) }
        later.forEach(index::remember)
        assertEquals(1, index.attach(item(1, 20)).alternateSources.size)
        assertTrue(index.attach(item(1, 99)).alternateSources.isEmpty())
    }
}
