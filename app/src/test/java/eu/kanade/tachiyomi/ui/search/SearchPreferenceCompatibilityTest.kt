package eu.kanade.tachiyomi.ui.search

import eu.kanade.domain.source.service.SourcePreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SearchPreferenceCompatibilityTest {
    @Test
    fun `renamed search accessors keep published preference and session keys`() {
        val queryKey = Preference.appStateKey("last_atlas_query")
        val categoryKey = Preference.appStateKey("last_atlas_category")
        val genresKey = Preference.appStateKey("last_atlas_genres")
        val store = mockk<PreferenceStore> {
            every { getBoolean("remember_atlas_search", false) } returns
                InMemoryPreference("remember_atlas_search", true, false)
            every { getBoolean("atlas_keyboard_on_open", false) } returns
                InMemoryPreference("atlas_keyboard_on_open", true, false)
            every { getBoolean("live_atlas_search", true) } returns
                InMemoryPreference("live_atlas_search", false, true)
            every { getString(queryKey, "") } returns InMemoryPreference(queryKey, "Example", "")
            every { getString(categoryKey, "") } returns InMemoryPreference(categoryKey, "video", "")
            every { getStringSet(genresKey, emptySet()) } returns
                InMemoryPreference(genresKey, setOf("genre"), emptySet())
        }
        val preferences = SourcePreferences(store)
        assertTrue(preferences.rememberUnifiedSearch().get())
        assertTrue(preferences.unifiedSearchKeyboardOnOpen().get())
        assertFalse(preferences.liveUnifiedSearch().get())
        assertEquals("Example", preferences.lastUnifiedSearchQuery().get())
        assertEquals("video", preferences.lastUnifiedSearchCategory().get())
        assertEquals(setOf("genre"), preferences.lastUnifiedSearchGenres().get())
        assertEquals(queryKey, preferences.lastUnifiedSearchQuery().key())
        assertEquals(categoryKey, preferences.lastUnifiedSearchCategory().key())
        assertEquals(genresKey, preferences.lastUnifiedSearchGenres().key())
    }
}
