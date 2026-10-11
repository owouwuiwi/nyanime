package eu.kanade.tachiyomi.data.cleanup

import android.app.NotificationManager
import android.content.Context
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import java.security.KeyStore

/** One successful cleanup per installation; failures are retried without starting retired code. */
internal object RetiredProfileCleanup {
    fun run(context: Context, preferences: PreferenceStore) {
        val completed = preferences.getBoolean(Preference.appStateKey("retired_profile_cleanup_v1"), false)
        if (completed.get()) return
        try {
            context.getSystemService(NotificationManager::class.java)?.let { notifications ->
                notifications.activeNotifications.filter {
                    it.notification.channelId in channels
                }.forEach { notifications.cancel(it.tag, it.id) }
                channels.forEach(notifications::deleteNotificationChannel)
            }
            val keys by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
            val failures = RetiredProfileData.clean(
                context.noBackupFilesDir,
                context::getDatabasePath,
            ) { alias -> if (keys.containsAlias(alias)) keys.deleteEntry(alias) }
            listOf("personal_sync_enabled", Preference.appStateKey("personal_sync_enabled")).forEach {
                preferences.getBoolean(it).delete()
            }
            if (failures.isEmpty()) {
                completed.set(true)
            } else {
                logcat(LogPriority.WARN) { "Retired private data cleanup incomplete: ${failures.joinToString()}" }
            }
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Unable to complete retired private data cleanup" }
        }
    }

    private val channels = setOf("community_messages", "community_sync")
}
