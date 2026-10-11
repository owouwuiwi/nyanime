package eu.kanade.tachiyomi.data.backup

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class BackupPreferencePolicyTest {
    @Test fun automaticTrustCannotReturnThroughAnOlderBackup() {
        assertTrue(BackupPreferencePolicy.isRetired("automatically_trust_extensions"))
        assertFalse(BackupPreferencePolicy.isPortable("automatically_trust_extensions"))
    }

    @Test fun cloudPolicyMovesButSessionActivationAndDeviceNameDoNot() {
        assertTrue(BackupPreferencePolicy.isPortable("cloud_backup_interval_hours"))
        assertTrue(BackupPreferencePolicy.isPortable("cloud_backup_keep_copies"))
        assertFalse(BackupPreferencePolicy.isPortable(Preference.appStateKey("cloud_backup_enabled")))
        assertFalse(BackupPreferencePolicy.isPortable(Preference.appStateKey("cloud_backup_device_name")))
    }

    @Test fun portableSettingsSurviveADeviceMove() {
        assertTrue(BackupPreferencePolicy.isPortable("pref_theme"))
        assertTrue(BackupPreferencePolicy.isPortable(Preference.privateKey("track_token_1")))
        assertTrue(BackupPreferencePolicy.isPortable(Preference.appStateKey("has_filters_toggle_state")))
    }

    @Test fun localStateAndDatabaseIdBasedProfilesStayOnTheOriginalDevice() {
        assertFalse(BackupPreferencePolicy.isPortable(Preference.appStateKey("storage_dir")))
        assertFalse(BackupPreferencePolicy.isPortable(Preference.appStateKey("discovery_hidden_resume")))
        assertFalse(BackupPreferencePolicy.isPortable(Preference.appStateKey("trusted_extensions")))
        assertFalse(BackupPreferencePolicy.isPortable(Preference.privateKey("anime4k_episode_profile_1_12")))
        assertFalse(BackupPreferencePolicy.isPortable(Preference.privateKey("anime4k_calibration_gpu")))
    }

    @Test fun retiredGestureSettingsCannotReturnFromAnOlderBackup() {
        for (key in listOf(
            "back_tap_enabled",
            "back_tap_haptic",
            "back_tap_sensitivity",
            "back_tap_action_Reader",
            Preference.appStateKey("back_tap_calibration"),
        )) {
            assertTrue(BackupPreferencePolicy.isRetired(key))
            assertFalse(BackupPreferencePolicy.isPortable(key))
        }
        assertFalse(BackupPreferencePolicy.isRetired("reader_tap"))
        assertTrue(BackupPreferencePolicy.isPortable("player_longpress_action"))
    }

    @Test fun cleanupDeletesOnlyRetiredKeysWithoutReadingTheirDifferentValueTypes() {
        val store = mockk<PreferenceStore>()
        val calibrationKey = Preference.appStateKey("back_tap_calibration")
        every { store.getAll() } returns mapOf(
            "back_tap_enabled" to true,
            "back_tap_action_Player" to "PlayPause",
            calibrationKey to 1.2f,
            "reader_tap" to true,
            "pref_theme" to "Dark",
        )
        val removed = mutableSetOf<String>()
        val handle = mockk<Preference<String>>(relaxed = true)
        every { store.getString(any(), any()) } answers {
            removed.add(firstArg())
            handle
        }
        BackupPreferencePolicy.removeRetired(store)
        assertEquals(setOf("back_tap_enabled", "back_tap_action_Player", calibrationKey), removed)
        verify(exactly = 3) { handle.delete() }
        verify(exactly = 0) { handle.get() }
    }

    @Test fun retiredArtworkPreferencesCannotReturnThroughBackup() {
        val keys = setOf(
            "nyanime_title_artwork_automatic",
            Preference.privateKey("fanart_personal_key"),
            Preference.appStateKey("title_artwork_choices_v1"),
            Preference.appStateKey("title_artwork_metadata_v1"),
        )
        keys.forEach { key ->
            assertTrue(BackupPreferencePolicy.isRetired(key))
            assertFalse(BackupPreferencePolicy.isPortable(key))
        }
        val store = mockk<PreferenceStore>()
        every { store.getAll() } returns (keys.associateWith { "retired" } + ("pref_theme" to "Dark"))
        val handle = mockk<Preference<String>>(relaxed = true)
        every { store.getString(any(), any()) } returns handle
        BackupPreferencePolicy.removeRetired(store)
        keys.forEach { verify(exactly = 1) { store.getString(it, any()) } }
        verify(exactly = 0) { store.getString("pref_theme", any()) }
        verify(exactly = 4) { handle.delete() }
    }
}
