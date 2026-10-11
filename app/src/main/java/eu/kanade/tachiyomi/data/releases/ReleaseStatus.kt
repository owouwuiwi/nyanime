package eu.kanade.tachiyomi.data.releases

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications

data class ReleaseStatusSnapshot(
    val followed: Int = 0,
    val checked: Int = 0,
    val retrying: Int = 0,
    val lastSuccess: Long = 0,
)

object ReleaseStatus {
    suspend fun snapshot(): ReleaseStatusSnapshot {
        val store = ReleaseStore()
        val states = ReleaseMedium.entries.flatMap { medium ->
            store.monitoredIds(medium).filter {
                ReleaseEligibility.source(medium, it) !=
                    null
            }.map { store.check(medium, it) }
        }
        return ReleaseStatusSnapshot(
            states.size,
            states.count { it.lastSuccess > 1 },
            states.count { it.failures > 0 },
            states.maxOfOrNull { it.lastSuccess } ?: 0,
        )
    }

    fun showTestNotification(context: Context, channel: String = Notifications.CHANNEL_NEW_CHAPTERS_EPISODES) {
        if (channel == ReleaseReminders.CHANNEL) ReleaseReminders.createChannel(context)
        if (!ReleaseNotifications.canPost(context, channel)) {
            context.startActivity(
                Intent(
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            return
        }
        NotificationManagerCompat.from(context).notify(
            "release-test-$channel",
            23062,
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_ani).setContentTitle("Nyanime")
                .setContentText(context.getString(R.string.release_test_description)).setAutoCancel(true).build(),
        )
    }
}
