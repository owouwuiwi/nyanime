package eu.kanade.tachiyomi.extension

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
) {

    internal fun promptUpdates(updates: List<ExtensionUpdate>, anime: Boolean = false) = synchronized(
        announcementLock,
    ) {
        val announcements = ExtensionUpdateAnnouncements(
            preferenceStore,
            if (anime) ExtensionUpdateKind.ANIME else ExtensionUpdateKind.MANGA,
        )
        if (!announcements.hasNew(updates)) return@synchronized
        val names = updates.map { it.name }
        context.notify(
            notificationId(anime),
            Notifications.CHANNEL_EXTENSIONS_UPDATE,
        ) {
            setContentTitle(
                context.resources.getQuantityString(
                    R.plurals.update_check_notification_ext_updates,
                    names.size,
                    names.size,
                ),
            )
            if (!securityPreferences.hideNotificationContent().get()) {
                val extNames = names.joinToString(", ")
                setContentText(extNames)
                setStyle(NotificationCompat.BigTextStyle().bigText(extNames))
            }
            setSmallIcon(R.drawable.ic_extension_24dp)
            if (!anime) {
                setContentIntent(NotificationReceiver.openExtensionsPendingActivity(context))
            } else {
                setContentIntent(NotificationReceiver.openAnimeExtensionsPendingActivity(context))
            }
            setAutoCancel(true)
            setOnlyAlertOnce(true)
        }
        announcements.record(updates)
    }

    fun dismiss(anime: Boolean = false) {
        context.cancelNotification(notificationId(anime))
    }

    private fun notificationId(anime: Boolean): Int =
        if (anime) Notifications.ID_UPDATES_TO_ANIME_EXTS else Notifications.ID_UPDATES_TO_EXTS

    companion object {
        private val announcementLock = Any()
    }
}
