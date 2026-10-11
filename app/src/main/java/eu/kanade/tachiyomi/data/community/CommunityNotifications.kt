package eu.kanade.tachiyomi.data.community

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.community.CommunityActivity

internal class CommunityNotifications(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun message(conversation: String, title: String, detail: String = "Hai un nuovo messaggio su Nyanime") {
        if (!BuildConfig.COMMUNITY_ENABLED || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Amici, chat e inviti", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val id = conversation.hashCode()
        val intent = Intent(context, CommunityActivity::class.java).putExtra("conversation", conversation)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            manager.notify(
                id,
                Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_play_arrow_24dp)
                    .setContentTitle(
                        title,
                    ).setContentText(detail).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setContentIntent(open).setAutoCancel(true).setGroup(CHANNEL).build(),
            )
        }
    }
    companion object {
        private const val CHANNEL = "community_messages"
    }
}
