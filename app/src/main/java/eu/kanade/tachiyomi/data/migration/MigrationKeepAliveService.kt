package eu.kanade.tachiyomi.data.migration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R

/** Starts only from a foreground, explicitly authorized migration. No boot or sticky restart. */
class MigrationKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = object : Runnable {
        override fun run() {
            if (packageName == eu.kanade.tachiyomi.BuildConfig.LEGACY_APPLICATION_ID &&
                MigrationState.state(
                    this@MigrationKeepAliveService,
                ).optLong("grantUntil") <= System.currentTimeMillis() &&
                !MigrationState.frozen(this@MigrationKeepAliveService)
            ) {
                stopSelf()
            } else {
                handler.postDelayed(this, 30_000)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "identity-migration",
                getString(R.string.migration_title),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val target = if (packageName ==
            eu.kanade.tachiyomi.BuildConfig.LEGACY_APPLICATION_ID
        ) {
            MigrationActivity::class.java
        } else {
            MigrationActivity::class.java
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startForeground(
            7241,
            NotificationCompat.Builder(this, "identity-migration")
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(getString(R.string.migration_title))
                .setContentText(getString(R.string.migration_working)).setContentIntent(pending)
                .setOngoing(true).setProgress(0, 0, true).build(),
        )
        handler.postDelayed(expiry, 30_000)
    }
    override fun onDestroy() {
        handler.removeCallbacks(expiry)
        super.onDestroy()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        fun start(
            context: Context,
        ) = ContextCompat.startForegroundService(context, Intent(context, MigrationKeepAliveService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, MigrationKeepAliveService::class.java))
    }
}
