package eu.kanade.tachiyomi.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomeGroup
import tachiyomi.domain.discovery.SourceHomeSection
import tachiyomi.domain.discovery.SourceHomeSectionGroup
import tachiyomi.domain.discovery.SourceHomeSource

class SourceHomeRowsTest {
    private val hero = SourceHomeSection("hero", "Vetrina", emptyMap(), layout = "featured")
    private val day = variant("day", "Giorno")
    private val week = variant("week", "Settimana")
    private val recent = SourceHomeSection("recent", "Novità", emptyMap())

    private fun variant(id: String, tab: String) = SourceHomeSection(
        id,
        "Classifica · $tab",
        mapOf("Ordine" to tab),
        group = SourceHomeSectionGroup("ranking", "Classifica", tab),
    )

    private fun source(vararg sections: SourceHomeSection) =
        SourceHomeSource(1, "v1", sections.toList(), emptyList())

    private fun home(vararg sections: SourceHomeSection) =
        SourceHomeGroup("films", "Film", listOf(source(*sections)))

    @Test fun rowsKeepFirstAppearanceAndConcreteRequestsRemainUnchanged() {
        val home = home(hero, day, recent, week)
        assertEquals(listOf("section:hero", "group:ranking", "section:recent"), home.rows.map { it.id })
        assertEquals(listOf("Vetrina", "Classifica", "Novità"), home.rows.map { it.title })
        assertEquals(listOf("day", "week"), home.rows[1].sections.map { it.id })
        assertEquals(listOf("hero", "day", "recent", "week"), home.sections.map { it.id })
        assertEquals("featured", home.rows.first().selected(null).layout)
        assertEquals(mapOf("Ordine" to "Settimana"), home.providers.single().sections.last().selections)
    }

    @Test fun savedSelectionFallsBackWhenItsProviderOrFilterIsRemoved() {
        val row = home(day, week).rows.single()
        assertEquals("day", row.selected(null).id)
        assertEquals("week", row.selected("week").id)
        assertEquals("day", home(day).rows.single().selected("week").id)
        assertEquals("Classifica · Giorno", home(day).rows.single().title)
        assertEquals("day", row.selected("unknown").id)
    }

    @Test fun multipleProvidersAddVariantsWithoutDuplicatingSharedSections() {
        val home = SourceHomeGroup(
            "films",
            "Film",
            listOf(source(day), source(day, week).copy(id = 2, key = "second")),
        )
        assertEquals(listOf("day", "week"), home.rows.single().sections.map { it.id })
        assertEquals(setOf(1L, 2L), home.sourceIds)
    }

    @Test fun groupIdsCannotCollideWithStandaloneSectionsOrLocalRows() {
        val standalone = recent.copy(id = "ranking")
        val rows = home(standalone, day).rows
        assertEquals(listOf("section:ranking", "group:ranking"), rows.map { it.id })
        assertEquals(2, rows.size)
    }

    @Test fun legacyDeclarationsStillRenderSeparateRows() {
        val rows = home(hero, recent).rows
        assertEquals(listOf("hero", "recent"), rows.map { it.selected(null).id })
        assertEquals(listOf(1, 1), rows.map { it.sections.size })
    }
}
