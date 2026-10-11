package eu.kanade.tachiyomi.data.releases

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.Locale

class ReleasePreferencesTest {
    @Test
    fun defaultStillIncludesTheExistingTwoMoments() {
        assertEquals(
            setOf(ReleaseReminderKind.ADVANCE, ReleaseReminderKind.AIRING),
            ReleasePreferences(store()).selectedReminders(),
        )
    }

    @Test
    fun existingAdvanceChoiceIsPreservedAndNewMomentsRemainOptIn() {
        val saved = store()
        saved.getBoolean("release_advance_reminders_enabled", true).set(false)
        val preferences = ReleasePreferences(saved)

        assertEquals(setOf(ReleaseReminderKind.AIRING), preferences.selectedReminders())
        preferences.reminder(ReleaseReminderKind.SAME_DAY_MORNING).set(true)
        assertEquals(
            setOf(ReleaseReminderKind.SAME_DAY_MORNING, ReleaseReminderKind.AIRING),
            ReleasePreferences(saved).selectedReminders(),
        )
    }

    @Test
    fun changingThePhoneLanguageDoesNotLoseReminderChoices() {
        val saved = store()
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            ReleasePreferences(saved).reminder(ReleaseReminderKind.SAME_DAY_MORNING).set(true)
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                setOf(ReleaseReminderKind.ADVANCE, ReleaseReminderKind.AIRING, ReleaseReminderKind.SAME_DAY_MORNING),
                ReleasePreferences(saved).selectedReminders(),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun store(): PreferenceStore {
        val booleans = mutableMapOf<String, InMemoryPreference<Boolean>>()
        val sets = mutableMapOf<String, InMemoryPreference<Set<String>>>()
        return mockk {
            every { getBoolean(any(), any()) } answers {
                val key = firstArg<String>()
                booleans.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
            every { getStringSet(any(), any()) } answers {
                val key = firstArg<String>()
                sets.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
        }
    }
}
