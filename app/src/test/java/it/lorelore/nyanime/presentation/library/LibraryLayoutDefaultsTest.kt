package it.lorelore.nyanime.presentation.library

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences

class LibraryLayoutDefaultsTest {
    @Test
    fun `introducing the library layout once keeps subsequent grid choices and unrelated preferences`() {
        val introduced = InMemoryPreference("intro", null, false)
        val display = InMemoryPreference<LibraryDisplayMode>("display", null, LibraryDisplayMode.CompactGrid)
        val combined = InMemoryPreference<LibraryDisplayMode>("combined", null, LibraryDisplayMode.CompactGrid)
        val unrelated = InMemoryPreference("filter", true, false)
        val preferences = mockk<LibraryPreferences> {
            every { modernLayoutIntroduced() } returns introduced
            every { displayMode() } returns display
            every { allDisplayMode() } returns combined
        }
        assertFalse(introduced.get())
        initializeLibraryLayout(preferences)
        assertTrue(introduced.get())
        assertEquals(LibraryDisplayMode.Shelf, display.get())
        assertEquals(LibraryDisplayMode.Shelf, combined.get())

        display.set(LibraryDisplayMode.ComfortableGrid)
        combined.set(LibraryDisplayMode.List)
        initializeLibraryLayout(preferences)
        assertEquals(LibraryDisplayMode.ComfortableGrid, display.get())
        assertEquals(LibraryDisplayMode.List, combined.get())
        assertTrue(unrelated.get())
    }
}
