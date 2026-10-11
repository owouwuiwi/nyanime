package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.data.addon.AddonIntroduction
import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class AddonIntroductionTest {
    private val acknowledgement = InMemoryPreference(
        Preference.appStateKey("addon_introduction_complete"),
        null,
        false,
    )
    private val store = mockk<PreferenceStore> {
        every { getBoolean(acknowledgement.key(), any()) } returns acknowledgement
    }

    @Test
    fun interruptedLaunchDoesNotConsumeTheExplanationButAcknowledgementSurvivesRestart() {
        assertFalse(AddonIntroduction(store).complete.get())
        assertFalse(AddonIntroduction(store).complete.get())
        AddonIntroduction(store).confirm()
        assertTrue(AddonIntroduction(store).complete.get())
    }

    @Test
    fun restoredBackupCannotSkipTheExplanationOnAnotherInstallation() {
        val introduction = AddonIntroduction(store)
        introduction.confirm()
        assertFalse(BackupPreferencePolicy.isPortable(introduction.complete.key()))
    }
}
