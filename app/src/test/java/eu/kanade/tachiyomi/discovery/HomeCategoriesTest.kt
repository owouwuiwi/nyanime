package eu.kanade.tachiyomi.discovery

import eu.kanade.presentation.discovery.HomeCategories
import eu.kanade.presentation.discovery.categoryPages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomeGroup

class HomeCategoriesTest {
    private val homes = listOf(
        SourceHomeGroup("video", "Video", emptyList()),
        SourceHomeGroup("reading", "Lettura", emptyList()),
        SourceHomeGroup("films", "Film", emptyList()),
    )

    @Test fun orderSurvivesMissingCategoryAndCyclesInVisibleOrder() {
        val initial = HomeCategories.choices(homes, "[]")
        val saved = HomeCategories.moveFirst("[]", initial.first { it.id == "reading" })
        val visible = HomeCategories.choices(homes, saved)
        assertEquals(listOf("reading", null, "films", "video"), visible.map { it.id })
        assertEquals(null, HomeCategories.next("reading", visible))
        assertEquals("films", HomeCategories.next(null, visible))
        assertEquals("reading", HomeCategories.next("video", visible))
        assertEquals("reading", HomeCategories.choices(homes.filterNot { it.id == "films" }, saved).first().id)
        assertEquals("reading", HomeCategories.choices(homes, saved).first().id)
    }

    @Test fun narrowScreenShowsAtMostTwoRowsWithoutLosingCategories() {
        val phone = categoryPages(listOf(71f, 66f, 68f, 65f, 64f, 79f), 328f, 12f)
        assertEquals(listOf(0, 1, 2, 3), phone.single().first())
        assertEquals(listOf(4, 5), phone.single().last())

        val widths = List(9) { 80f }
        val pages = categoryPages(widths, availableWidth = 288f, gap = 12f)
        assertTrue(pages.size > 1)
        assertEquals(widths.indices.toList(), pages.flatten().flatten())
        assertTrue(pages.all { it.size <= 2 })
        assertTrue(pages.flatMap { it }.all { row -> row.size * 80f + (row.size - 1) * 12f <= 288f })
        assertEquals(1, categoryPages(listOf(70f, 80f), 288f, 12f).single().size)
    }
}
