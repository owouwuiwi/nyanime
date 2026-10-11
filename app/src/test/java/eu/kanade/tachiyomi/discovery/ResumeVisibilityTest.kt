package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.ResumeVisibility
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class ResumeVisibilityTest {
    @Test fun hidingAndUndoKeepOtherTitlesAndTouchOnlyUiPreference() {
        val store = mockk<PreferenceStore>()
        val preference = mockk<Preference<Set<String>>>()
        var hidden = emptySet<String>()
        every { store.getStringSet(any(), any()) } returns preference
        every { preference.get() } answers { hidden }
        every { preference.set(any()) } answers { hidden = firstArg() }
        val visibility = ResumeVisibility(store)
        visibility.hide(10)
        visibility.hide(20)
        visibility.hide(10)
        visibility.restore(10)
        assertEquals(setOf("20"), hidden)
        visibility.restoreAll()
        assertTrue(hidden.isEmpty())
        verify(exactly = 1) { store.getStringSet(any(), any()) }
    }
}
