package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.AppUpdatePreferences

class AppUpdatePreferencesTest {
    private val selection = InMemoryPreference("nyanime_update_channel", null, "recommended")
    private val complete = InMemoryPreference(Preference.appStateKey("update_channel_choice_complete"), null, false)
    private val lastCheck = InMemoryPreference(Preference.appStateKey("last_app_check_preview"), 500L, 0L)
    private val store = mockk<PreferenceStore> {
        every { getString(selection.key(), any()) } returns selection
        every { getBoolean(complete.key(), any()) } returns complete
        every { getLong(any(), any()) } returns lastCheck
    }

    @Test
    fun choiceIsPendingForNewAndExistingInstallationsAndRecommendedIsTheDefault() {
        val preferences = AppUpdatePreferences(store)
        assertFalse(preferences.choiceComplete.get())
        assertEquals(UpdateChannel.RECOMMENDED, preferences.channel())
    }

    @Test
    fun confirmingSurvivesRestartAndChangingChannelsDoesNotAskAgain() {
        AppUpdatePreferences(store).confirm(UpdateChannel.INCLUDING_PREVIEWS)
        val restarted = AppUpdatePreferences(store)
        assertTrue(restarted.choiceComplete.get())
        assertEquals(UpdateChannel.INCLUDING_PREVIEWS, restarted.channel())
        assertEquals(lastCheck.defaultValue(), lastCheck.get())
        restarted.confirm(UpdateChannel.RECOMMENDED)
        assertTrue(AppUpdatePreferences(store).choiceComplete.get())
        assertEquals(UpdateChannel.RECOMMENDED, restarted.channel())
    }

    @Test
    fun backupTransfersTheChannelButNeverTheInitialChoiceCompletion() {
        val preferences = AppUpdatePreferences(store)
        assertTrue(BackupPreferencePolicy.isPortable(preferences.selection.key()))
        assertFalse(BackupPreferencePolicy.isPortable(preferences.choiceComplete.key()))
    }

    @Test
    fun unknownRestoredChannelDoesNotEnablePreviews() {
        selection.set("unknown")
        assertEquals(UpdateChannel.RECOMMENDED, AppUpdatePreferences(store).channel())
    }
}
