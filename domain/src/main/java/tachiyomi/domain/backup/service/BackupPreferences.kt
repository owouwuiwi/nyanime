package tachiyomi.domain.backup.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class BackupPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun backupInterval() = preferenceStore.getInt("backup_interval", 12)

    fun lastAutoBackupTimestamp() = preferenceStore.getLong(Preference.appStateKey("last_auto_backup_timestamp"), 0L)

    fun autoBackupFailures() = preferenceStore.getInt(Preference.appStateKey("auto_backup_failures"), 0)

    fun autoBackupError() = preferenceStore.getString(Preference.appStateKey("auto_backup_error"), "")

    fun backupFlags() = preferenceStore.getStringSet(
        "backup_flags",
        setOf(FLAG_CATEGORIES, FLAG_CHAPTERS, FLAG_HISTORY, FLAG_TRACK),
    )
}
