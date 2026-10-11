package eu.kanade.tachiyomi.ui.privacy

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class PrivacyDisplayPreferencesTest {
    private fun preferences(): PrivacyDisplayPreferences {
        val values = mutableMapOf<String, Preference<Boolean>>()
        val store = mockk<PreferenceStore>()
        every { store.getBoolean(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Boolean>()
            values.getOrPut(key) { InMemoryPreference(key, null, default) }
        }
        return PrivacyDisplayPreferences(store)
    }

    @Test fun optInDefaultsAndPortableSettingsRemainSeparateFromLocalEvidence() {
        val preferences = preferences()
        assertFalse(preferences.enabled().get())
        assertFalse(preferences.onlyInIncognito().get())
        assertTrue(PrivacyArea.entries.filter { it != PrivacyArea.NSFW }.all { preferences.area(it).get() })
        assertFalse(preferences.area(PrivacyArea.NSFW).get())
        assertTrue(BackupPreferencePolicy.isPortable(preferences.enabled().key()))
        assertTrue(BackupPreferencePolicy.isPortable(preferences.onlyInIncognito().key()))
        assertTrue(PrivacyArea.entries.all { BackupPreferencePolicy.isPortable(preferences.area(it).key()) })
        assertFalse(BackupPreferencePolicy.isPortable(preferences.everApplied().key()))
        assertFalse(BackupPreferencePolicy.isPortable(preferences.warningShown().key()))
    }

    @Test fun onlyOneWarningAfterAPreviouslyAppliedProtection() {
        val preferences = preferences()
        assertFalse(preferences.claimFailureWarning())
        preferences.everApplied().set(true)
        assertTrue(preferences.claimFailureWarning())
        repeat(100) { assertFalse(preferences.claimFailureWarning()) }
    }

    @Test fun nsfwRequiresAnExplicitFlagOrLabel() {
        assertTrue(NsfwContentPolicy.isNsfw(true, null))
        assertTrue(NsfwContentPolicy.isNsfw(false, listOf(" NSFW ")))
        assertTrue(NsfwContentPolicy.isNsfw(false, listOf("ＡＤＵＬＴ")))
        assertFalse(NsfwContentPolicy.isNsfw(false, listOf("Romance", "Mature themes", "Not NSFW")))
        assertFalse(NsfwContentPolicy.isNsfw(false, null))
    }
}
