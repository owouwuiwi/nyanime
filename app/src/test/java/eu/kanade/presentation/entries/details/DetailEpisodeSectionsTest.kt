package eu.kanade.presentation.entries.details

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.items.episode.model.EpisodeGroup

class DetailEpisodeSectionsTest {
    private val first = EpisodeGroup("first", "First collection", 0)
    private val second = EpisodeGroup("second", "Second collection", 1)
    private fun part(id: Long, group: EpisodeGroup? = null, done: Boolean = false) =
        DetailPart(id, "1", "Entry $id", "", done, false, false, false, group = group)

    @Test fun `ordinary entries retain their existing flat layout`() {
        assertTrue(DetailEpisodeSections.fromParts(listOf(part(1), part(2)), "Other").isEmpty())
    }

    @Test fun `declared order wins while item ordering and real identities stay stable`() {
        val sections = DetailEpisodeSections.fromParts(
            listOf(part(9, second), part(3, first, true), part(2, first), part(4)),
            "Other",
        )
        assertEquals(listOf("First collection", "Second collection", "Other"), sections.map { it.title })
        assertEquals(listOf(3L, 2L), sections.first().parts.map { it.id })
        assertEquals(2, sections.first().total)
        assertEquals(1, sections.first().completed)
        assertEquals(4, sections.flatMap { it.parts }.size)
    }

    @Test fun `native filters and sorting affect rows without changing header progress totals`() {
        val all = listOf(part(3, first, true), part(2, first), part(1, first), part(4, second))
        val visible = listOf(all[2], all[1])
        val sections = DetailEpisodeSections.fromParts(all, "Other", visible)
        assertEquals(listOf(1L, 2L), sections.first().parts.map { it.id })
        assertEquals(3, sections.first().total)
        assertEquals(1, sections.first().completed)
        assertTrue(sections[1].parts.isEmpty())
        assertEquals(1, sections[1].total)
    }

    @Test fun `default expansion locates resume then explicit native preference then first section`() {
        val sections = DetailEpisodeSections.fromParts(listOf(part(1, first), part(2, second)), "Other")
        assertEquals(sections[1].key, DetailEpisodeSections.initial(sections, 2, sections[0].key))
        assertEquals(sections[1].key, DetailEpisodeSections.initial(sections, null, sections[1].key))
        assertEquals(sections[0].key, DetailEpisodeSections.initial(sections, null, "absent"))
    }

    @Test fun `search reveals results from closed sections without changing saved expansion`() {
        val sections = DetailEpisodeSections.fromParts(listOf(part(1, first), part(200, second)), "Other")
        val open = setOf(sections[0].key)
        val rows = DetailEpisodeSections.rows(sections, open, "200")
        assertEquals(listOf(200L), rows.filterIsInstance<DetailEpisodeRow.Item>().map { it.part.id })
        assertEquals(DetailListLayout.FIRST_ROW + 1, DetailEpisodeSections.index(rows, 200))
        assertEquals(setOf(sections[0].key), open)
    }

    @Test fun `unloaded sections do not disappear as empty search results`() {
        val section = DetailEpisodeSection("native:1", "Collection", emptyList(), total = null, loaded = false)
        val rows = DetailEpisodeSections.rows(listOf(section), emptySet(), "missing")
        assertTrue(rows.first() is DetailEpisodeRow.Header)
        assertTrue(rows.last() is DetailEpisodeRow.Status)
    }

    @Test fun `failed updates preserve cached rows and add a retry status`() {
        val section = DetailEpisodeSection("native:1", "Collection", listOf(part(1)), failed = true)
        val rows = DetailEpisodeSections.rows(listOf(section), setOf(section.key), "")
        assertEquals(1, rows.filterIsInstance<DetailEpisodeRow.Item>().size)
        assertTrue(rows.last() is DetailEpisodeRow.Status)
    }

    @Test fun `matching sections remain collapsible while searching`() {
        val sections = DetailEpisodeSections.fromParts(listOf(part(1, first)), "Other")
        val rows = DetailEpisodeSections.rows(sections, emptySet(), "1", setOf(sections.single().key))
        assertEquals(1, rows.size)
        assertTrue(rows.single() is DetailEpisodeRow.Header)
    }

    @Test fun `large lists only materialize the expanded section and preserve keys`() {
        val sections = (0..19).map { group ->
            DetailEpisodeSection("g:$group", "Collection $group", (1..250).map { part(group * 250L + it) })
        }
        val rows = DetailEpisodeSections.rows(sections, setOf("g:19"), "")
        assertEquals(270, rows.size)
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(DetailListLayout.FIRST_ROW + rows.lastIndex, DetailEpisodeSections.index(rows, 5000))
    }

    @Test fun `nested season containers remain directly navigable without invented empty episodes`() {
        val section = DetailEpisodeSection("nested", "Collection", emptyList(), loaded = false, directOpen = true)
        assertEquals(1, DetailEpisodeSections.rows(listOf(section), setOf(section.key), "").size)
    }
}
