package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class SleepTimerPreferenceTest {
    @Test
    fun `custom duration survives recreation of the player preferences`() {
        val values = mutableMapOf<String, InMemoryPreference<Int>>()
        val store = mockk<PreferenceStore> {
            every { getInt(any(), any()) } answers {
                val key = firstArg<String>()
                values.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
        }
        val first = PlayerPreferences(store)
        assertEquals(30, first.lastSleepTimerMinutes().get())
        first.lastSleepTimerMinutes().set(75)
        assertEquals(75, PlayerPreferences(store).lastSleepTimerMinutes().get())
    }
}
