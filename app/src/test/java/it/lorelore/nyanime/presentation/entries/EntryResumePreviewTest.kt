package it.lorelore.nyanime.presentation.entries

import eu.kanade.presentation.entries.details.DetailPart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EntryResumePreviewTest {
    @Test
    fun `descending source order still previews the next episodes rather than the previous ones`() {
        val parts = (12L downTo 1).map { part(it, it.toDouble(), it < 7) }
        assertEquals(listOf(7L, 8L, 9L), EntryResumePreview.items(parts, 7).map { it.id })
    }

    @Test
    fun `fractional chapters keep stable source IDs and unknown numbers are retained`() {
        val parts = listOf(part(1, 4.0), part(2, 4.5), part(3, 5.0), part(4, null))
        assertEquals(listOf(1L, 2L, 3L), EntryResumePreview.items(parts, 1).map { it.id })
        assertEquals(listOf(3L, 4L), EntryResumePreview.items(parts, 3).map { it.id })
    }

    @Test
    fun `empty catalogues missing current IDs and completed works stay bounded`() {
        assertEquals(emptyList<DetailPart>(), EntryResumePreview.items(emptyList(), 123))
        val parts = listOf(part(1, null, true), part(2, null), part(3, null))
        assertEquals(listOf(2L, 3L), EntryResumePreview.items(parts, 123).map { it.id })
        assertEquals(listOf(1L), EntryResumePreview.items(parts.map { it.copy(completed = true) }, 1).map { it.id })
    }

    private fun part(id: Long, sequence: Double?, completed: Boolean = false) =
        DetailPart(id, "", "", "", completed, false, false, false, sequence = sequence)
}
