package eu.kanade.tachiyomi.data.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.watch.WatchTogetherActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Room control and optional voice share one notification; video retains its own lifecycle. */
class WatchSessionService : Service() {
    private val text by lazy { RoomText.from(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, text(R.string.room_notification), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, notification)
        }
        val rooms = WatchTogetherManager.get(this)
        combine(
            rooms.controller.state,
            rooms.readingSession.state,
            rooms.voice.state.map { Triple(it.active, it.muted, it.available) }.distinctUntilChanged(),
        ) { room, reading, voice ->
            Triple(room.active, room.message, reading.selection to voice)
        }.distinctUntilChanged().onEach {
            if (!it.first) {
                stopSelf()
            } else {
                val voice = rooms.voice.state.value
                foreground(
                    voice.active,
                    voice.active &&
                        !voice.muted &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED,
                )
            }
        }.launchIn(scope)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rooms = WatchTogetherManager.get(this)
        when (intent?.action) {
            STOP -> rooms.controller.leave()
            JOIN_VOICE -> if (rooms.controller.active) {
                runCatching {
                    foreground(voice = true, microphone = false)
                    rooms.voice.serviceJoin()
                }.onFailure { rooms.voice.serviceUnavailable() }
            }
            UNMUTE_VOICE -> if (rooms.voice.state.value.active &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    foreground(voice = true, microphone = true)
                    rooms.voice.unmute()
                }.onFailure { rooms.voice.mute() }
            }
            MUTE_VOICE -> rooms.voice.mute()
            LEAVE_VOICE -> rooms.voice.leave()
        }
        return START_NOT_STICKY
    }
    private fun foreground(voice: Boolean, microphone: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (voice) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) or
                (
                    if (microphone &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        0
                    }
                    )
            startForeground(ID, notification(), type)
        } else {
            startForeground(ID, notification())
        }
    }
    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_play_arrow_24dp)
        .setContentTitle(text(R.string.room_notification))
        .setContentText(notificationText())
        .setStyle(Notification.BigTextStyle().bigText(notificationText()))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                ID,
                Intent(this, WatchTogetherActivity::class.java).setAction(WatchTogetherActivity.OPEN_SHARED),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true).setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .addAction(
            R.drawable.ic_close_24dp,
            text(R.string.room_leave),
            PendingIntent.getService(
                this,
                ID,
                Intent(this, WatchSessionService::class.java).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).apply {
            val voice = WatchTogetherManager.get(this@WatchSessionService).voice.state.value
            if (voice.active) {
                if (!voice.muted) {
                    addAction(
                        R.drawable.ic_close_24dp,
                        text(R.string.room_voice_mute),
                        action(
                            MUTE_VOICE,
                            ID + 1,
                        ),
                    )
                }
                addAction(R.drawable.ic_close_24dp, text(R.string.room_voice_leave), action(LEAVE_VOICE, ID + 2))
            }
        }.build()
    private fun action(value: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, WatchSessionService::class.java).setAction(value),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    override fun onDestroy() {
        WatchTogetherManager.get(this).voice.leave()
        scope.cancel()
        super.onDestroy()
    }
    private fun notificationText(): String {
        val manager = WatchTogetherManager.get(this)
        val room = manager.controller.state.value
        val chapter = manager.readingSession.state.value.selection?.chapter.takeIf { room.sharedReadingMode }
        val content = chapter?.let { it.title + " · " + it.chapterName } ?: room.message
        val voice = manager.voice.state.value
        return if (voice.active) {
            text(if (voice.muted) R.string.room_voice_muted else R.string.room_voice_live) +
                " · " +
                content
        } else {
            content
        }
    }
    companion object {
        private const val CHANNEL = "watch_together"
        private const val ID = 4982
        private const val STOP = "nyanime.watch.STOP"
        const val JOIN_VOICE = "nyanime.watch.JOIN_VOICE"
        const val UNMUTE_VOICE = "nyanime.watch.UNMUTE_VOICE"
        const val MUTE_VOICE = "nyanime.watch.MUTE_VOICE"
        const val LEAVE_VOICE = "nyanime.watch.LEAVE_VOICE"
    }
}
