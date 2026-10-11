package it.lorelore.nyanime.presentation.library

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryQuickFilterTest {
    @Test
    fun `continue includes partially played first item and excludes finished and unstarted works`() {
        assertTrue(LibraryQuickFilter.CONTINUE.includes(12, 0, 123, 0))
        assertTrue(LibraryQuickFilter.CONTINUE.includes(12, 6, 0, 0))
        assertFalse(LibraryQuickFilter.CONTINUE.includes(0, 12, 123, 0))
        assertFalse(LibraryQuickFilter.CONTINUE.includes(12, 0, 0, 1))
    }

    @Test
    fun `news uses release notices rather than all unread items or catalogue totals`() {
        assertFalse(LibraryQuickFilter.NEWS.includes(1477, 0, 123, 0))
        assertTrue(LibraryQuickFilter.NEWS.includes(1, 42, 123, 1))
    }

    @Test
    fun `all keeps unknown totals and unstarted titles accessible`() {
        assertTrue(LibraryQuickFilter.ALL.includes(0, 0, 0, 0))
        assertTrue(LibraryQuickFilter.ALL.includes(-1, 6, 123, 0))
    }
}
