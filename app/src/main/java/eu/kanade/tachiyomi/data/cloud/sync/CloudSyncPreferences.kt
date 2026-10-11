package eu.kanade.tachiyomi.data.cloud.sync

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Device configuration must never replicate itself and enroll a different installation. */
internal class CloudSyncPreferences(private val store: PreferenceStore = Injekt.get()) {
    fun enabled() = store.getBoolean(key("enabled"), false)
    fun account() = store.getLong(key("account"), 0)
    fun space() = store.getString(key("space"))
    fun sharedAppearance() = store.getBoolean(key("appearance"), true)
    fun sharedPlayback() = store.getBoolean(key("playback"), true)
    fun sharedReader() = store.getBoolean(key("reader"), true)
    fun sharedCatalogues() = store.getBoolean(key("catalogues"), true)
    fun sharedCredentials() = store.getBoolean(key("portable_credentials"), false)
    fun sharedPreferences() = store.getBoolean(key("preferences"), true)
    fun recoverySaved() = store.getBoolean(key("recovery_saved"), false)
    fun importPending() = store.getBoolean(key("import_pending"), false)
    fun shares(group: SyncSettingsGroup): Boolean = when (group) {
        SyncSettingsGroup.APPEARANCE -> sharedAppearance().get()
        SyncSettingsGroup.PLAYBACK -> sharedPlayback().get()
        SyncSettingsGroup.READER -> sharedReader().get()
        SyncSettingsGroup.CATALOGUES -> sharedCatalogues().get()
        SyncSettingsGroup.GENERAL -> sharedPreferences().get()
    }
    private fun key(name: String) = Preference.appStateKey("telegram_sync_$name")
}
