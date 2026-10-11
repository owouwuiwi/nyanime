package eu.kanade.tachiyomi.data.cloud

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CloudPreferences(private val store: PreferenceStore = Injekt.get()) {
    fun introductionComplete() = store.getBoolean(Preference.appStateKey("cloud_introduction_complete"), false)
    fun enabled() = store.getBoolean(Preference.appStateKey("cloud_backup_enabled"), false)
    fun intervalHours() = store.getInt("cloud_backup_interval_hours", 12)
    fun wifiOnly() = store.getBoolean("cloud_backup_wifi_only", false)
    fun keepCopies() = store.getInt("cloud_backup_keep_copies", 10)
    fun maxAgeDays() = store.getInt("cloud_backup_max_age_days", 0)
    fun deviceName() = store.getString(
        Preference.appStateKey("cloud_backup_device_name"),
        android.os.Build.MODEL.take(80),
    )
}
