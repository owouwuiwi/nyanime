package eu.kanade.presentation.more

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.model.ReleaseHistoryEntry

class UpdateReleaseTimelineTest {
    @Test
    fun `every declared category is recognized in both languages and preserves its entry`() {
        UpdateNoteCategory.entries.forEach { category ->
            category.headings.forEach { heading ->
                val notes = parseUpdateTimeline(
                    "0.42.0.0",
                    "$UPDATE_NOTES_FORMAT\n### 0.42.0.0\n#### $heading\n- Preserved entry.",
                ).single()
                assertEquals(category, notes.groups.single().category)
                assertEquals(listOf("- Preserved entry."), notes.groups.single().entries)
            }
        }
    }

    @Test
    fun `unknown categories remain original notes instead of inheriting the previous category`() {
        val notes = parseUpdateTimeline(
            "0.42.0.0",
            "$UPDATE_NOTES_FORMAT\n### 0.42.0.0\n#### Novità\n- Feature.\n" +
                "#### Future category\n- Preserved future note.\n#### Sicurezza\n- Security note.",
        ).single()
        assertEquals(
            listOf(UpdateNoteCategory.FEATURES, UpdateNoteCategory.ORIGINAL, UpdateNoteCategory.SECURITY),
            notes.groups.map { it.category },
        )
        assertTrue(notes.groups[1].markdown.contains("#### Future category"))
        assertNull(notes.groups[1].entries)
        assertEquals(listOf("- Feature."), notes.groups.first().entries)
    }

    @Test
    fun `undated archives recognize additional explicit categories`() {
        val notes = parseUpdateArchive("## An earlier revision\n### Compatibilità\n- Preserved behavior.")
        assertEquals("An earlier revision", notes.single().version)
        assertEquals(UpdateNoteCategory.COMPATIBILITY, notes.single().groups.single().category)
    }

    @Test
    fun `old headings do not imply structured categories`() {
        val notes = parseUpdateTimeline("0.41.0.6", "### Correzioni\n- Existing change.\n\n### Features\n- Another.")
        assertEquals(listOf(UpdateNoteCategory.ORIGINAL), notes.single().groups.map { it.category })
        assertTrue(notes.single().groups.single().markdown.contains("### Correzioni"))
        assertNull(notes.single().groups.single().entries)
    }

    @Test
    fun `old release notes still separate intermediate versions and exclude installed changes`() {
        val seed = "## Novità di Nyanime 0.41.0.6\n### 12 ottobre — 0.41.0.6: New\n- New.\n" +
            "### 11 ottobre — 0.41.0.2: Older\n- Old.\n### 10 ottobre — 0.40.0.0\n- Installed."
        val notes = mergeUpdateTimeline("0.40.0.0", "0.41.0.6", seed, emptyList())
        assertEquals(listOf("0.41.0.6", "0.41.0.2"), notes.map { it.version })
        assertTrue(notes.all { it.groups.single().category == UpdateNoteCategory.ORIGINAL })
        assertTrue(notes.flatMap { it.groups }.none { it.markdown.contains("Installed.") })
    }

    @Test
    fun `release metadata following a category does not become a counted change`() {
        val notes = parseUpdateTimeline(
            "0.42.0.0",
            "$UPDATE_NOTES_FORMAT\n### 0.42.0.0\n#### Novità\n- New.\n" +
                "### Installation guide\nInstructions.",
        ).single()
        assertEquals(listOf(UpdateNoteCategory.FEATURES, UpdateNoteCategory.ORIGINAL), notes.groups.map { it.category })
        assertEquals(1, notes.groups.first().entries!!.size)
        assertNull(notes.groups.last().entries)
    }

    @Test
    fun `explicit categories preserve nested lists and ignore checksum payloads`() {
        val text = "$UPDATE_NOTES_FORMAT\n## Novità di Nyanime 0.42.0.0\n" +
            "### 12 ottobre — 0.42.0.0: Updates\n#### Novità\n- New screen.\n  - Nested detail.\n" +
            "\n#### Correzioni\n- Fixed retries.\n\n### Checksums\nhash"
        val notes = parseUpdateTimeline("0.42.0.0", text).single()
        assertEquals("Updates", notes.title)
        assertEquals(listOf(UpdateNoteCategory.FEATURES, UpdateNoteCategory.FIXES), notes.groups.map { it.category })
        assertEquals(listOf("- New screen.\n  - Nested detail."), notes.groups.first().entries)
        assertTrue(notes.groups.none { it.markdown.contains("hash") })
    }

    @Test
    fun `code headings remain code and paragraphs are never shortened`() {
        val code = "$UPDATE_NOTES_FORMAT\n### 0.42.0.0: Test\n#### Novità\n" +
            "Description\n\n```\n### 0.41.0.1\n#### Correzioni\n```\n- Entry."
        val notes = parseUpdateTimeline("0.42.0.0", code)
        assertEquals(1, notes.size)
        assertNull(notes.single().groups.single().entries)
        assertTrue(notes.single().groups.single().markdown.contains("### 0.41.0.1"))
    }

    @Test
    fun `all intermediate releases are ordered and duplicated versions stay singular`() {
        val seed = "$UPDATE_NOTES_FORMAT\n### 0.42.0.0: New\n#### Novità\n- New.\n" +
            "### 0.41.0.7: Sync\n#### Correzioni\n- Fixed."
        val result = mergeUpdateTimeline(
            "0.41.0.2",
            "0.42.0.0",
            seed,
            listOf(
                ReleaseHistoryEntry("0.42.0.0", "Duplicate latest."),
                ReleaseHistoryEntry("0.41.0.7", "Duplicate intermediate."),
                ReleaseHistoryEntry("0.41.0.6", "Older included."),
                ReleaseHistoryEntry("0.41.0.2", "Already installed."),
            ),
        )
        assertEquals(listOf("0.42.0.0", "0.41.0.7", "0.41.0.6"), result.map { it.version })
        assertTrue(result.first().groups.first().markdown.contains("- New."))
        assertTrue(result.flatMap { it.groups }.none { it.markdown.contains("Duplicate") })
    }

    @Test
    fun `users on an intermediate build see only later changes`() {
        val seed = "$UPDATE_NOTES_FORMAT\n### 0.42.0.0: New\n#### Novità\n- New.\n" +
            "### 0.41.0.7: Sync\n#### Correzioni\n- Fixed."
        assertEquals(
            listOf("0.42.0.0"),
            mergeUpdateTimeline("0.41.0.7", "0.42.0.0", seed, emptyList()).map {
                it.version
            },
        )
    }

    @Test
    fun `full archive preserves dated unnumbered revisions without inventing versions`() {
        val archive = "# Nyanime\n## Infrastructure\n- Not a release.\n" +
            "## 12 ottobre — 0.42.0.0: Updates\n### Novità\n- New.\n" +
            "## 10 ottobre 2026: Older work\n- Preserved.\n" +
            "## Earlier interface\n### Novità\n- An undated original change."
        val notes = parseUpdateArchive(archive)
        assertEquals(listOf("0.42.0.0", "10 ottobre 2026", "Earlier interface"), notes.map { it.version })
        assertTrue(notes[1].groups.single().markdown.contains("Preserved."))
        assertTrue(notes.last().groups.single().markdown.contains("An undated original change."))
        assertEquals(3, notes.map { it.key }.distinct().size)
    }
}
