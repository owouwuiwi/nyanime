package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.model.Anime

class PlayerAutoSkipPreferenceTest {
    @Test
    fun `a title override wins over the global setting and survives recreation`() {
        val booleans = mutableMapOf<String, InMemoryPreference<Boolean>>()
        val sets = mutableMapOf<String, InMemoryPreference<Set<String>>>()
        val store = mockk<PreferenceStore> {
            every { getBoolean(any(), any()) } answers {
                val key = firstArg<String>()
                booleans.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
            every { getStringSet(any(), any()) } answers {
                val key = firstArg<String>()
                sets.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
        }
        val first = Anime.create().copy(source = 42, url = "/show/one")
        val anotherSource = first.copy(source = 43)
        val anotherTitle = first.copy(url = "/show/two")
        val preferences = PlayerPreferences(store)

        assertFalse(preferences.shouldAutoSkipIntro(first))
        preferences.autoSkipIntro().set(true)
        assertTrue(preferences.shouldAutoSkipIntro(first))
        preferences.setAutoSkipIntroOverride(first, false)
        assertFalse(preferences.shouldAutoSkipIntro(first))
        assertTrue(preferences.shouldAutoSkipIntro(anotherSource))
        assertTrue(preferences.shouldAutoSkipIntro(anotherTitle))
        assertEquals(false, PlayerPreferences(store).autoSkipIntroOverride(first))

        preferences.autoSkipIntro().set(false)
        preferences.setAutoSkipIntroOverride(first, true)
        assertTrue(preferences.shouldAutoSkipIntro(first))
        assertFalse(preferences.shouldAutoSkipIntro(anotherTitle))
        preferences.setAutoSkipIntroOverride(first, null)
        assertNull(preferences.autoSkipIntroOverride(first))
        assertFalse(preferences.shouldAutoSkipIntro(first))
    }
}
