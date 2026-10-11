package eu.kanade.tachiyomi.data.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.PowerManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.cast.CastRemoteActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Keeps relay and progress updates alive while the user browses or turns off the phone screen. */
class CastSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var castController: CastController
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var mediaSession: MediaSession
    private var lastMedia: CastMedia? = null
    private var lastDuration = 0L
    private var adjustableVolume = false
    private val fixedVolume = object : VolumeProvider(VOLUME_CONTROL_FIXED, 100, 100) {}
    private val remoteVolume = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, 100) {
        override fun onSetVolumeTo(volume: Int) {
            scope.launch { castController.setVolume(volume / 100f) }
        }
        override fun onAdjustVolume(direction: Int) {
            scope.launch { castController.adjustVolume(direction * 0.05f) }
        }
    }
    override fun onBind(intent: Intent?) = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        castController = CastController.get(this)
        mediaSession = MediaSession(this, "UltraYomi Cast").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (castController.state.value.playback.paused) castController.togglePause()
                }
                override fun onPause() {
                    if (!castController.state.value.playback.paused) castController.togglePause()
                }
                override fun onSeekTo(pos: Long) {
                    castController.seek(pos)
                }
                override fun onSkipToNext() {
                    castController.next()
                }
                override fun onSkipToPrevious() {
                    castController.previous()
                }
                override fun onStop() {
                    castController.stop()
                }
            })
            setPlaybackToRemote(fixedVolume)
        }
        castController.state.onEach(::updateMediaSession).launchIn(scope)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Riproduzione sulla TV", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = notification(castController.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, notification)
        }
        // Held only for this explicit casting session; always released in onDestroy.
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "UltraYomi:CastRelay",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        castController.state.map {
            listOf(it.media?.title, it.media?.episodeName, it.device?.name, it.playback.paused, it.connecting, it.error)
        }.distinctUntilChanged().onEach {
            getSystemService(NotificationManager::class.java).notify(ID, notification(castController.state.value))
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!castController.state.value.active && !castController.state.value.connecting) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> castController.stop()
            ACTION_PAUSE -> castController.togglePause()
            ACTION_NEXT -> castController.next()
        }
        return START_NOT_STICKY
    }

    private fun notification(state: CastState) = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_play_arrow_24dp)
        .setContentTitle(state.media?.title ?: "Collegamento alla TV")
        .setContentText(state.error ?: listOfNotNull(state.device?.name, state.media?.episodeName).joinToString(" · "))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                ID,
                Intent(this, CastRemoteActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setStyle(
            Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1),
        )
        .addAction(
            if (state.playback.paused) R.drawable.ic_play_arrow_24dp else R.drawable.ic_pause_24dp,
            if (state.playback.paused) "Riprendi" else "Pausa",
            action(ACTION_PAUSE),
        )
        .apply { if (state.canNext) addAction(R.drawable.ic_skip_next_24dp, "Prossimo", action(ACTION_NEXT)) }
        .addAction(R.drawable.ic_close_24dp, "Interrompi", action(ACTION_STOP))
        .build()

    private fun updateMediaSession(state: CastState) {
        mediaSession.isActive = state.active
        if (state.media != lastMedia || state.playback.durationMs != lastDuration) {
            lastMedia = state.media
            lastDuration = state.playback.durationMs
            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, state.media?.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, state.media?.episodeName)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, state.device?.name)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, state.playback.durationMs)
                    .build(),
            )
        }
        val playback = state.playback
        if (adjustableVolume != playback.canSetVolume) {
            adjustableVolume = playback.canSetVolume
            mediaSession.setPlaybackToRemote(if (adjustableVolume) remoteVolume else fixedVolume)
        }
        remoteVolume.setCurrentVolume((playback.volume.coerceIn(0f, 1f) * 100).toInt())
        val status = when {
            state.needsReconnect -> PlaybackState.STATE_ERROR
            state.connecting || playback.buffering -> PlaybackState.STATE_BUFFERING
            !state.active || playback.finished -> PlaybackState.STATE_STOPPED
            playback.paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (playback.canSeek && playback.durationMs > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (state.canNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (state.canPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        mediaSession.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(status, playback.positionMs, if (status == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build(),
        )
    }

    private fun action(name: String) = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, CastSessionService::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (castController.state.value.active && !castController.state.value.connecting) castController.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cast_playback"
        private const val ID = 62017
        private const val ACTION_PAUSE = "cast.pause"
        private const val ACTION_NEXT = "cast.next"
        private const val ACTION_STOP = "cast.stop"
    }
}
