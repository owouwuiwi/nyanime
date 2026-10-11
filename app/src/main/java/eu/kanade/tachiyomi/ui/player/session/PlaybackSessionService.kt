package eu.kanade.tachiyomi.ui.player.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** A single service owns the native session; Activity recreation never restores a second decoder. */
class PlaybackSessionService : Service(), androidx.lifecycle.DefaultLifecycleObserver {
    private val wake by lazy {
        (
            getSystemService(
                android.os.PowerManager::class.java,
            )
            ).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Nyanime:Playback").apply {
            setReferenceCounted(false)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: PlayerSession? = null
    private var starting: Job? = null
    private var latest: Intent? = null
    private var notificationJob: Job? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super<Service>.onCreate()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.playback_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> {
                val selected = Intent(intent)
                latest = selected
                startForeground(NOTIFICATION, notification(session))
                val current = session
                if (current != null && starting?.isActive != true && current.available) {
                    current.present(Injekt.get<PlayerPreferences>().startPresentation().get().presentation)
                    current.open(selected)
                } else if (starting?.isActive != true) {
                    starting = scope.launch {
                        val next =
                            PlayerSession(this@PlaybackSessionService, ::closed, SystemClock.elapsedRealtimeNanos())
                        session = next
                        val initial = latest ?: selected
                        next.start(initial)
                        if (session === next && next.available) {
                            latest?.takeUnless { it === initial }?.let(next::open)
                            PlaybackSessions.set(next)
                            observeNotification(next)
                        } else if (session === next) {
                            next.close()
                            if (!next.available) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    }
                }
            }
            TOGGLE -> session?.playPause()
            NEXT -> session?.next()
            STOP -> session?.requestClose()
        }
        // An OS restart must not silently restart a file or falsely rejoin a room as ready.
        return START_NOT_STICKY
    }

    private fun observeNotification(current: PlayerSession) {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            var last: Triple<String, String, Boolean>? = null
            current.snapshot.collect { state ->
                current.updateMediaSessionState()
                if (!state.paused && !wake.isHeld) wake.acquire(10 * 60_000L)
                if (state.paused && wake.isHeld) wake.release()
                val key = Triple(state.title, state.episode, state.paused)
                if (last != key) {
                    last = key
                    notifications.notify(NOTIFICATION, notification(current))
                }
            }
        }
    }

    private fun closed(closed: PlayerSession) {
        if (session !== closed) return
        notificationJob?.cancel()
        if (wake.isHeld) wake.release()
        session = null
        PlaybackSessions.clear(closed)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun action(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, PlaybackSessionService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(current: PlayerSession?): android.app.Notification {
        val state = current?.snapshot?.value
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = OPEN
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseLabel = if (state?.paused != false) R.string.playback_play else R.string.playback_pause
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_play_arrow_24dp)
            .setContentTitle(state?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.playback_preparing))
            .setContentText(state?.episode)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(state?.paused == false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(
                if (state?.paused !=
                    false
                ) {
                    R.drawable.ic_play_arrow_24dp
                } else {
                    R.drawable.ic_pause_24dp
                },
                getString(pauseLabel),
                action(TOGGLE),
            )
            .apply {
                if (state?.live !=
                    true
                ) {
                    addAction(R.drawable.ic_skip_next_24dp, getString(R.string.playback_next), action(NEXT))
                }
            }
            .addAction(R.drawable.ic_close_24dp, getString(R.string.playback_close), action(STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(current?.mediaSessionToken)
                    .setShowActionsInCompactView(*if (state?.live == true) intArrayOf(0, 1) else intArrayOf(0, 1, 2)),
            )
            .build()
    }

    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
        val current = session ?: return
        current.viewModel.cancelPlaybackScrub(
            restorePlayback = current.viewModel.playerPreferences.backgroundAudio().get() || current.pip.value,
        )
        if (!current.viewModel.playerPreferences.backgroundAudio().get() && !current.pip.value) {
            current.viewModel.watchTogether.hold()
            current.pause()
        }
    }

    override fun onDestroy() {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        if (wake.isHeld) wake.release()
        starting?.cancel()
        session?.close()
        scope.cancel()
        super<Service>.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Background audio is intentional. The notification is the explicit stop control.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val OPEN = "nyanime.playback.OPEN"
        internal const val OPEN_START = "nyanime.playback.OPEN_START"
        private const val PLAY = "nyanime.playback.PLAY"
        private const val TOGGLE = "nyanime.playback.TOGGLE"
        private const val NEXT = "nyanime.playback.NEXT"
        private const val STOP = "nyanime.playback.STOP"
        private const val CHANNEL = "nyanime.playback"
        private const val NOTIFICATION = 7100

        fun play(context: Context, request: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(request).setClass(context, PlaybackSessionService::class.java).setAction(PLAY),
            )
        }
    }
}

/** Process-local handle to the service. No UI component creates an engine through this registry. */
object PlaybackSessions {
    private val mutableCurrent = MutableStateFlow<PlayerSession?>(null)
    val current = mutableCurrent.asStateFlow()
    internal fun set(session: PlayerSession) {
        mutableCurrent.value = session
    }
    internal fun clear(session: PlayerSession) {
        if (mutableCurrent.value === session) mutableCurrent.value = null
    }
}
