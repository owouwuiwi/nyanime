package eu.kanade.presentation.more

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateReleaseNotesTest {
    @Test
    fun `generated header is not duplicated and sections preserve full notes`() {
        val notes = updateReleaseNotes(
            "## Novità di Nyanime 0.19.1.0\n\n### Interfaccia\n- **Nuovo** aspetto\n  - Dettagli\n" +
                "\n### Correzioni\n- [Dettagli](https://example.invalid/notes)",
        )
        assertEquals(listOf("Interfaccia", "Correzioni"), notes.map { it.title })
        assertEquals("- **Nuovo** aspetto\n  - Dettagli", notes[0].markdown)
        assertTrue(notes[1].markdown.contains("https://example.invalid/notes"))
    }

    @Test
    fun `markdown with no sections and horizontal rules is kept`() {
        val text = "A new release.\n\n---\n\n- One change."
        assertEquals(listOf(UpdateNoteSection(null, text)), updateReleaseNotes(text))
        assertTrue(updateReleaseNotes("\n  \n").isEmpty())
    }

    @Test
    fun `checksum section is removed without truncating fenced markdown`() {
        val fence = "```"
        val text = "### Notes\n$fence\n### Code heading\nChecksums\n$fence\n- Visible change\n" +
            "\n### Checksums\nlarge hash"
        val notes = updateReleaseNotes(text)
        assertEquals(1, notes.size)
        assertEquals("$fence\n### Code heading\nChecksums\n$fence\n- Visible change", notes.single().markdown)
        assertEquals("Change\n---", updateReleaseNotes("Change\n---\nChecksums\nhash").single().markdown)
    }
}
