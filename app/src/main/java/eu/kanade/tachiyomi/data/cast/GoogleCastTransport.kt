package eu.kanade.tachiyomi.data.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GoogleCastTransport(private val context: Context) : CastTransport {
    private val mutableDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val devices = mutableDevices.asStateFlow()
    private var cast: CastContext? = null
    private var router: MediaRouter? = null
    private var media: CastMedia? = null
    private var pendingSession: CompletableDeferred<CastSession>? = null
    private var discovering = false
    private val selector = MediaRouteSelector.Builder().addControlCategory(
        CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID),
    ).build()
    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = updateDevices()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = updateDevices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = updateDevices()
    }
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, id: String) {
            pendingSession?.complete(session)
        }
        override fun onSessionResumed(session: CastSession, suspended: Boolean) {
            pendingSession?.complete(session)
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            failConnection(error)
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            failConnection(error)
        }
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, id: String) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) {
            failConnection(error)
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    private suspend fun initialize() = withContext(Dispatchers.Main.immediate) {
        if (cast != null) return@withContext
        check(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            "Google Cast richiede Google Play Services disponibili e aggiornati"
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            cast = suspendCancellableCoroutine { continuation ->
                CastContext.getSharedInstance(context, executor)
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
        } finally {
            executor.shutdown()
        }
        cast!!.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        router = MediaRouter.getInstance(context)
    }

    override suspend fun discover() = withContext(Dispatchers.Main.immediate) {
        initialize()
        if (!discovering) {
            discovering = true
            router!!.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        }
        updateDevices()
    }

    override fun stopDiscovery() {
        router?.removeCallback(callback)
        discovering = false
    }

    private fun updateDevices() {
        mutableDevices.value = router?.routes.orEmpty()
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { CastDevice("google:" + it.id, it.name, CastProtocol.GOOGLE_CAST) }
    }

    private fun failConnection(error: Int) {
        pendingSession?.completeExceptionally(IllegalStateException("Connessione Google Cast interrotta ($error)"))
    }

    override suspend fun load(device: CastDevice, media: CastMedia) = withContext(Dispatchers.Main.immediate) {
        initialize()
        val route = router!!.routes.firstOrNull { "google:" + it.id == device.id }
            ?: error("La TV non è più disponibile. Cerca di nuovo.")
        val manager = cast!!.sessionManager
        val session = if (route.isSelected && manager.currentCastSession?.isConnected == true) {
            manager.currentCastSession!!
        } else {
            val pending = CompletableDeferred<CastSession>()
            pendingSession = pending
            try {
                router!!.selectRoute(route)
                withTimeout(25_000) { pending.await() }
            } finally {
                pendingSession = null
            }
        }
        val tracks = media.subtitles.mapIndexed { index, subtitle ->
            MediaTrack.Builder(index + 1L, MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(subtitle.url).setContentType("text/vtt").setName(subtitle.name).build()
        }
        val info = MediaInfo.Builder(media.url)
            .setContentType(media.mimeType)
            .setStreamType(if (media.live != null) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .apply { if (media.live == null) setStreamDuration(media.durationMs.coerceAtLeast(0)) }
            .setMetadata(
                MediaMetadata(MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
                    putString(MediaMetadata.KEY_TITLE, media.title)
                    putString(MediaMetadata.KEY_SUBTITLE, media.episodeName)
                },
            )
            .setMediaTracks(tracks)
            .build()
        val client = session.remoteMediaClient ?: error("Il ricevitore Google Cast non risponde")
        withTimeout(25_000) {
            client.load(
                MediaLoadRequestData.Builder().setMediaInfo(info)
                    .apply { if (media.live == null) setCurrentTime(media.startMs) }.setAutoplay(true).build(),
            ).awaitSuccess()
        }
        this@GoogleCastTransport.media = media
    }

    override suspend fun status(): CastPlayback = withContext(Dispatchers.Main.immediate) {
        val session = session()
        val client = session.remoteMediaClient ?: error("Il ricevitore non risponde")
        val status = client.mediaStatus ?: return@withContext CastPlayback(buffering = true)
        check(client.mediaInfo?.contentId == media?.url) { "La riproduzione sulla TV è cambiata" }
        check(status.idleReason != MediaStatus.IDLE_REASON_ERROR) {
            "La TV non supporta questo video o la fonte non risponde"
        }
        CastPlayback(
            positionMs = client.approximateStreamPosition,
            durationMs = client.streamDuration,
            paused = !client.isPlaying,
            buffering = client.isBuffering ||
                client.isLoadingNextItem ||
                status.playerState == MediaStatus.PLAYER_STATE_UNKNOWN ||
                (
                    status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                        status.idleReason == MediaStatus.IDLE_REASON_NONE
                    ),
            finished = status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                status.idleReason == MediaStatus.IDLE_REASON_FINISHED,
            canSeek = status.isMediaCommandSupported(MediaStatus.COMMAND_SEEK),
            canSetVolume = true,
            volume = session.volume.toFloat(),
        )
    }

    private fun session(): CastSession = cast?.sessionManager?.currentCastSession?.takeIf { it.isConnected }
        ?: error("Collegamento con la TV interrotto")

    override suspend fun pause(paused: Boolean) = withContext(Dispatchers.Main.immediate) {
        val client = session().remoteMediaClient ?: error("Il ricevitore non risponde")
        (if (paused) client.pause() else client.play()).awaitSuccess()
    }

    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.Main.immediate) {
        session().remoteMediaClient!!.seek(MediaSeekOptions.Builder().setPosition(positionMs).build()).awaitSuccess()
    }

    override suspend fun volume(value: Float) = withContext(Dispatchers.Main.immediate) {
        session().volume = value.coerceIn(0f, 1f).toDouble()
    }

    override suspend fun subtitle(index: Int) = withContext(Dispatchers.Main.immediate) {
        val ids = if (index < 0) longArrayOf() else longArrayOf(index + 1L)
        session().remoteMediaClient!!.setActiveMediaTracks(ids).awaitSuccess()
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        cast?.sessionManager?.endCurrentSession(true)
        media = null
    }

    private suspend fun <R : Result> PendingResult<R>.awaitSuccess() {
        suspendCancellableCoroutine<Unit> { continuation ->
            setResultCallback { result ->
                if (continuation.isActive) {
                    if (result.status.isSuccess) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException("Google Cast: comando fallito (${result.status.statusCode})"),
                        )
                    }
                }
            }
            continuation.invokeOnCancellation { cancel() }
        }
    }
}
