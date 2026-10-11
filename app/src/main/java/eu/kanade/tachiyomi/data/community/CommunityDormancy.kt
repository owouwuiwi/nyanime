package eu.kanade.tachiyomi.data.community

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.sqlite.db.SupportSQLiteDatabase
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.KeyStore

/** Stops old work and removes retired private data without instantiating a social/sync runtime. */
internal object CommunityDormancy {
    fun onDatabaseOpen(database: SupportSQLiteDatabase) {
        if (!BuildConfig.COMMUNITY_ENABLED) {
            // A previous process may have persisted enabled=1. Reset before any library write.
            val enabled = BuildConfig.PERSONAL_SYNC_ENABLED &&
                run {
                    val preferences: BasePreferences = Injekt.get()
                    PersonalSyncPolicy.capture(
                        preferences.personalSyncEnabled().get() &&
                            CommunityManager.hasPersonalIdentity(
                                preferences.context,
                            ),
                        preferences.incognitoMode().get(),
                    )
                }
            database.execSQL("UPDATE community_capture SET enabled = ?", arrayOf(if (enabled) 1 else 0))
            if (!BuildConfig.PERSONAL_SYNC_ENABLED) {
                CommunityRetirement.libraryCleanup.forEach(database::execSQL)
            }
        }
    }

    fun stopBackgroundWork(context: Context) {
        if (BuildConfig.COMMUNITY_ENABLED) return
        runCatching {
            context.stopService(
                Intent().setComponent(
                    ComponentName(context.packageName, "eu.kanade.tachiyomi.data.community.CommunityConnectionService"),
                ),
            )
        }
            .onFailure { logcat(LogPriority.WARN, it) { "Unable to stop dormant community service" } }
        runCatching {
            val notifications = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
            notifications.activeNotifications.filter {
                ownsNotificationChannel(it.notification.channelId)
            }.forEach { notifications.cancel(it.tag, it.id) }
            notifications.deleteNotificationChannel("community_messages")
            notifications.deleteNotificationChannel("community_sync")
        }.onFailure { logcat(LogPriority.WARN, it) { "Unable to clear dormant community notifications" } }
    }

    fun cleanObsoleteData(context: Context, preferences: BasePreferences) = runCatching {
        if (BuildConfig.COMMUNITY_ENABLED && BuildConfig.PERSONAL_SYNC_ENABLED) return@runCatching
        val keys by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
        val failures = CommunityRetirement.clean(
            context.noBackupFilesDir,
            context::getDatabasePath,
            deleteKey = { alias -> if (keys.containsAlias(alias)) keys.deleteEntry(alias) },
            social = !BuildConfig.COMMUNITY_ENABLED,
            personal = !BuildConfig.PERSONAL_SYNC_ENABLED,
        )
        if (!BuildConfig.PERSONAL_SYNC_ENABLED) preferences.personalSyncEnabled().delete()
        if (failures.isNotEmpty()) {
            // No completion marker: failed removals are retried at the next application start.
            logcat(LogPriority.WARN) { "Obsolete private data cleanup incomplete: ${failures.joinToString()}" }
        }
    }.onFailure { logcat(LogPriority.WARN, it) { "Unable to finish obsolete private data cleanup" } }

    internal fun ownsNotificationChannel(channel: String?) =
        channel == "community_messages" || channel == "community_sync"
}
