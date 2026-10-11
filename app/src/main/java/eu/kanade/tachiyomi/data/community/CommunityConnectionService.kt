package eu.kanade.tachiyomi.data.community

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.community.CommunityActivity

/** Opt-in continuous connection, visible and stoppable from Android. */
class CommunityConnectionService : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.COMMUNITY_ENABLED) {
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Community e sincronizzazione", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this,
            ID,
            Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            ID,
            Intent(this, CommunityActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_play_arrow_24dp)
            .setContentTitle("Nyanime • connesso ai tuoi dispositivi")
            .setContentText("Chat e sincronizzazione attive in background")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_close_24dp, "Interrompi", stop).build()
        if (Build.VERSION.SDK_INT >=
            34
        ) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(ID, notification)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.COMMUNITY_ENABLED) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            CommunityManager.get(this).setBackground(false)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    companion object {
        private const val CHANNEL = "community_sync"
        private const val ID = 4985
        private const val STOP = "nyanime.community.STOP"
    }
}
