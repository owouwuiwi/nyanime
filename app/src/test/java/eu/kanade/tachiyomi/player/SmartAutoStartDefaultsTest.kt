package eu.kanade.tachiyomi.player

import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.preserveSmartAutoStartOnUpgrade
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SmartAutoStartDefaultsTest {
    // The preview-only InMemoryPreferenceStore returns a fresh copy on every getter;
    // this fixture shares writes between handles like Android SharedPreferences does.
    private fun store(): PreferenceStore = object : PreferenceStore by InMemoryPreferenceStore() {
        private val values = mutableMapOf<String, Preference<Boolean>>()
        override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
            values.getOrPut(key) { InMemoryPreference(key, null, defaultValue) }
    }

    @Test fun `new installs do not start smart automatically`() {
        val store = store()
        preserveSmartAutoStartOnUpgrade(store, 0)
        assertFalse(AdvancedPlayerPreferences(store).anime4kSmartAutoStart().get())
        preserveSmartAutoStartOnUpgrade(store, 215)
        assertFalse(AdvancedPlayerPreferences(store).anime4kSmartAutoStart().get())
    }

    @Test fun `upgrades preserve the earlier implicit default and existing choices`() {
        val store = store()
        val preferences = AdvancedPlayerPreferences(store)
        preserveSmartAutoStartOnUpgrade(store, 212)
        assertTrue(preferences.anime4kSmartAutoStart().get())
        preferences.anime4kSmartAutoStart().set(false)
        preserveSmartAutoStartOnUpgrade(store, 214)
        assertFalse(preferences.anime4kSmartAutoStart().get())
        preferences.anime4kSmartAutoStart().set(true)
        preserveSmartAutoStartOnUpgrade(store, 215)
        assertTrue(preferences.anime4kSmartAutoStart().get())
    }
}
