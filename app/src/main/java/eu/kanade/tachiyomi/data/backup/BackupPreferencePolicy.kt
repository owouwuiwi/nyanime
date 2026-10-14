package eu.kanade.tachiyomi.data.backup

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Settings that remain meaningful after the database IDs and hardware change. */
object BackupPreferencePolicy {
    private val retiredPrefixes = listOf("back_tap_", Preference.appStateKey("back_tap_"))
    private val retiredKeys = setOf(
        "personal_sync_enabled",
        Preference.appStateKey("personal_sync_enabled"),
        "automatically_trust_extensions",
        "nyanime_title_artwork_automatic",
        Preference.privateKey("fanart_personal_key"),
        Preference.appStateKey("title_artwork_choices_v1"),
        Preference.appStateKey("title_artwork_metadata_v1"),
    )

    fun isRetired(key: String): Boolean = key in retiredKeys || retiredPrefixes.any(key::startsWith)

    fun removeRetired(store: PreferenceStore) {
        store.getAll().keys.filter(::isRetired).forEach { store.getString(it).delete() }
    }

    private val portableAppStateKeys = setOf(
        Preference.appStateKey("has_filters_toggle_state"),
        Preference.appStateKey("last_anime_catalogue_source"),
        Preference.appStateKey("last_catalogue_source"),
    )

    private val deviceSpecificPrefixes = listOf(
        Preference.privateKey("anime4k_episode_profile_"),
        Preference.privateKey("anime4k_calibration_"),
    )

    fun isPortable(key: String): Boolean =
        !isRetired(key) &&
            (!Preference.isAppState(key) || key in portableAppStateKeys) &&
            deviceSpecificPrefixes.none(key::startsWith)
}
