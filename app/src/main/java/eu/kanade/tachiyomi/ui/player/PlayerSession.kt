/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Code is a mix between PlayerActivity from mpvKt and the former
 * PlayerActivity from Aniyomi.
 */

package eu.kanade.tachiyomi.ui.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import aniyomi.core.common.torrent.TorrentPreferences
import aniyomi.core.common.torrent.TorrentServerApi
import aniyomi.core.common.torrent.TorrentServerUtils
import com.hippo.unifile.UniFile
import eu.kanade.domain.items.episode.model.toSEpisode
import eu.kanade.presentation.share.ContentShareDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.data.cast.CastHandoffPolicy
import eu.kanade.tachiyomi.data.cast.CastRequest
import eu.kanade.tachiyomi.data.database.models.anime.toDomainEpisode
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraFiles
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraPlaybackGuard
import eu.kanade.tachiyomi.data.download.transfer.DownloadPlaybackGate
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.share.ContentLink
import eu.kanade.tachiyomi.data.share.SharedMedium
import eu.kanade.tachiyomi.data.torrent.service.TorrentServerService
import eu.kanade.tachiyomi.databinding.PlayerLayoutBinding
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.player.controls.PlayerControls
import eu.kanade.tachiyomi.ui.player.session.LivePlayback
import eu.kanade.tachiyomi.ui.player.session.MpvPlaybackEngine
import eu.kanade.tachiyomi.ui.player.session.MpvSurfaceView
import eu.kanade.tachiyomi.ui.player.session.PlaybackPresentation
import eu.kanade.tachiyomi.ui.player.session.PlaybackSnapshot
import eu.kanade.tachiyomi.ui.player.session.PlaybackWindowMode
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils.Companion.getStringRes
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.privacy.PrivacyMedia
import eu.kanade.tachiyomi.ui.privacy.observeContentIncognito
import eu.kanade.tachiyomi.ui.privacy.registerNsfwView
import eu.kanade.tachiyomi.util.system.powerManager
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import logcat.LogPriority
import nyanime.tv.api.TvChannelRef
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.custombuttons.model.CustomButton
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

/** Owns playback, not a screen. Only the service creates and closes this object. */
class PlayerSession(
    context: Context,
    private val onClosed: (PlayerSession) -> Unit,
    internal val sessionId: Long,
) : android.content.ContextWrapper(context), eu.kanade.tachiyomi.ui.player.session.PlaybackCommands {
    private val ownerJob = kotlinx.coroutines.SupervisorJob()
    private val lifecycleScope = CoroutineScope(Dispatchers.Main.immediate + ownerJob)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var host = java.lang.ref.WeakReference<BaseActivity>(null)
    internal val hostActivity: BaseActivity? get() = host.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    internal var intent: Intent = Intent()
        private set
    private val modelStore = androidx.lifecycle.ViewModelStore()
    private val model = lazy {
        PlayerViewModel(this, androidx.lifecycle.SavedStateHandle()).also { modelStore.put("player", it) }
    }
    val viewModel get() = model.value
    private val mutableSnapshot = kotlinx.coroutines.flow.MutableStateFlow(
        PlaybackSnapshot(sessionId),
    )
    override val snapshot = mutableSnapshot.asStateFlow()
    val presentation = kotlinx.coroutines.flow.MutableStateFlow(
        Injekt.get<PlayerPreferences>().startPresentation().get().presentation,
    )
    internal val miniLayout = kotlinx.coroutines.flow.MutableStateFlow(
        eu.kanade.tachiyomi.ui.player.session.MiniLayout(),
    )
    val pip = kotlinx.coroutines.flow.MutableStateFlow(false)
    val preparingSelection = kotlinx.coroutines.flow.MutableStateFlow(false)
    val closeRestriction = kotlinx.coroutines.flow.MutableStateFlow(false)
    internal val live = kotlinx.coroutines.flow.MutableStateFlow<LivePlayback?>(null)
    private var liveSelectionJob: Job? = null
    private var selectionJob: Job? = null
    private var selectionGeneration = 0L
    var isDestroyed = false
        private set
    private var closing = false
    private var miniDismissalPending = false
    internal val available get() = nativeReady && !closing && !isDestroyed
    private val isFinishing get() = closing
    val isInPictureInPictureMode get() = pip.value
    private var pipRect: Rect? = null
    private var videoAspect = 16.0 / 9.0
    internal val replacementFrame = kotlinx.coroutines.flow.MutableStateFlow<android.graphics.Bitmap?>(null)
    internal val windowFrame = kotlinx.coroutines.flow.MutableStateFlow<android.graphics.Bitmap?>(null)
    private var windowTransitionJob: Job? = null
    private val windowMode = PlaybackWindowMode()
    internal fun windowFrameReady(frame: android.graphics.Bitmap) {
        if (windowFrame.value === frame) {
            windowFrame.value = null
            updateHostPresentation()
        }
    }
    private var renderer = java.lang.ref.WeakReference<MpvSurfaceView>(null)
    internal fun renderHost(view: MpvSurfaceView?) {
        renderer = java.lang.ref.WeakReference(view)
    }
    internal fun releaseRenderHost(view: MpvSurfaceView) {
        if (renderer.get() === view) renderer.clear()
    }
    internal suspend fun captureReplacement() {
        replacementFrame.value = try {
            kotlinx.coroutines.withTimeoutOrNull(120) { renderer.get()?.captureFrame() }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
    internal fun replacementReady() {
        replacementFrame.value = null
    }

    fun attachHost(activity: BaseActivity) {
        if (closing || isDestroyed) return
        // Background reader compositions can observe a new session, but only the resumed
        // rendering host may change its presentation or claim the native surface.
        if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        host.get()?.takeUnless { it === activity }?.let(::detachHost)
        host = java.lang.ref.WeakReference(activity)
        viewModel.watchManager.present(activity)
        if (activity is eu.kanade.tachiyomi.ui.reader.ReaderActivity &&
            presentation.value != PlaybackPresentation.Mini
        ) {
            present(PlaybackPresentation.Mini)
        } else {
            updateHostPresentation()
        }
    }

    fun detachHost(activity: BaseActivity) {
        if (host.get() !== activity) return
        // PiP parameters belong to the Activity and outlive the service-owned player.
        // Revoke them before the renderer disappears or another Activity claims the video.
        eu.kanade.tachiyomi.ui.player.session.PlaybackActivityBridge.disablePip(activity)
        windowMode.restore(activity)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f }
        if (activity is eu.kanade.tachiyomi.ui.main.MainActivity) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        host.clear()
        if (nativeReady && !closing) viewModel.saveCurrentEpisodeWatchingProgress()
    }

    fun present(value: PlaybackPresentation) {
        if (closing) return
        val screen = hostActivity
        val rotatesToPortrait =
            (presentation.value == PlaybackPresentation.Fullscreen || windowTransitionJob?.isActive == true) &&
                value != PlaybackPresentation.Fullscreen &&
                !pip.value &&
                screen is eu.kanade.tachiyomi.ui.main.MainActivity &&
                screen.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        windowTransitionJob?.cancel()
        windowFrame.value = null
        viewModel.endHoldSpeed()
        presentation.value = value
        if (rotatesToPortrait) {
            windowTransitionJob = lifecycleScope.launch {
                windowFrame.value = try {
                    kotlinx.coroutines.withTimeoutOrNull(80) { renderer.get()?.captureFrame() }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
                if (windowFrame.value != null) {
                    kotlinx.coroutines.withTimeoutOrNull(80) {
                        eu.kanade.tachiyomi.ui.player.session.awaitPlaybackWindowFrame(
                            requireNotNull(screen).window.decorView,
                        )
                    }
                }
                windowTransitionJob = null
                if (!closing && presentation.value == value && hostActivity === screen) updateHostPresentation()
            }
        } else {
            updateHostPresentation()
        }
    }

    fun back(): Boolean {
        if (viewModel.sheetShown.value != Sheets.None ||
            viewModel.panelShown.value != Panels.None ||
            viewModel.dialogShown.value != Dialogs.None
        ) {
            viewModel.showSheet(Sheets.None)
            viewModel.showPanel(Panels.None)
            viewModel.showDialog(Dialogs.None)
            return true
        }
        return when (presentation.value) {
            PlaybackPresentation.Fullscreen -> {
                present(PlaybackPresentation.Page)
                true
            }
            PlaybackPresentation.Page -> {
                present(PlaybackPresentation.Mini)
                true
            }
            else -> false
        }
    }

    private fun setIntent(value: Intent) {
        intent = value
    }
    internal fun runOnUiThread(action: () -> Unit) {
        if (android.os.Looper.myLooper() === handler.looper) {
            if (!closing && !isDestroyed) action()
        } else {
            handler.post { if (!closing && !isDestroyed) action() }
        }
    }

    internal fun setKeepScreenOn(value: Boolean) {
        hostActivity?.window?.let { window ->
            if (value && !pip.value) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    internal fun showStatusBars(value: Boolean) {
        val screen = hostActivity ?: return
        if (screen is eu.kanade.tachiyomi.ui.reader.ReaderActivity) return
        val controller = WindowCompat.getInsetsController(screen.window, screen.window.decorView)
        if (value ||
            presentation.value != PlaybackPresentation.Fullscreen
        ) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    internal fun applyBrightness(value: Float) {
        hostActivity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = value } }
    }

    internal fun displayMetrics(): android.util.DisplayMetrics = android.util.DisplayMetrics().apply {
        setTo(resources.displayMetrics)
        pipRect?.takeUnless { it.isEmpty }?.let {
            widthPixels = it.width()
            heightPixels = it.height()
        }
    }

    internal var requestedOrientation: Int
        get() = hostActivity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        set(value) {
            hostActivity?.requestedOrientation = value
        }

    private fun updateHostPresentation() {
        if (closing || isDestroyed) return
        if (windowTransitionJob?.isActive == true) return
        val screen = hostActivity ?: return
        // PiP owns a temporary window geometry, not a different playback presentation.
        // Do not request orientation or alter its window flags during its resize callbacks.
        if (pip.value || (Build.VERSION.SDK_INT >= 24 && screen.isInPictureInPictureMode)) {
            if (nativeReady) applySurfaceAspect()
            return
        }
        val fullscreen = presentation.value == PlaybackPresentation.Fullscreen
        val rotatingToPage = !fullscreen &&
            screen is eu.kanade.tachiyomi.ui.main.MainActivity &&
            (
                screen.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
                    windowFrame.value != null
                )
        if (screen is eu.kanade.tachiyomi.ui.main.MainActivity) {
            windowMode.apply(screen, fullscreen || rotatingToPage)
        }
        val bars = WindowCompat.getInsetsController(screen.window, screen.window.decorView)
        val casting = castController.state.value.active || castController.state.value.connecting
        if (fullscreen) {
            bars.hide(WindowInsetsCompat.Type.systemBars())
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            setupPlayerOrientation()
        } else if (screen is eu.kanade.tachiyomi.ui.main.MainActivity) {
            if (!rotatingToPage) bars.show(WindowInsetsCompat.Type.systemBars())
            screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (casting) screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        screen.volumeControlStream = AudioManager.STREAM_MUSIC
        if (nativeReady) {
            setKeepScreenOn(!snapshot.value.paused)
            applySurfaceAspect()
            setPictureInPictureParams(createPipParams())
        }
    }

    internal fun applySurfaceAspect() {
        if (!nativeReady || player.isExiting) return
        // The fixed renderer retains the source proportions. Its host applies fit/crop/stretch;
        // applying the same policy inside MPV would crop twice or add transformed black borders.
        MPVLib.setPropertyDouble("panscan", 0.0)
        MPVLib.setPropertyDouble("video-aspect-override", -1.0)
    }

    internal fun surfaceBounds(rect: Rect) {
        pipRect = rect
    }
    internal fun refreshPipGeometry() {
        if (nativeReady && !pip.value && available) setPictureInPictureParams(createPipParams())
    }
    internal fun setMiniDismissalPending(value: Boolean) {
        if (miniDismissalPending == value) return
        miniDismissalPending = value
        val screen = hostActivity ?: return
        if (value) {
            // Android may enter PiP before the exit animation completes. Revoke it at
            // the accepted dismissal gesture, and prevent native callbacks rearming it.
            eu.kanade.tachiyomi.ui.player.session.PlaybackActivityBridge.disablePip(screen)
        } else if (available) {
            // A cancelled animation or a room refusing observation keeps its player.
            setPictureInPictureParams(createPipParams())
        }
    }
    fun setPictureInPictureParams(params: PictureInPictureParams) {
        val screen = hostActivity ?: return
        if (!available) return
        if (isPipSupportedAndEnabled) {
            runCatching { screen.setPictureInPictureParams(params) }
        } else {
            eu.kanade.tachiyomi.ui.player.session.PlaybackActivityBridge.disablePip(screen)
        }
    }
    fun enterPictureInPictureMode(params: PictureInPictureParams = createPipParams()): Boolean {
        val screen = hostActivity ?: return false
        return canEnterPictureInPicture && runCatching { screen.enterPictureInPictureMode(params) }.getOrDefault(false)
    }

    internal val canEnterPictureInPicture: Boolean
        get() {
            if (miniDismissalPending ||
                !available ||
                !isPipSupportedAndEnabled ||
                hostActivity == null ||
                player.isExiting
            ) {
                return false
            }
            val cast = castController.state.value
            return !cast.active && !cast.connecting && (player.videoH ?: 0) > 0
        }

    override fun startActivity(intent: Intent) {
        val screen = hostActivity
        if (screen != null) {
            screen.startActivity(intent)
        } else {
            super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun playPause() {
        if (!available) return
        val runtime = live.value
        if (runtime !=
            null
        ) {
            if (runtime.session.state.value.paused) runtime.session.live() else runtime.session.pause(true)
        } else {
            viewModel.pauseUnpause()
        }
    }
    override fun seek(seconds: Double) {
        if (available) {
            if (live.value !=
                null
            ) {
                live.value?.session?.seek(seconds)
            } else {
                viewModel.seekTo(seconds.toInt())
            }
        }
    }
    internal fun pause() {
        if (!available) return
        live.value?.session?.pause(true) ?: viewModel.pauseByUser()
    }
    override fun next() {
        if (!available || live.value != null) return
        viewModel.changeEpisode(false)
    }
    internal val mediaSessionToken: android.support.v4.media.session.MediaSessionCompat.Token?
        get() = mediaSession?.sessionToken?.let {
            android.support.v4.media.session.MediaSessionCompat.Token.fromToken(it)
        }
    private var mediaMetadataKey: Triple<String, String, Long>? = null
    internal fun updateMediaSessionState() {
        if (!nativeReady || closing) return
        val state = snapshot.value
        val key = Triple(state.title, state.episode, (state.durationSeconds * 1000).toLong())
        if (mediaMetadataKey != key) {
            mediaMetadataKey = key
            mediaSession?.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, key.first)
                    .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, key.second)
                    .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, key.third).build(),
            )
        }
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
                        (
                            if (state.live) {
                                0L
                            } else {
                                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                    PlaybackState.ACTION_SKIP_TO_NEXT
                            }
                            ) or
                        (if (state.seekable) PlaybackState.ACTION_SEEK_TO else 0L),
                )
                .setState(
                    if (state.buffering) {
                        PlaybackState.STATE_BUFFERING
                    } else if (state.paused) {
                        PlaybackState.STATE_PAUSED
                    } else {
                        PlaybackState.STATE_PLAYING
                    },
                    (state.positionSeconds * 1000).toLong(),
                    viewModel.playbackSpeed.value,
                ).build(),
        )
    }
    internal fun requestClose() {
        // Room observation is negotiated by the room controller before native teardown.
        if (viewModel.watchTogether.active) {
            if (!viewModel.watchManager.becomeObserver()) {
                closeRestriction.value = true
                return
            }
        }
        close()
    }
    private fun finish() = close()
    private fun finishAndRemoveTask() = close()

    private var nativeOwned = false
    private var nativeInitialized = false
    private var nativeReady = false
    private val trackLoadingMutex = Mutex()
    val castController by lazy { CastController.get(applicationContext) }

    fun castRequest(video: Video? = viewModel.currentVideo.value): CastRequest? {
        val anime = viewModel.currentAnime.value ?: return null
        val episode = viewModel.currentEpisode.value ?: return null
        val episodeId = episode.id ?: return null
        val media = castController.state.value.media?.takeIf { it.episodeId == episodeId }
        return CastRequest(
            anime.id, episodeId, anime.source, anime.title, episode.name, video ?: return null,
            CastHandoffPolicy.startPosition(
                remotePositionMs = media?.let { castController.state.value.playback.positionMs },
                loadingEpisode = viewModel.isLoadingEpisode.value,
                savedPositionMs = episode.last_second_seen,
                seen = episode.seen,
                preserveSeenPosition = playerPreferences.preserveWatchingPosition().get(),
                localPositionMs = (viewModel.pos.value * 1000).toLong(),
            ),
            when {
                media != null -> castController.state.value.playback.durationMs
                viewModel.isLoadingEpisode.value -> episode.total_seconds
                else -> (viewModel.duration.value * 1000).toLong()
            },
            viewModel.currentPlaylist.value.mapNotNull { it.id },
            playerPreferences.autoplayEnabled().get(),
        )
    }

    fun resumeAfterCast() {
        val anime = viewModel.currentAnime.value ?: return
        val episode = viewModel.currentEpisode.value ?: return
        open(PlayerActivity.newIntent(this, anime.id, episode.id))
    }
    internal fun openWatchVideo(animeId: Long, episodeId: Long) {
        open(PlayerActivity.newIntent(this, animeId, episodeId))
    }
    private val playerObserver by lazy { PlayerObserver(this) }
    val player = MpvPlaybackEngine(applicationContext)
    val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var mediaSession: MediaSession? = null
    private val gesturePreferences: GesturePreferences by lazy { viewModel.gesturePreferences }
    private val playerPreferences: PlayerPreferences by lazy { viewModel.playerPreferences }
    private val audioPreferences: AudioPreferences = Injekt.get()
    val headphoneCinema by lazy {
        HeadphoneCinemaController(this, lifecycleScope, audioPreferences) { !player.isExiting }
    }
    private val advancedPlayerPreferences: AdvancedPlayerPreferences = Injekt.get()
    private val networkPreferences: NetworkPreferences = Injekt.get()
    private val storageManager: StorageManager = Injekt.get()
    private val torrentServerApi: TorrentServerApi = Injekt.get()
    private val torrentServerUtils: TorrentServerUtils = Injekt.get()
    private val torrentPreferences: TorrentPreferences = Injekt.get()

    private var audioFocusRequest: AudioFocusRequestCompat? = null
    private var restoreAudioFocus: () -> Unit = {}

    val isPipSupportedAndEnabled: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            playerPreferences.enablePip().get()

    private var pipReceiver: BroadcastReceiver? = null
    private var httpServer: HttpServer? = null
    private val anime4kShaderPipeline by lazy { Anime4KShaderPipeline(this) }
    private var fileLoadedJob: Job? = null
    private var videoLoadJob: Job? = null
    private var handledFailureGeneration = -1L
    internal val playbackGeneration: Long get() = viewModel.playbackLoadState.value.generation
    private var anime4kAppliedRoomRestriction: Boolean? = null
    private var anime4kRoomRestorePending = false
    private var anime4kSmartController: Anime4KSmartController? = null
    private var anime4kSmartNeedsMediaResolution = true
    private var anime4kSmartResolutionRetryCount = 0
    private var anime4kSmartRetryPending = false
    private var anime4kMediaResolutionGeneration = 0L
    private var anime4kTelemetryGeneration = 0L
    private var anime4kTelemetrySession: Anime4KTelemetrySession? = null
    private var anime4kCalibrationKey: String? = null
    private var anime4kCalibrationMediaInfo: Anime4KMediaInfo? = null
    private var anime4kLastSavedCalibration: Anime4KCalibration? = null
    private val anime4kMediaRefresh by lazy {
        Anime4KMediaRefresh(
            schedule = { delay, refresh ->
                handler.postDelayed({ if (!isFinishing && !isDestroyed && !player.isExiting) refresh() }, delay)
            },
            refresh = ::applySmartAnime4KForLoadedMedia,
        )
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        var initialized = false
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                live.value?.let {
                    it.session.pause(true)
                    return
                }
                viewModel.watchTogether.hold()
                viewModel.pause()
                setKeepScreenOn(false)
            }
        }
    }

    private var sharedStart: Pair<Long, Long>? = null

    private fun readSharedStart(intent: Intent): Pair<Long, Long>? {
        if (!intent.hasExtra(SHARED_START)) return null
        val episode = intent.getLongExtra("episodeId", -1)
        val position = intent.getLongExtra(SHARED_START, -1)
        return if (episode >= 0 && position in 0..eu.kanade.tachiyomi.data.share.ContentLinks.MAX_POSITION_MS) {
            episode to position
        } else {
            null
        }
    }

    fun shareCurrentContent() {
        val anime = viewModel.currentAnime.value ?: return
        val episode = viewModel.currentEpisode.value ?: return
        if (viewModel.currentSource.value !is AnimeHttpSource) return
        ContentShareDialog.show(
            hostActivity ?: return,
            ContentLink(
                medium = SharedMedium.ANIME,
                sourceId = anime.source,
                sourceName = viewModel.currentSource.value?.name,
                entryUrl = anime.url,
                title = anime.title,
                itemUrl = episode.url,
                itemTitle = episode.name,
                positionMs = (viewModel.pos.value.toDouble() * 1000).toLong().coerceIn(
                    0,
                    eu.kanade.tachiyomi.data.share.ContentLinks.MAX_POSITION_MS,
                ),
            ),
        )
    }

    companion object {
        private const val SHARED_START = PlayerActivity.SHARED_START
        private const val MPV_DIR = PlayerActivity.MPV_DIR
        private const val MPV_FONTS_DIR = "fonts"
        private const val MPV_SCRIPTS_DIR = "scripts"
        private const val MPV_SCRIPTS_OPTS_DIR = "script-opts"
        private const val MPV_SHADERS_DIR = "shaders"
    }

    internal fun open(intent: Intent) {
        val channel = TvChannelRef(intent.getLongExtra("source", 0), intent.getStringExtra("channel").orEmpty())
        if (nyanime.tv.api.TvRules.valid(channel)) openLive(channel) else select { intent }
    }

    internal fun requestAudioFocus(): Boolean = audioFocusRequest?.let {
        AudioManagerCompat.requestAudioFocus(audioManager, it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } == true

    internal fun openLive(reference: TvChannelRef) {
        if (!available || !nyanime.tv.api.TvRules.valid(reference)) return
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        liveSelectionJob?.cancel()
        preparingSelection.value = true
        publishSnapshot()
        liveSelectionJob = lifecycleScope.launch {
            try {
                val services = Injekt.get<eu.kanade.tachiyomi.data.tv.TvServices>()
                services.initialized.first { it }
                val prepared = services.repository.resolve(reference)
                currentCoroutineContext().ensureActive()
                if (!available || generation != selectionGeneration) return@launch
                if (viewModel.currentVideo.value ==
                    null
                ) {
                    kotlinx.coroutines.withTimeout(10_000) { player.surfaceReady.first { it } }
                }
                captureReplacement()
                currentCoroutineContext().ensureActive()
                if (!available || generation != selectionGeneration) return@launch
                viewModel.endHoldSpeed()
                viewModel.playbackLoad.begin()
                if (live.value == null) {
                    viewModel.saveCurrentEpisodeWatchingProgress()
                    fileLoadedJob?.cancel()
                    videoLoadJob?.cancel()
                    viewModel.cancelHosterVideoLinksJob()
                    viewModel.prepareMediaChange(null)
                    viewModel.watchManager.detach(this@PlayerSession)
                    resetAnime4KRuntime()
                    val runtime = LivePlayback(this@PlayerSession, lifecycleScope)
                    live.value = runtime
                    runtime.start(reference, prepared)
                } else {
                    live.value?.select(reference, prepared)
                }
                publishSnapshot()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (generation == selectionGeneration) showToast(getString(R.string.tv_player_unavailable))
            } finally {
                if (generation == selectionGeneration) {
                    preparingSelection.value = false
                    publishSnapshot()
                }
            }
        }
    }

    private fun stopLive() {
        val runtime = live.value ?: return
        runtime.close()
        live.value = null
        player.restoreProperties()
        MPVLib.setPropertyBoolean("keep-open", true)
        viewModel.bindWatchPlayer()
    }

    internal fun playRecommended(anime: tachiyomi.domain.entries.anime.model.Anime) {
        if (viewModel.watchTogether.active && !viewModel.watchTogether.state.value.host) {
            showToast(getString(R.string.room_host_selects_episode))
            return
        }
        select { eu.kanade.tachiyomi.ui.player.session.PlaybackRecommendations.request(this, anime) }
    }

    private fun select(request: suspend () -> Intent) {
        if (!nativeReady || closing) return
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        liveSelectionJob?.cancel()
        preparingSelection.value = true
        publishSnapshot()
        selectionJob = lifecycleScope.launch {
            try {
                val intent = request()
                val animeId = intent.getLongExtra("animeId", -1)
                val episodeId = intent.getLongExtra("episodeId", -1)
                require(animeId >= 0 && episodeId >= 0)
                val selection = viewModel.prepareSelection(
                    animeId,
                    episodeId,
                    intent.getStringExtra("hostList").orEmpty(),
                    intent.getIntExtra("hostIndex", -1),
                    intent.getIntExtra("vidIndex", -1),
                )
                currentCoroutineContext().ensureActive()
                if (closing || generation != selectionGeneration) return@launch
                if (viewModel.currentVideo.value == null) {
                    kotlinx.coroutines.withTimeout(10_000) { player.surfaceReady.first { it } }
                }
                captureReplacement()
                currentCoroutineContext().ensureActive()
                if (!available || generation != selectionGeneration) return@launch
                viewModel.endHoldSpeed()
                stopLive()
                setIntent(intent)
                sharedStart = readSharedStart(intent)
                viewModel.saveCurrentEpisodeWatchingProgress()
                fileLoadedJob?.cancel()
                viewModel.cancelHosterVideoLinksJob()
                viewModel.prepareMediaChange(episodeId)
                viewModel.resetState()
                viewModel.commitSelection(selection)
                advancedPlayerPreferences.setAnime4kUltraActive(false)
                advancedPlayerPreferences.beginAnime4kSession()
                NotificationReceiver.dismissNotification(
                    this@PlayerSession,
                    animeId.hashCode(),
                    Notifications.ID_NEW_EPISODES,
                )
                setVideo(selection.video)
                publishSnapshot()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (generation == selectionGeneration) {
                    setInitialEpisodeError(error)
                }
            } finally {
                if (generation == selectionGeneration) {
                    preparingSelection.value = false
                    publishSnapshot()
                }
            }
        }
    }

    internal suspend fun start(initialIntent: Intent) {
        setIntent(initialIntent)
        if (!PlayerLifecycle.acquire(this, ::close) { closing || isDestroyed }) return
        nativeOwned = true
        eu.kanade.tachiyomi.data.rin.RinSessions.enterPlayback(this)
        UltraPlaybackGuard.enterPlayer()
        DownloadPlaybackGate.enter()
        try {
            setupPlayerMPV()
            nativeReady = true
            setupPlayerAudio()
            headphoneCinema.start()
            setupMediaSession()
            castController.state.distinctUntilChangedBy { it.active || it.connecting }.onEach { cast ->
                if (cast.active || cast.connecting) {
                    viewModel.remoteProgressOwned = true
                    mediaSession?.isActive = false
                    player.paused = true
                    setKeepScreenOn(false)
                }
                updateHostPresentation()
            }.launchIn(lifecycleScope)
            viewModel.eventFlow.onEach { event ->
                when (event) {
                    is PlayerViewModel.Event.SavedImage -> onSaveImageResult(event.result)
                    is PlayerViewModel.Event.ShareImage -> onShareImageResult(event.uri, event.seconds)
                    is PlayerViewModel.Event.SetArtResult -> onSetAsArtResult(event.result, event.artType)
                }
            }.launchIn(lifecycleScope)
            viewModel.watchTogether.state.distinctUntilChangedBy { it.active }.onEach {
                if (it.active) viewModel.endHoldSpeed()
                updateAnime4KRoomRestriction()
            }.launchIn(lifecycleScope)
            val initialChannel =
                TvChannelRef(initialIntent.getLongExtra("source", 0), initialIntent.getStringExtra("channel").orEmpty())
            if (!nyanime.tv.api.TvRules.valid(initialChannel)) viewModel.bindWatchPlayer()
            open(intent)
            updateHostPresentation()
            publishSnapshot()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            close()
            throw cancel
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) { "Native playback session initialization failed" }
            showToast(getString(R.string.playback_start_failed))
            close()
        }
    }

    override fun close() {
        if (closing || isDestroyed) return
        closing = true
        host.get()?.let(::detachHost)
        eu.kanade.tachiyomi.ui.player.session.PlaybackSessions.clear(this)
        if (nativeReady && !player.isExiting) player.paused = true
        selectionJob?.cancel()
        liveSelectionJob?.cancel()
        windowTransitionJob?.cancel()
        replacementFrame.value = null
        windowFrame.value = null
        renderer.clear()
        live.value?.close()
        live.value = null
        videoLoadJob?.cancel()
        fileLoadedJob?.cancel()
        if (nativeReady && model.isInitialized()) {
            viewModel.endHoldSpeed()
            if (!snapshot.value.live) viewModel.saveCurrentEpisodeWatchingProgress()
            viewModel.detachDevicePlayback()
            viewModel.watchManager.detach(this)
            viewModel.playbackLoad.cancel()
            headphoneCinema.close()
        }
        ownerJob.cancel()
        modelStore.clear()
        handler.removeCallbacksAndMessages(null)
        player.isExiting = true
        httpServer?.stop()
        httpServer = null
        audioFocusRequest?.let { AudioManagerCompat.abandonAudioFocusRequest(audioManager, it) }
        audioFocusRequest = null
        pipReceiver?.let { unregisterReceiver(it) }
        pipReceiver = null
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        if (noisyReceiver.initialized) {
            unregisterReceiver(noisyReceiver)
            noisyReceiver.initialized = false
        }
        // Wait for already-entered native track/load calls before destroying the process-wide engine.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            ownerJob.join()
            trackLoadingMutex.withLock {
                if (nativeInitialized) {
                    MPVLib.removeLogObserver(playerObserver)
                    MPVLib.removeObserver(playerObserver)
                    player.destroy()
                    nativeInitialized = false
                }
            }
            nativeReady = false
            isDestroyed = true
            host.clear()
            if (nativeOwned) {
                nativeOwned = false
                UltraPlaybackGuard.leavePlayer()
                DownloadPlaybackGate.leave()
                PlayerLifecycle.release(this@PlayerSession)
                eu.kanade.tachiyomi.data.rin.RinSessions.leavePlayback(this@PlayerSession)
            }
            onClosed(this@PlayerSession)
        }
    }

    private fun executeMPVCommand(commands: Array<String>) {
        if (!player.isExiting) {
            MPVLib.command(commands)
        }
    }

    private fun currentAnime4KEpisodeIds(): Pair<Long, Long>? {
        val animeId = viewModel.currentAnime.value?.id ?: return null
        val episodeId = viewModel.currentEpisode.value?.id ?: return null
        return animeId to episodeId
    }

    private fun anime4KRuntimeCalibrationKey(mediaInfo: Anime4KMediaInfo): String {
        return Anime4K.calibrationKey(
            mediaInfo = mediaInfo,
            renderer = getAnime4KPropertyString("gpu-renderer")
                ?: getAnime4KPropertyString("gpu-context")
                ?: Build.HARDWARE,
            api = getAnime4KPropertyString("gpu-api") ?: "unknown",
            driver = getAnime4KPropertyString("gpu-driver") ?: Build.VERSION.SDK_INT.toString(),
            hwdec = getAnime4KPropertyString("hwdec-current"),
            calibrationRevision = "${Anime4K.CALIBRATION_REVISION}:${BuildConfig.VERSION_CODE}",
        )
    }

    internal fun onAnime4KShaderError(message: String? = null) {
        runOnUiThread {
            if (player.isExiting || isAnime4KUnavailable()) return@runOnUiThread

            val now = SystemClock.elapsedRealtime()

            val currentSelection = currentAnime4KSelection()
            if (currentSelection.profile == Anime4KProfile.Off || currentSelection.mode == Anime4KMode.Off) {
                return@runOnUiThread
            }

            if (currentSelection.profile == Anime4KProfile.Smart) {
                val controller = anime4kSmartController
                if (controller == null) {
                    applyAnime4KSelection(
                        profile = Anime4KProfile.Smart,
                        mode = Anime4KMode.Off,
                        resetSmartController = false,
                        persistEpisodeProfile = false,
                        fallbackToOffOnFailure = false,
                    )
                } else {
                    val adjustment = controller.onShaderError(
                        nowMillis = now,
                        shaderName = message?.let(Anime4K::shaderNameInError),
                    )
                    adjustment?.let { applySmartAdjustment(it, now) }
                }
                return@runOnUiThread
            }

            applyAnime4KSelection(Anime4KProfile.Off, Anime4KMode.Off)
            toast(AYMR.strings.pref_anime4k_fallback)
        }
    }

    internal fun toggleAnime4KSmart() {
        if (isAnime4KUnavailable()) return
        val mediaInfo = resolveAnime4KMediaInfo()
        val current = currentAnime4KSelection()
        if (current.profile == Anime4KProfile.Smart && anime4kSmartController?.isSuspended == true) {
            val now = SystemClock.elapsedRealtime()
            val controller = anime4kSmartController ?: return
            controller.resume(now)
            advancedPlayerPreferences.setAnime4kActiveSelection(
                Anime4KSelection(Anime4KProfile.Smart, Anime4KMode.Off),
            )
            advancedPlayerPreferences.anime4kProfile().set(Anime4KProfile.Smart)
            advancedPlayerPreferences.anime4kMode().set(Anime4KMode.Off)
            currentAnime4KEpisodeIds()?.let { (animeId, episodeId) ->
                advancedPlayerPreferences.saveAnime4kEpisodeProfile(
                    animeId,
                    episodeId,
                    Anime4KEpisodeProfile(Anime4KProfile.Smart),
                )
            }
            if (applySmartMode(controller, controller.currentMode, now)) {
                applySmartAnime4KForLoadedMedia()
            }
            return
        }

        val target = Anime4KSelectionRules.toggleSmart(
            current,
            mediaInfo?.let(Anime4K::resolveSmartMode) ?: Anime4KMode.ModeA,
        )
        if (target.profile == Anime4KProfile.Smart) {
            if (applyAnime4KSelection(
                    profile = Anime4KProfile.Smart,
                    mode = Anime4KMode.Off,
                    resetSmartController = true,
                    persistEpisodeProfile = true,
                    fallbackToOffOnFailure = false,
                )
            ) {
                applySmartAnime4KForLoadedMedia()
            }
        } else {
            applyAnime4KSelection(Anime4KProfile.Off, Anime4KMode.Off)
        }
    }

    internal fun selectAnime4KCustom(mode: Anime4KMode) {
        if (isAnime4KUnavailable()) return
        val target = Anime4KSelectionRules.selectCustom(currentAnime4KSelection(), mode)
        applyAnime4KSelection(target.profile, target.mode)
    }

    private fun isAnime4KUnavailable() =
        viewModel.watchTogether.active || UltraFiles.isUltra(viewModel.currentVideo.value)

    private fun currentAnime4KSelection(): Anime4KSelection =
        advancedPlayerPreferences.anime4kActiveSelection().value

    private fun applyAnime4KSelection(
        profile: Anime4KProfile,
        mode: Anime4KMode,
        resetSmartController: Boolean = true,
        persistEpisodeProfile: Boolean = true,
        fallbackToOffOnFailure: Boolean = true,
    ): Boolean {
        if (isAnime4KUnavailable()) return false
        if (anime4kShaderPipeline.apply(mode)) {
            val selection = Anime4KSelection(profile, mode)
            advancedPlayerPreferences.setAnime4kActiveSelection(selection)
            advancedPlayerPreferences.anime4kProfile().set(profile)
            advancedPlayerPreferences.anime4kMode().set(mode)
            if (persistEpisodeProfile) {
                currentAnime4KEpisodeIds()?.let { (animeId, episodeId) ->
                    advancedPlayerPreferences.saveAnime4kEpisodeProfile(
                        animeId,
                        episodeId,
                        Anime4KEpisodeProfile(
                            profile = profile,
                            customMode = if (profile == Anime4KProfile.Custom) mode else Anime4KMode.Off,
                        ),
                    )
                }
            }
            advancedPlayerPreferences.setAnime4kDiagnostics(
                Anime4KSmartDiagnostics(
                    profile = profile,
                    mode = mode,
                ),
            )
            if (profile != Anime4KProfile.Smart) {
                resetAnime4KRuntime()
            } else if (resetSmartController) {
                resetAnime4KRuntime()
            } else {
                anime4kSmartNeedsMediaResolution = anime4kSmartController == null
                startAnime4KTelemetry()
            }
            return true
        }

        if (!fallbackToOffOnFailure) return false

        if (!anime4kShaderPipeline.apply(Anime4KMode.Off)) {
            toast(AYMR.strings.pref_anime4k_fallback)
            return false
        }
        advancedPlayerPreferences.setAnime4kActiveSelection(
            Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off),
        )
        advancedPlayerPreferences.anime4kProfile().set(Anime4KProfile.Off)
        advancedPlayerPreferences.anime4kMode().set(Anime4KMode.Off)
        if (persistEpisodeProfile) {
            currentAnime4KEpisodeIds()?.let { (animeId, episodeId) ->
                advancedPlayerPreferences.saveAnime4kEpisodeProfile(
                    animeId,
                    episodeId,
                    Anime4KEpisodeProfile(Anime4KProfile.Off),
                )
            }
        }
        resetAnime4KRuntime()
        toast(AYMR.strings.pref_anime4k_fallback)
        return false
    }

    private fun updateAnime4KRoomRestriction() {
        if (isFinishing || isDestroyed || player.isExiting) return
        val active = viewModel.watchTogether.active
        if (anime4kAppliedRoomRestriction == active) return
        anime4kAppliedRoomRestriction = active
        anime4kRoomRestorePending = !active
        advancedPlayerPreferences.setAnime4kRoomActive(active)
        resetAnime4KRuntime()
        if (active) {
            anime4kShaderPipeline.apply(Anime4KMode.Off)
        } else if (viewModel.playbackLoadState.value.started &&
            !viewModel.playbackLoadState.value.opening &&
            !viewModel.isLoadingEpisode.value
        ) {
            loadAnime4KForCurrentEpisode()
        }
    }

    private fun applyAnime4K() {
        val roomActive = viewModel.watchTogether.active
        anime4kAppliedRoomRestriction = roomActive
        advancedPlayerPreferences.setAnime4kRoomActive(roomActive)
        advancedPlayerPreferences.setAnime4kUltraActive(false)
        advancedPlayerPreferences.beginAnime4kSession()
        // The episode profile is loaded after MPV has identified the current episode. Never
        // apply a previous episode's profile during MPV initialization.
        anime4kShaderPipeline.apply(Anime4KMode.Off)
        advancedPlayerPreferences.setAnime4kActiveSelection(
            Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off),
        )
        advancedPlayerPreferences.setAnime4kDiagnostics(Anime4KSmartDiagnostics())
        resetAnime4KRuntime()
    }

    private fun loadAnime4KForCurrentEpisode() {
        advancedPlayerPreferences.setAnime4kRoomActive(viewModel.watchTogether.active)
        advancedPlayerPreferences.setAnime4kUltraActive(UltraFiles.isUltra(viewModel.currentVideo.value))
        resetAnime4KRuntime()

        val ids = currentAnime4KEpisodeIds() ?: run {
            anime4kShaderPipeline.apply(Anime4KMode.Off)
            advancedPlayerPreferences.setAnime4kActiveSelection(
                Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off),
            )
            advancedPlayerPreferences.setAnime4kDiagnostics(Anime4KSmartDiagnostics())
            return
        }
        val profile = advancedPlayerPreferences.loadAnime4kEpisodeProfile(ids.first, ids.second)
        anime4kRoomRestorePending = false
        if (isAnime4KUnavailable()) {
            anime4kShaderPipeline.apply(Anime4KMode.Off)
            return
        }
        when (profile.profile) {
            Anime4KProfile.Off -> applyAnime4KSelection(
                Anime4KProfile.Off,
                Anime4KMode.Off,
                persistEpisodeProfile = false,
            )
            Anime4KProfile.Smart -> {
                if (!applyAnime4KSelection(
                        Anime4KProfile.Smart,
                        Anime4KMode.Off,
                        persistEpisodeProfile = false,
                        fallbackToOffOnFailure = false,
                    )
                ) {
                    return
                }
                applySmartAnime4KForLoadedMedia()
            }
            Anime4KProfile.Maximum -> applyAnime4KSelection(
                Anime4KProfile.Maximum,
                Anime4KMode.ModeAPlusHq,
                persistEpisodeProfile = false,
            )
            Anime4KProfile.Custom -> applyAnime4KSelection(
                Anime4KProfile.Custom,
                profile.customMode,
                persistEpisodeProfile = false,
            )
        }
    }

    private fun requestAnime4KMediaRefresh() {
        if (isAnime4KUnavailable()) return
        if (currentAnime4KSelection().profile == Anime4KProfile.Smart) anime4kMediaRefresh.request()
    }

    private fun applySmartAnime4KForLoadedMedia() {
        if (isAnime4KUnavailable()) return
        if (currentAnime4KSelection().profile != Anime4KProfile.Smart) return

        val mediaInfo = resolveAnime4KMediaInfo() ?: run {
            scheduleAnime4KMediaResolutionRetry()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val controller = anime4kSmartController
        if (
            controller == null ||
            anime4kSmartNeedsMediaResolution
        ) {
            val calibrationKey = anime4KRuntimeCalibrationKey(mediaInfo)
            val calibration = advancedPlayerPreferences.anime4kCalibration(calibrationKey)
            val newController = Anime4KSmartController(mediaInfo, now, calibration)
            anime4kSmartController = newController
            anime4kSmartNeedsMediaResolution = false
            anime4kSmartResolutionRetryCount = 0
            anime4kCalibrationKey = calibrationKey
            anime4kCalibrationMediaInfo = mediaInfo
            anime4kLastSavedCalibration = calibration
            if (applySmartMode(newController, newController.currentMode, now)) {
                logcat(LogPriority.INFO) {
                    "Anime4K Smart started at ${newController.currentMode.name} for " +
                        "${mediaInfo.sourceWidth}x${mediaInfo.sourceHeight} -> " +
                        "${mediaInfo.targetWidth}x${mediaInfo.targetHeight} @ " +
                        "${mediaInfo.framesPerSecond}fps"
                }
            }
            publishAnime4KSmartState(newController)
            return
        }

        if (anime4kCalibrationMediaInfo?.let { Anime4K.sameWorkload(it, mediaInfo) } != true) {
            anime4kCalibrationKey = anime4KRuntimeCalibrationKey(mediaInfo)
            anime4kCalibrationMediaInfo = mediaInfo
            anime4kLastSavedCalibration = null
        }

        controller.updateMediaInfo(mediaInfo, now)?.let { adjustment ->
            applySmartAdjustment(adjustment, now)
        }
        publishAnime4KSmartState(controller)
        saveAnime4KCalibrationIfChanged(controller)
    }

    private fun applySmartMode(
        controller: Anime4KSmartController,
        mode: Anime4KMode,
        nowMillis: Long,
    ): Boolean {
        if (isAnime4KUnavailable()) return false
        var candidate = mode
        // Every failure removes a candidate. Even a broken installation terminates at Off.
        repeat(Anime4KMode.entries.size) {
            if (applyAnime4KSelection(
                    profile = Anime4KProfile.Smart,
                    mode = candidate,
                    resetSmartController = false,
                    persistEpisodeProfile = false,
                    fallbackToOffOnFailure = false,
                )
            ) {
                publishAnime4KSmartState(controller)
                saveAnime4KCalibrationIfChanged(controller)
                return candidate == mode
            }
            candidate = controller.onModeApplyFailure(nowMillis)?.mode ?: return forceSmartOff(controller)
        }
        return forceSmartOff(controller)
    }

    private fun applySmartAdjustment(
        adjustment: Anime4KSmartAdjustment,
        nowMillis: Long,
    ) {
        val controller = anime4kSmartController ?: return
        val previousMode = currentAnime4KSelection().mode
        if (adjustment.mode != previousMode) {
            applySmartMode(controller, adjustment.mode, nowMillis)
        }
        publishAnime4KSmartState(controller)
        saveAnime4KCalibrationIfChanged(controller)
        if (previousMode != adjustment.mode) {
            logcat(LogPriority.INFO) {
                "Anime4K Smart changed $previousMode to ${currentAnime4KSelection().mode.name}: ${adjustment.reason}"
            }
        }
    }

    private fun forceSmartOff(controller: Anime4KSmartController): Boolean {
        controller.suspend(SystemClock.elapsedRealtime())
        stopAnime4KTelemetry()
        if (!anime4kShaderPipeline.apply(Anime4KMode.Off)) {
            // MPV rejected even clearing our shaders; report the last applied selection honestly.
            advancedPlayerPreferences.setAnime4kDiagnostics(
                controller.diagnostics.copy(mode = currentAnime4KSelection().mode, reason = "shader update rejected"),
            )
            toast(AYMR.strings.pref_anime4k_fallback)
            return false
        }
        advancedPlayerPreferences.setAnime4kActiveSelection(
            Anime4KSelection(Anime4KProfile.Smart, Anime4KMode.Off),
        )
        advancedPlayerPreferences.anime4kProfile().set(Anime4KProfile.Smart)
        advancedPlayerPreferences.anime4kMode().set(Anime4KMode.Off)
        publishAnime4KSmartState(controller)
        saveAnime4KCalibrationIfChanged(controller)
        return false
    }

    private fun resetAnime4KRuntime() {
        anime4kMediaRefresh.reset()
        stopAnime4KTelemetry()
        anime4kSmartController = null
        anime4kSmartNeedsMediaResolution = true
        anime4kSmartResolutionRetryCount = 0
        anime4kSmartRetryPending = false
        anime4kMediaResolutionGeneration++
        anime4kCalibrationKey = null
        anime4kCalibrationMediaInfo = null
        anime4kLastSavedCalibration = null
    }

    private fun publishAnime4KSmartState(controller: Anime4KSmartController) {
        advancedPlayerPreferences.setAnime4kDiagnostics(
            controller.diagnostics.copy(mode = currentAnime4KSelection().mode),
        )
    }

    private fun saveAnime4KCalibrationIfChanged(controller: Anime4KSmartController) {
        if (!controller.hasStableMeasurement) return
        val key = anime4kCalibrationKey ?: return
        val calibration = controller.calibration
        if (calibration != anime4kLastSavedCalibration) {
            advancedPlayerPreferences.saveAnime4kCalibration(
                key,
                calibration.copy(updatedAtMillis = System.currentTimeMillis()),
            )
            anime4kLastSavedCalibration = calibration
        }
    }

    /** MPV can publish FILE_LOADED before video-params is available. Retry only briefly. */
    private fun scheduleAnime4KMediaResolutionRetry() {
        if (
            anime4kSmartRetryPending ||
            currentAnime4KSelection().profile != Anime4KProfile.Smart ||
            !anime4kSmartNeedsMediaResolution ||
            anime4kSmartResolutionRetryCount >= 4
        ) {
            return
        }

        val generation = anime4kMediaResolutionGeneration
        anime4kSmartResolutionRetryCount++
        anime4kSmartRetryPending = true
        handler.postDelayed({
            if (generation != anime4kMediaResolutionGeneration) return@postDelayed
            anime4kSmartRetryPending = false
            if (isFinishing || isDestroyed) return@postDelayed
            if (currentAnime4KSelection().profile == Anime4KProfile.Smart) {
                applySmartAnime4KForLoadedMedia()
            }
        }, anime4kSmartResolutionRetryCount * 250L)
    }

    private fun resolveAnime4KMediaInfo(): Anime4KMediaInfo? {
        // video-out-params describes the shader input after video filters, not the window.
        val parameters = if ((getAnime4KPropertyInt("video-out-params/w") ?: 0) >
            0
        ) {
            "video-out-params"
        } else {
            "video-params"
        }
        val geometry = Anime4KGeometry.resolve(
            sourceWidth = getAnime4KPropertyInt("$parameters/w") ?: return null,
            sourceHeight = getAnime4KPropertyInt("$parameters/h") ?: return null,
            rotation = getAnime4KPropertyInt("$parameters/rotate") ?: 0,
            displayAspect = getAnime4KPropertyDouble("$parameters/aspect"),
            osdWidth = getAnime4KPropertyInt("osd-dimensions/w"),
            osdHeight = getAnime4KPropertyInt("osd-dimensions/h"),
            marginLeft = getAnime4KPropertyInt("osd-dimensions/ml"),
            marginRight = getAnime4KPropertyInt("osd-dimensions/mr"),
            marginTop = getAnime4KPropertyInt("osd-dimensions/mt"),
            marginBottom = getAnime4KPropertyInt("osd-dimensions/mb"),
            surfaceWidth = player.width,
            surfaceHeight = player.height,
        ) ?: return null
        val containerFps = getAnime4KPropertyDouble("container-fps")
        val displayRefreshRate = getAnime4KPropertyDouble("display-fps")
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: getAnime4KPropertyDouble("estimated-display-fps")?.takeIf { it.isFinite() && it > 0.0 }

        return Anime4KMediaInfo(
            sourceWidth = geometry.sourceWidth,
            sourceHeight = geometry.sourceHeight,
            targetWidth = geometry.targetWidth,
            targetHeight = geometry.targetHeight,
            framesPerSecond = Anime4K.resolveFramesPerSecond(null, containerFps),
            displayRefreshRate = displayRefreshRate,
            frameRateKnown = containerFps?.let { it.isFinite() && it > 0.0 } == true,
            playbackSpeed = getAnime4KPropertyDouble("speed")
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: 1.0,
        )
    }

    private fun sampleAnime4KSmart() {
        if (isAnime4KUnavailable()) return
        if (currentAnime4KSelection().profile != Anime4KProfile.Smart) return
        val controller = anime4kSmartController ?: return
        if (controller.isSuspended) return
        val now = SystemClock.elapsedRealtime()
        if (anime4kTelemetrySession?.shouldSampleFallback(now) != true) return
        // Older/custom MPV builds may not load Lua. Drop counters still permit safe degradation,
        // but no measured GPU capacity is invented when render-pass timings are unavailable.
        acceptAnime4KSample(
            Anime4KPerformanceSample(
                timestampMillis = now,
                outputDroppedFrames = getAnime4KPropertyString("frame-drop-count")?.toLongOrNull(),
                decoderDroppedFrames = getAnime4KPropertyString("decoder-frame-drop-count")?.toLongOrNull(),
                delayedFrames = getAnime4KPropertyString("vo-delayed-frame-count")?.toLongOrNull(),
                renderTimeMillis = null,
                mistimedFrames = if (getAnime4KPropertyString("display-sync-active") == "yes") {
                    getAnime4KPropertyString("mistimed-frame-count")?.toLongOrNull()
                } else {
                    null
                },
                playing = player.paused == false &&
                    getAnime4KPropertyString("paused-for-cache") == "no" &&
                    getAnime4KPropertyString("seeking") == "no",
            ),
        )
    }

    private fun startAnime4KTelemetry() {
        if (isAnime4KUnavailable()) return
        if (anime4kSmartController?.isSuspended != false) {
            stopAnime4KTelemetry()
            return
        }
        val session = Anime4KTelemetrySession((++anime4kTelemetryGeneration).toString(), SystemClock.elapsedRealtime())
        anime4kTelemetrySession = session
        runCatching {
            MPVLib.command(
                arrayOf("script-message-to", Anime4KTelemetry.SCRIPT_NAME, "set-active", "yes", session.token),
            )
        }
    }

    private fun stopAnime4KTelemetry() {
        if (anime4kTelemetrySession == null) return
        anime4kTelemetrySession = null
        runCatching {
            MPVLib.command(arrayOf("script-message-to", Anime4KTelemetry.SCRIPT_NAME, "set-active", "no", ""))
        }
    }

    private fun acceptAnime4KTelemetry(value: String) {
        if (isAnime4KUnavailable()) return
        if (currentAnime4KSelection().profile != Anime4KProfile.Smart) return
        val telemetry = Anime4KTelemetry.parse(value) ?: return
        val sample = anime4kTelemetrySession?.accept(telemetry, SystemClock.elapsedRealtime()) ?: return
        acceptAnime4KSample(sample)
    }

    private fun acceptAnime4KSample(sample: Anime4KPerformanceSample) {
        if (isAnime4KUnavailable()) return
        val controller = anime4kSmartController ?: return
        controller.onSample(sample)?.let { adjustment ->
            applySmartAdjustment(adjustment, sample.timestampMillis)
        }
        publishAnime4KSmartState(controller)
        saveAnime4KCalibrationIfChanged(controller)
    }

    private fun getAnime4KPropertyInt(property: String): Int? =
        runCatching { MPVLib.getPropertyInt(property) }.getOrNull()

    private fun getAnime4KPropertyDouble(property: String): Double? =
        runCatching { MPVLib.getPropertyDouble(property) }.getOrNull()

    private fun getAnime4KPropertyString(property: String): String? =
        runCatching { MPVLib.getPropertyString(property) }.getOrNull()

    private fun UniFile.writeText(text: String) {
        this.openOutputStream().use {
            it.write(text.toByteArray())
        }
    }

    private suspend fun setupPlayerMPV() {
        val logLevel = if (networkPreferences.verboseLogging().get()) "info" else "warn"
        val mpvDir = withContext(Dispatchers.IO) {
            val directory = UniFile.fromFile(applicationContext.filesDir)!!.createDirectory(MPV_DIR)!!
            val mpvConfFile = directory.createFile("mpv.conf")!!
            advancedPlayerPreferences.mpvConf().get().let { mpvConfFile.writeText(it) }
            val mpvInputFile = directory.createFile("input.conf")!!
            advancedPlayerPreferences.mpvInput().get().let { mpvInputFile.writeText(it) }
            copyUserFiles(directory)
            anime4kShaderPipeline.copyAssets()
            copyAssets(directory)
            directory
        }
        currentCoroutineContext().ensureActive()
        check(!closing)

        player.initialize(
            configDir = mpvDir.filePath!!,
            cacheDir = applicationContext.cacheDir.path,
            logLvl = logLevel,
        )
        nativeInitialized = true
        MPVLib.setOptionString("sub-ass-force-margins", "yes")
        MPVLib.setOptionString("sub-use-margins", "yes")
        MPVLib.addLogObserver(playerObserver)
        MPVLib.addObserver(playerObserver)
        applyAnime4K()
        copyFontsDirectory(mpvDir)
    }

    private fun copyUserFiles(mpvDir: UniFile) {
        // First, delete all present scripts
        val scriptsDir = { mpvDir.createDirectory(MPV_SCRIPTS_DIR) }
        val scriptOptsDir = { mpvDir.createDirectory(MPV_SCRIPTS_OPTS_DIR) }
        val shadersDir = { mpvDir.createDirectory(MPV_SHADERS_DIR) }

        scriptsDir()?.delete()
        scriptOptsDir()?.delete()
        shadersDir()?.delete()

        // Then, copy the user files from the Aniyomi directory
        if (advancedPlayerPreferences.mpvUserFiles().get()) {
            storageManager.getScriptsDirectory()?.listFiles()?.forEach { file ->
                val outFile = scriptsDir()?.createFile(file.name)
                outFile?.let {
                    file.openInputStream().copyTo(it.openOutputStream())
                }
            }
            storageManager.getScriptOptsDirectory()?.listFiles()?.forEach { file ->
                val outFile = scriptOptsDir()?.createFile(file.name)
                outFile?.let {
                    file.openInputStream().copyTo(it.openOutputStream())
                }
            }
            storageManager.getShadersDirectory()?.listFiles()?.forEach { file ->
                val outFile = shadersDir()?.createFile(file.name)
                outFile?.let {
                    file.openInputStream().copyTo(it.openOutputStream())
                }
            }
        }

        // Bundled bridges are refreshed on every initialization, independently of user scripts.
        for (filename in listOf("aniyomi.lua", "${Anime4KTelemetry.SCRIPT_NAME}.lua")) {
            scriptsDir()?.createFile(filename)?.openOutputStream()?.use { output ->
                assets.open(filename).use { input -> input.copyTo(output) }
            }
        }
    }

    private fun copyAssets(mpvDir: UniFile) {
        val assetManager = this.assets
        val files = arrayOf("subfont.ttf", "cacert.pem")
        for (filename in files) {
            var ins: InputStream? = null
            var out: OutputStream? = null
            try {
                ins = assetManager.open(filename, AssetManager.ACCESS_STREAMING)
                val outFile = mpvDir.createFile(filename)!!
                // Note that .available() officially returns an *estimated* number of bytes available
                // this is only true for generic streams, asset streams return the full file size
                if (outFile.length() == ins.available().toLong()) {
                    logcat(LogPriority.VERBOSE) { "Skipping copy of asset file (exists same size): $filename" }
                    continue
                }
                out = outFile.openOutputStream()
                ins.copyTo(out)
                logcat(LogPriority.WARN) { "Copied asset file: $filename" }
            } catch (e: IOException) {
                logcat(LogPriority.ERROR, e) { "Failed to copy asset file: $filename" }
            } finally {
                ins?.close()
                out?.close()
            }
        }
    }

    private fun copyFontsDirectory(mpvDir: UniFile) {
        // TODO: I think this is a bad hack.
        //  We need to find a way to let MPV directly access our fonts directory.
        lifecycleScope.launchIO {
            val fontsDirectory = mpvDir.createDirectory(MPV_FONTS_DIR)!!

            storageManager.getFontsDirectory()?.listFiles()?.forEach { font ->
                val outFile = fontsDirectory.createFile(font.name)
                outFile?.let {
                    font.openInputStream().use { input -> it.openOutputStream().use(input::copyTo) }
                }
            }

            withContext(Dispatchers.Main.immediate) {
                if (available) {
                    MPVLib.setPropertyString("sub-fonts-dir", fontsDirectory.filePath!!)
                    MPVLib.setPropertyString("osd-fonts-dir", fontsDirectory.filePath!!)
                }
            }
        }
    }

    fun setupCustomButtons(buttons: List<CustomButton>) {
        lifecycleScope.launchIO {
            val scriptsDir = {
                UniFile.fromFile(applicationContext.filesDir)
                    ?.createDirectory(MPV_DIR)
                    ?.createDirectory(MPV_SCRIPTS_DIR)
            }

            val primaryButtonId = viewModel.primaryButton.value?.id ?: 0L

            val customButtonsContent = buildString {
                append(
                    """
                        local lua_modules = mp.find_config_file('scripts')
                        if lua_modules then
                            package.path = package.path .. ';' .. lua_modules .. '/?.lua;' .. lua_modules .. '/?/init.lua;' .. '${scriptsDir()!!.filePath}' .. '/?.lua'
                        end
                        local aniyomi = require 'aniyomi'
                    """.trimIndent(),
                )

                buttons.forEach { button ->
                    append(
                        """
                            ${button.getButtonOnStartup(primaryButtonId)}
                            function button${button.id}()
                                ${button.getButtonContent(primaryButtonId)}
                            end
                            mp.register_script_message('call_button_${button.id}', button${button.id})
                            function button${button.id}long()
                                ${button.getButtonLongPressContent(primaryButtonId)}
                            end
                            mp.register_script_message('call_button_${button.id}_long', button${button.id}long)
                        """.trimIndent(),
                    )
                }
            }

            val file = scriptsDir()?.createFile("custombuttons.lua")
            file?.openOutputStream()?.bufferedWriter()?.use {
                it.write(customButtonsContent)
            }

            file?.let {
                withContext(Dispatchers.Main.immediate) {
                    if (available) MPVLib.command(arrayOf("load-script", it.filePath))
                }
            }
        }
    }

    private fun setupPlayerAudio() {
        with(audioPreferences) {
            applyAudioChannels(audioChannels().get())

            val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN).also {
                it.setAudioAttributes(
                    AudioAttributesCompat.Builder().setUsage(AudioAttributesCompat.USAGE_MEDIA)
                        .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC).build(),
                )
                it.setOnAudioFocusChangeListener(audioFocusChangeListener)
            }.build()
            AudioManagerCompat.requestAudioFocus(audioManager, request).let {
                if (it == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return@let
                audioFocusRequest = request
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener {
        if (player.isExiting || isFinishing || isDestroyed) return@OnAudioFocusChangeListener
        live.value?.let { runtime ->
            if (it == AudioManager.AUDIOFOCUS_LOSS ||
                it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                runtime.session.pause(true)
            }
            return@OnAudioFocusChangeListener
        }
        when (it) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                val oldRestore = restoreAudioFocus
                val wasPlayerPaused = player.paused ?: false
                viewModel.watchTogether.hold()
                viewModel.pause()
                restoreAudioFocus = {
                    oldRestore()
                    if (!wasPlayerPaused) viewModel.unpause()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                MPVLib.command(arrayOf("multiply", "volume", "0.5"))
                restoreAudioFocus = {
                    MPVLib.command(arrayOf("multiply", "volume", "2"))
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                restoreAudioFocus()
                restoreAudioFocus = {}
            }

            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                logcat(LogPriority.DEBUG) { "didn't get audio focus" }
            }
        }
    }

    internal fun hostResumed() {
        if (!nativeReady || closing) return
        updateAnime4KRoomRestriction()
        updateHostPresentation()
    }

    internal fun hostConfigurationChanged() {
        if (!nativeReady || closing) return
        updateHostPresentation()
        if (pip.value) viewModel.hideControls()
        publishSnapshot()
    }

    fun showToast(message: String) {
        runOnUiThread { toast(message) }
    }

    // A bunch of observers

    internal fun onObserverEvent(property: String, value: Long) {
        if (player.isExiting || live.value != null) return
        when (property) {
            "time-pos" -> {
                viewModel.updatePlayBackPos(value.toFloat())
                viewModel.setChapter(value.toFloat())
                sampleAnime4KSmart()
            }
            "demuxer-cache-time" -> viewModel.updateReadAhead(value = value)
            "volume" -> viewModel.setMPVVolume(value.toInt())
            "volume-max" -> viewModel.volumeBoostCap = value.toInt() - 100
            // "chapter" -> viewModel.updateChapter(value)
            "duration" -> viewModel.duration.update { value.toFloat() }
            "user-data/current-anime/intro-length" -> viewModel.setAnimeSkipIntroLength(value)
        }
    }

    internal fun onObserverEvent(property: String) {
        if (player.isExiting || live.value != null) return
        when (property) {
            "chapter-list" -> {
                viewModel.loadChapters()
                viewModel.updateChapter(0)
            }
            "track-list" -> viewModel.loadTracks()
            "osd-dimensions" -> requestAnime4KMediaRefresh()
        }
    }

    internal fun onObserverEvent(property: String, value: Boolean) {
        if (player.isExiting) return
        if (live.value != null) {
            if (property == "pause") setKeepScreenOn(!value)
            return
        }
        if (property in listOf("pause", "paused-for-cache", "seeking")) {
            anime4kSmartController?.resetTelemetry(SystemClock.elapsedRealtime())
        }
        when (property) {
            "pause" -> {
                if (value && player.paused == true) {
                    if (viewModel.watchTogether.active) viewModel.onNativePause(true) else viewModel.pause()
                    setKeepScreenOn(false)
                } else if (!value && player.paused == false) {
                    if (viewModel.watchTogether.active) viewModel.onNativePause(false) else viewModel.unpause()
                    if (!viewModel.paused.value) setKeepScreenOn(true)
                }

                runCatching {
                    setPictureInPictureParams(createPipParams())
                }
            }

            "paused-for-cache" -> {
                logcat(LogPriority.INFO) {
                    "Playback buffering=$value positionSeconds=${viewModel.pos.value}"
                }
                viewModel.playbackLoad.buffering(value)
                viewModel.isLoading.value = viewModel.playbackLoadState.value.loading
            }

            "seeking" -> {
                viewModel.playbackLoad.seeking(value)
                viewModel.isLoading.value = viewModel.playbackLoadState.value.loading
            }

            "eof-reached" -> {
                endFile(value)
            }
        }
    }

    val trackId: (String) -> Int? = {
        when (it) {
            "auto" -> null
            "no" -> -1
            else -> it.toInt()
        }
    }

    internal fun onObserverEvent(property: String, value: String) {
        if (player.isExiting || live.value != null) return
        if (property == Anime4KTelemetry.PROPERTY) {
            acceptAnime4KTelemetry(value)
            return
        }
        when (property.substringBeforeLast("/")) {
            "aid" -> trackId(value)?.let { viewModel.updateAudio(it) }
            "sid" -> trackId(value)?.let { viewModel.updateSubtitle(it, viewModel.selectedSubtitles.value.second) }
            "secondary-sid" -> trackId(value)?.let {
                viewModel.updateSubtitle(viewModel.selectedSubtitles.value.first, it)
            }
            "hwdec", "hwdec-current" -> {
                viewModel.getDecoder()
                anime4kCalibrationMediaInfo = null
                anime4kSmartController?.invalidateCalibration(SystemClock.elapsedRealtime())
                applySmartAnime4KForLoadedMedia()
            }
            "user-data/aniyomi" -> viewModel.handleLuaInvocation(property, value)
        }
    }

    @SuppressLint("NewApi")
    internal fun onObserverEvent(property: String, value: Double) {
        if (player.isExiting || live.value != null) return
        when (property) {
            "speed" -> {
                val baseSpeed = if (viewModel.watchTogether.isManagedSpeed(value)) {
                    viewModel.watchTogether.preferredSpeed().also {
                        if (kotlin.math.abs(viewModel.playbackSpeed.value - it) < 0.0001) return
                    }
                } else {
                    value
                }
                viewModel.playbackSpeed.update { baseSpeed.toFloat() }
                anime4kSmartController?.resetTelemetry(SystemClock.elapsedRealtime())
                requestAnime4KMediaRefresh()
            }
            "video-params/aspect" -> {
                player.getVideoOutAspect()?.takeIf { it.isFinite() && it in 0.1..10.0 }?.let { videoAspect = it }
                if (isPipSupportedAndEnabled) setPictureInPictureParams(createPipParams())
            }
            "container-fps",
            "display-fps",
            "estimated-display-fps",
            -> requestAnime4KMediaRefresh()
        }
    }

    internal fun event(eventId: Int) {
        if (player.isExiting) return
        if (live.value != null) {
            if (eventId == MPVLib.mpvEventId.MPV_EVENT_PLAYBACK_RESTART) replacementReady()
            if (eventId == MPVLib.mpvEventId.MPV_EVENT_START_FILE) headphoneCinema.fileLoading()
            if (eventId == MPVLib.mpvEventId.MPV_EVENT_FILE_LOADED) headphoneCinema.fileLoaded()
            return
        }
        when (eventId) {
            MPVLib.mpvEventId.MPV_EVENT_START_FILE -> {
                headphoneCinema.fileLoading()
                viewModel.endHoldSpeed()
                fileLoadedJob?.cancel()
            }
            MPVLib.mpvEventId.MPV_EVENT_FILE_LOADED -> {
                headphoneCinema.fileLoaded()
                if (viewModel.playbackLoadState.value.failure != null) return
                loadAnime4KForCurrentEpisode()
                fileLoadedJob?.cancel()
                fileLoadedJob = lifecycleScope.launch { fileLoaded() }
            }
            MPVLib.mpvEventId.MPV_EVENT_VIDEO_RECONFIG -> requestAnime4KMediaRefresh()
            MPVLib.mpvEventId.MPV_EVENT_AUDIO_RECONFIG -> headphoneCinema.refresh()
            MPVLib.mpvEventId.MPV_EVENT_SEEK -> {
                viewModel.nativeSeekStarted()
                anime4kSmartController?.resetTelemetry(SystemClock.elapsedRealtime())
                viewModel.playbackLoad.seeking(true)
                viewModel.isLoading.value = viewModel.playbackLoadState.value.loading
            }
            MPVLib.mpvEventId.MPV_EVENT_PLAYBACK_RESTART -> {
                replacementReady()
                player.isExiting = false
                if (MPVLib.getPropertyBoolean("seeking") == false) {
                    MPVLib.getPropertyDouble("time-pos")?.let { viewModel.nativeSeekFinished(it.toFloat()) }
                }
                viewModel.playbackLoad.restarted(MPVLib.getPropertyBoolean("paused-for-cache") == true)
                viewModel.isLoading.value = viewModel.playbackLoadState.value.loading
                if (anime4kRoomRestorePending && !viewModel.watchTogether.active) loadAnime4KForCurrentEpisode()
            }
        }
    }

    fun createPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val paused = if (player.isExiting) true else player.paused ?: true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val channel = live.value?.session?.state?.value?.media?.channel
            val anime = viewModel.currentAnime.value
            val episode = viewModel.currentEpisode.value

            if (channel != null) {
                builder.setTitle(channel.name).setSubtitle(getString(R.string.playback_live))
            } else if (anime != null && episode != null) {
                builder.setTitle(anime.title).setSubtitle(episode.name)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val autoEnter = playerPreferences.pipOnExit().get()
            builder.setAutoEnterEnabled(canEnterPictureInPicture && !paused && autoEnter)
            // This is video-only content, with a stable native buffer through PiP.
            // Let Android animate it directly instead of inserting a blank content overlay.
            builder.setSeamlessResizeEnabled(true)
        }
        builder.setActions(
            createPipActions(
                context = this,
                isPaused = paused,
                replaceWithPrevious = playerPreferences.pipReplaceWithPrevious().get(),
                playlistCount = if (live.value != null) 1 else viewModel.currentPlaylist.value.size,
                playlistPosition = if (live.value != null) 0 else viewModel.getCurrentEpisodeIndex(),
            ),
        )
        pipRect?.takeUnless { it.isEmpty }?.let(builder::setSourceRectHint)
        // MPV can expose height before aspect, or clear aspect during a transition.
        // Missing geometry is valid: omit it and let Android use its default.
        val aspect = if (player.isExiting) null else player.getVideoOutAspect()
        PipVideoGeometry.fromAspect(aspect)?.let {
            builder.setAspectRatio(Rational(it.width, it.height))
        }
        return builder.build()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    internal fun pipChanged(isInPictureInPictureMode: Boolean) {
        if (!nativeReady) {
            return
        }
        if (!isInPictureInPictureMode) {
            pipReceiver?.let {
                unregisterReceiver(pipReceiver)
                pipReceiver = null
            }
        } else {
            windowTransitionJob?.cancel()
            windowTransitionJob = null
            windowFrame.value = null
            setPictureInPictureParams(createPipParams())
            viewModel.hideControls()
            viewModel.hideSeekBar()
            viewModel.isBrightnessSliderShown.update { false }
            viewModel.isVolumeSliderShown.update { false }
            viewModel.sheetShown.update { Sheets.None }
            if (pipReceiver != null) {
                return
            }
            pipReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || intent.action != PIP_INTENTS_FILTER) return
                    when (intent.getIntExtra(PIP_INTENT_ACTION, 0)) {
                        PIP_PAUSE -> {
                            if (live.value == null) viewModel.cancelNextEpisode()
                            pause()
                        }
                        PIP_PLAY -> if (live.value != null) live.value?.session?.live() else viewModel.resumeByUser()
                        PIP_NEXT -> next()
                        PIP_PREVIOUS -> if (live.value == null) viewModel.changeEpisode(true)
                        PIP_SKIP -> seek(snapshot.value.positionSeconds + 10)
                    }
                    setPictureInPictureParams(createPipParams())
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER), RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER))
            }
        }

        pip.value = isInPictureInPictureMode
        applySurfaceAspect()
    }

    private fun setupPlayerOrientation() {
        val cast = castController.state.value
        if (cast.active || cast.connecting) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            return
        }
        if (player.isExiting) return
        requestedOrientation = when (playerPreferences.defaultPlayerOrientationType().get()) {
            PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            PlayerOrientation.Video -> if ((player.getVideoOutAspect() ?: 0.0) > 1.0) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }

            PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    internal fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!nativeReady) return false
        if (castController.state.value.active &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            castController.adjustVolume(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                viewModel.changeVolumeBy(1)
                viewModel.displayVolumeSlider()
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                viewModel.changeVolumeBy(-1)
                viewModel.displayVolumeSlider()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> viewModel.handleLeftDoubleTap()
            KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.handleRightDoubleTap()
            KeyEvent.KEYCODE_SPACE -> viewModel.pauseUnpause()
            KeyEvent.KEYCODE_MEDIA_STOP -> finishAndRemoveTask()

            KeyEvent.KEYCODE_MEDIA_REWIND -> viewModel.handleLeftDoubleTap()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> viewModel.handleRightDoubleTap()

            // other keys should be bound by the user in input.conf ig
            else -> {
                event?.let { player.onKey(it) }
                false
            }
        }
        return true
    }

    internal fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!nativeReady || event == null) return false
        if (player.onKey(event)) return true
        return false
    }

    private fun setupMediaSession() {
        val previousAction = gesturePreferences.mediaPreviousGesture().get()
        val playAction = gesturePreferences.mediaPlayPauseGesture().get()
        val nextAction = gesturePreferences.mediaNextGesture().get()

        mediaSession = MediaSession(this, "Nyanime playback").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        live.value?.let {
                            it.session.live()
                            return
                        }
                        when (playAction) {
                            SingleActionGesture.None -> {}
                            SingleActionGesture.Seek -> {}
                            SingleActionGesture.PlayPause -> {
                                super.onPlay()
                                viewModel.resumeByUser()
                                setKeepScreenOn(true)
                            }
                            SingleActionGesture.Custom -> {
                                MPVLib.command(arrayOf("keypress", CustomKeyCodes.MediaPlay.keyCode))
                            }

                            SingleActionGesture.Switch -> {}
                        }
                    }

                    override fun onPause() {
                        live.value?.let {
                            it.session.pause(true)
                            return
                        }
                        when (playAction) {
                            SingleActionGesture.None -> {}
                            SingleActionGesture.Seek -> {}
                            SingleActionGesture.PlayPause -> {
                                super.onPause()
                                viewModel.pauseByUser()
                                setKeepScreenOn(false)
                            }
                            SingleActionGesture.Custom -> {
                                MPVLib.command(arrayOf("keypress", CustomKeyCodes.MediaPlay.keyCode))
                            }

                            SingleActionGesture.Switch -> {}
                        }
                    }

                    override fun onSkipToPrevious() {
                        if (!available || live.value != null) return
                        when (previousAction) {
                            SingleActionGesture.None -> {}
                            SingleActionGesture.Seek -> {
                                viewModel.leftSeek()
                            }
                            SingleActionGesture.PlayPause -> {
                                viewModel.pauseUnpause()
                            }
                            SingleActionGesture.Custom -> {
                                MPVLib.command(arrayOf("keypress", CustomKeyCodes.MediaPrevious.keyCode))
                            }

                            SingleActionGesture.Switch -> viewModel.changeEpisode(true)
                        }
                    }

                    override fun onSkipToNext() {
                        if (!available || live.value != null) return
                        when (nextAction) {
                            SingleActionGesture.None -> {}
                            SingleActionGesture.Seek -> {
                                viewModel.rightSeek()
                            }
                            SingleActionGesture.PlayPause -> {
                                viewModel.pauseUnpause()
                            }
                            SingleActionGesture.Custom -> {
                                MPVLib.command(arrayOf("keypress", CustomKeyCodes.MediaNext.keyCode))
                            }

                            SingleActionGesture.Switch -> viewModel.changeEpisode(false)
                        }
                    }

                    override fun onSeekTo(pos: Long) {
                        if (available) seek(pos / 1000.0)
                    }

                    override fun onStop() {
                        super.onStop()
                        isActive = false
                        if (live.value == null) viewModel.cancelNextEpisode()
                        pause()
                    }
                },
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_STOP or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SKIP_TO_NEXT,
                    )
                    .build(),
            )
            isActive = true
        }

        val filter = IntentFilter().apply { addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY) }
        registerReceiver(noisyReceiver, filter)
        noisyReceiver.initialized = true
    }

    // ==== END MPVKT ====

    /**
     * Switches to the episode based on [episodeId],
     * @param episodeId id of the episode to switch the player to
     * @param autoPlay whether the episode is switching due to auto play
     */
    internal fun changeEpisode(episodeId: Long?, autoPlay: Boolean = false, fromWatchRoom: Boolean = false) {
        val animeId = viewModel.currentAnime.value?.id ?: return
        val selected = episodeId?.takeIf { it >= 0 } ?: return
        if (viewModel.watchTogether.active && !viewModel.watchTogether.state.value.host && !fromWatchRoom) {
            showToast(getString(R.string.room_host_selects_episode))
            return
        }
        viewModel.showSheet(Sheets.None)
        viewModel.showPanel(Panels.None)
        open(PlayerActivity.newIntent(this, animeId, selected))
    }

    fun setVideo(video: Video?, position: Long? = null) {
        if (player.isExiting) return
        if (video == null) return
        advancedPlayerPreferences.setAnime4kUltraActive(UltraFiles.isUltra(video))
        resetAnime4KRuntime()
        anime4kShaderPipeline.apply(Anime4KMode.Off)
        if (castController.state.value.active || castController.state.value.connecting) {
            lifecycleScope.launch {
                castRequest(video)?.let { castController.play(it) }
                viewModel.updateIsLoadingEpisode(false)
            }
            return
        }
        viewModel.mediaReloaded()
        httpServer?.stop()
        httpServer = null

        setHttpOptions(video)
        videoLoadJob?.cancel()
        viewModel.playbackLoad.begin()
        viewModel.isLoading.value = true
        playerObserver.clearError()

        if (viewModel.isLoadingEpisode.value) {
            viewModel.currentEpisode.value?.let { episode ->
                val preservePos = playerPreferences.preserveWatchingPosition().get()
                val resumePosition = sharedStart?.takeIf { it.first == episode.id }?.second
                    ?: castController.takePhoneResume(episode.id ?: -1) ?: position
                    ?: if (episode.seen && !preservePos) {
                        0L
                    } else {
                        episode.last_second_seen
                    }
                MPVLib.command(arrayOf("set", "start", "${resumePosition.coerceAtLeast(0) / 1000.0}"))
            }
        } else {
            MPVLib.command(arrayOf("set", "start", "${player.timePos ?: 0.0}"))
        }

        val videoOptions = video.mpvArgs.joinToString(",") { (option, value) ->
            "$option=\"$value\""
        }

        if (torrentPreferences.torrServerEnable().get() &&
            (
                video.videoUrl.startsWith(torrentServerApi.hostUrl) ||
                    video.videoUrl.startsWith("magnet") ||
                    video.videoUrl.endsWith("torrent")
                )
        ) {
            videoLoadJob = launchVideoLoad {
                TorrentServerService.start()
                torrentLinkHandler(video.videoUrl, video.videoTitle, videoOptions)
            }
        } else {
            videoLoadJob = launchVideoLoad {
                val httpSource = viewModel.currentSource.value as? AnimeHttpSource
                var videoUrl: String = video.videoUrl
                if (video.usesHttpServer() && httpSource != null) {
                    val port = try {
                        httpServer = httpSource.createHttpServer()
                        httpServer?.start()
                        httpServer?.listeningPort ?: 0
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to start http server" }
                        launchUI {
                            toast(AYMR.strings.http_server_start_failure)
                            onPlaybackFailure(PlaybackFailure.Unknown)
                        }
                        return@launchVideoLoad
                    }

                    val newVideo = video.copyHttpServer(port)
                    videoUrl = newVideo.videoUrl
                    viewModel.updateVideo(newVideo)
                }

                currentCoroutineContext().ensureActive()
                MPVLib.command(
                    arrayOf(
                        "loadfile",
                        parseVideoUrl(videoUrl),
                        "replace",
                        "0",
                        videoOptions,
                    ),
                )
            }
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the videos of the episode. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialEpisodeError(error: Throwable) {
        if (error is PlayerViewModel.ExceptionWithStringResource) {
            toast(error.stringResource)
        } else {
            toast(error.message)
        }
        logcat(LogPriority.ERROR, error)
        if (viewModel.currentVideo.value == null) close()
    }

    private suspend fun torrentLinkHandler(videoUrl: String, title: String, videoOptions: String) {
        var index = 0

        // check if link is from localSource
        if (videoUrl.startsWith("content://")) {
            val videoInputStream = applicationContext.contentResolver.openInputStream(videoUrl.toUri())
            val torrent = torrentServerApi.uploadTorrent(videoInputStream!!, title, false)
            val torrentUrl = torrentServerUtils.getTorrentPlayLink(torrent, 0)

            MPVLib.command(
                arrayOf(
                    "loadfile",
                    torrentUrl,
                    "replace",
                    "0",
                    videoOptions,
                ),
            )
            return
        }

        // check if link is from magnet, in that check if index is present
        if (videoUrl.startsWith("magnet")) {
            if (videoUrl.contains("index=")) {
                index = try {
                    videoUrl.substringAfter("index=").substringBefore("&").toInt()
                } catch (_: NumberFormatException) {
                    0
                }
            }
        }

        val currentTorrent = torrentServerApi.addTorrent(videoUrl, title, "", "", false)
        val videoTorrentUrl = torrentServerUtils.getTorrentPlayLink(currentTorrent, index)

        MPVLib.command(
            arrayOf(
                "loadfile",
                videoTorrentUrl,
                "replace",
                "0",
                videoOptions,
            ),
        )
    }

    fun parseVideoUrl(videoUrl: String?): String? {
        return videoUrl?.toUri()?.resolveUri(this)
            ?: videoUrl
    }

    fun setHttpOptions(video: Video) {
        val source = viewModel.currentSource.value as? AnimeHttpSource

        val headers = (video.headers ?: source?.headers)
            ?.toMultimap().orEmpty()
            .mapValues { it.value.firstOrNull() ?: "" }
            .toMutableMap()

        val httpHeaderString = headers.map {
            it.key + ": " + it.value.replace(",", "\\,")
        }.joinToString(",")

        MPVLib.setOptionString("http-header-fields", httpHeaderString)

        // need to fix the cache
        // MPVLib.setOptionString("cache-on-disk", "yes")
        // val cacheDir = File(applicationContext.filesDir, "media").path
        // MPVLib.setOptionString("cache-dir", cacheDir)
    }

    internal fun onPlaybackFailure(reason: PlaybackFailure) {
        if (isDestroyed || isFinishing || live.value != null) return
        if (handledFailureGeneration == playbackGeneration) return
        handledFailureGeneration = playbackGeneration
        replacementReady()
        fileLoadedJob?.cancel()
        videoLoadJob?.cancel()
        viewModel.onPlaybackFailed(reason)
        player.paused = true
        // Stop stale native I/O after the failure state is published. Never reset saved progress.
        MPVLib.command(arrayOf("stop"))
    }

    private fun launchVideoLoad(block: suspend () -> Unit): Job {
        val generation = playbackGeneration
        return lifecycleScope.launchIO {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (playbackGeneration == generation) {
                        onPlaybackFailure(PlaybackFailure.fromNative(e.message.orEmpty()))
                    }
                }
            }
        }
    }

    internal fun openPlaybackSource() {
        val source = viewModel.currentSource.value as? AnimeHttpSource ?: return
        val episode = viewModel.currentEpisode.value ?: return
        lifecycleScope.launch {
            try {
                val domainEpisode = episode.toDomainEpisode() ?: return@launch
                val url = withContext(Dispatchers.IO) {
                    source.getEpisodeUrl(domainEpisode.toSEpisode())
                }
                startActivity(
                    eu.kanade.tachiyomi.ui.webview.WebViewActivity.newIntent(
                        this@PlayerSession,
                        url,
                        source.id,
                        isAnime = true,
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                toast(AYMR.strings.player_error_source_page)
            }
        }
    }

    /**
     * Called from the presenter when a screenshot is ready to be shared. It shows Android's
     * default sharing tool.
     */
    private fun onShareImageResult(uri: Uri, seconds: String) {
        val anime = viewModel.currentAnime.value ?: return
        val episode = viewModel.currentEpisode.value ?: return

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(AYMR.strings.share_screenshot_info, anime.title, episode.name, seconds),
        )
        startActivity(Intent.createChooser(intent, stringResource(MR.strings.action_share)))
    }

    /**
     * Called from the presenter when a screenshot is saved or fails. It shows a message
     * or logs the event depending on the [result].
     */
    private fun onSaveImageResult(result: PlayerViewModel.SaveImageResult) {
        when (result) {
            is PlayerViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is PlayerViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a screenshot is set as art or fails.
     * It shows a different message depending on the [result].
     */
    private fun onSetAsArtResult(result: SetAsArt, artType: ArtType) {
        toast(
            when (result) {
                SetAsArt.Success ->
                    when (artType) {
                        ArtType.Cover -> MR.strings.cover_updated
                        ArtType.Background -> AYMR.strings.background_updated
                        ArtType.Thumbnail -> AYMR.strings.thumbnail_updated
                    }
                SetAsArt.AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                SetAsArt.Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    // TODO: exception java.util.ConcurrentModificationException:
    //  UPDATE: MAY HAVE BEEN FIXED
    // at java.lang.Object java.util.ArrayList$Itr.next() (ArrayList.java:860)
    // at void eu.kanade.tachiyomi.ui.player.PlayerActivity.fileLoaded() (PlayerActivity.kt:1874)
    // at void eu.kanade.tachiyomi.ui.player.PlayerActivity.event(int) (PlayerActivity.kt:1566)
    // at void is.xyz.mpv.MPVLib.event(int) (MPVLib.java:86)
    private suspend fun fileLoaded() {
        if (player.isExiting || viewModel.playbackLoadState.value.failure != null) return
        if (castController.state.value.active || castController.state.value.connecting) {
            player.paused = true
            return
        }
        if (sharedStart?.first == viewModel.currentEpisode.value?.id) {
            sharedStart = null
            intent.removeExtra(SHARED_START)
        }
        viewModel.remoteProgressOwned = false
        mediaSession?.isActive = true
        setMpvOptions()
        setMpvMediaTitle()
        updateHostPresentation()
        setupChapters()
        setupTracks()

        viewModel.onDevicePlaybackReady()
        viewModel.startAutoTracking()

        viewModel.waitingSkipIntro = playerPreferences.waitingTimeIntroSkip().get()
        if (!viewModel.introSkipEnabled ||
            !playerPreferences.aniSkipEnabled().get() ||
            (playerPreferences.disableAniSkipOnChapters().get() && viewModel.chapters.value.isNotEmpty())
        ) {
            return
        }
        val animeId = viewModel.currentAnime.value?.id ?: return
        val episodeId = viewModel.currentEpisode.value?.id ?: return
        val video = viewModel.currentVideo.value ?: return
        val duration = player.duration ?: return
        val stamps = withTimeoutOrNull(15_000L) { viewModel.aniSkipResponse(duration) } ?: return
        withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext().ensureActive()
            if (player.isExiting ||
                viewModel.currentAnime.value?.id != animeId ||
                viewModel.currentEpisode.value?.id != episodeId ||
                viewModel.currentVideo.value !== video ||
                castController.state.value.active ||
                castController.state.value.connecting
            ) {
                return@withContext
            }
            viewModel.recordEndingCue(episodeId, duration, stamps)
            viewModel.updateChapters(
                ChapterUtils.mergeChapters(viewModel.chapters.value, stamps, duration),
            )
            viewModel.setChapter(viewModel.pos.value)
        }
    }

    private fun setMpvOptions() {
        if (player.isExiting) return
        val video = viewModel.currentVideo.value ?: return

        // Only check for `MPV_ARGS_TAG` on downloaded videos
        if (listOf("file", "content", "data").none { video.videoUrl.startsWith(it) }) {
            return
        }

        try {
            val metadata = Json.decodeFromString<Map<String, String>>(
                MPVLib.getPropertyString("metadata"),
            )

            val opts = metadata[Video.MPV_ARGS_TAG]
                ?.split(";")
                ?.map { it.split("=", limit = 2) }
                ?: return

            opts.forEach { (option, value) ->
                MPVLib.setPropertyString(option, value)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read video metadata" }
        }
    }

    private suspend fun setupTracks() {
        if (player.isExiting) return
        viewModel.isLoadingTracks.update { _ -> true }

        val audioTracks = viewModel.currentVideo.value?.audioTracks?.takeIf { it.isNotEmpty() }
        val subtitleTracks = viewModel.currentVideo.value?.subtitleTracks?.takeIf { it.isNotEmpty() }

        // If no external audio or subtitle tracks are present, loadTracks() won't be
        // called and we need to call onFinishLoadingTracks() manually
        if (audioTracks == null && subtitleTracks == null) {
            viewModel.onFinishLoadingTracks()
            return
        }

        val generation = playbackGeneration
        // Adding a remote track may synchronously open its URL. Keep the player
        // controls responsive even when an extension supplies many slow tracks.
        withContext(Dispatchers.IO) {
            trackLoadingMutex.withLock {
                audioTracks?.forEach { audio ->
                    currentCoroutineContext().ensureActive()
                    if (player.isExiting || playbackGeneration != generation) return@withLock
                    executeMPVCommand(arrayOf("audio-add", audio.url, "auto", audio.lang))
                }
                subtitleTracks?.forEach { sub ->
                    currentCoroutineContext().ensureActive()
                    if (player.isExiting || playbackGeneration != generation) return@withLock
                    executeMPVCommand(arrayOf("sub-add", sub.url, "auto", sub.lang))
                }
            }
        }

        viewModel.isLoadingTracks.update { _ -> false }
    }

    private fun setupChapters() {
        if (player.isExiting) return

        val timestamps = viewModel.currentVideo.value?.timestamps?.takeIf { it.isNotEmpty() }
            ?.map { timestamp ->
                if (timestamp.name.isEmpty() && timestamp.type != ChapterType.Other) {
                    timestamp.copy(
                        name = timestamp.type.getStringRes()?.let(::stringResource) ?: "",
                    )
                } else {
                    timestamp
                }
            }
            ?: return

        viewModel.updateChapters(
            ChapterUtils.mergeChapters(
                currentChapters = viewModel.chapters.value,
                stamps = timestamps,
                duration = player.duration,
            ),
        )
        viewModel.setChapter(viewModel.pos.value)
    }

    private fun setMpvMediaTitle() {
        if (player.isExiting) return
        val anime = viewModel.currentAnime.value ?: return
        val episode = viewModel.currentEpisode.value ?: return

        // Write to mpv table
        MPVLib.setPropertyString("user-data/current-anime/episode-title", episode.name)

        val epNumber = episode.episode_number.let { number ->
            if (ceil(number) == floor(number)) number.toInt() else number
        }.toString().padStart(2, '0')

        val title = stringResource(
            AYMR.strings.mpv_media_title,
            anime.title,
            epNumber,
            episode.name,
        )

        MPVLib.setPropertyString("force-media-title", title)
    }

    private fun endFile(eofReached: Boolean) {
        viewModel.onPlaybackEof(eofReached)
    }
    internal fun publishSnapshot() {
        if (closing || !nativeReady || !model.isInitialized()) return
        live.value?.let { runtime ->
            val state = runtime.session.state.value
            val native = runtime.output.status.value
            mutableSnapshot.value = PlaybackSnapshot(
                sessionId, title = state.media?.channel?.name.orEmpty(),
                episode = getString(R.string.tv_live), positionSeconds = native.position ?: 0.0,
                paused = state.paused, buffering = state.loading || state.buffering,
                aspect = native.aspect ?: videoAspect, preparingSelection = preparingSelection.value,
                durationSeconds = native.rewindEnd ?: 0.0, live = true, seekable = native.rewindStart != null,
            )
            return
        }
        mutableSnapshot.value = PlaybackSnapshot(
            sessionId = sessionId,
            animeId = viewModel.currentAnime.value?.id,
            episodeId = viewModel.currentEpisode.value?.id,
            title = viewModel.animeTitle.value,
            episode = viewModel.mediaTitle.value,
            positionSeconds = viewModel.pos.value.toDouble(),
            durationSeconds = viewModel.duration.value.toDouble(),
            paused = viewModel.paused.value,
            buffering = viewModel.isLoading.value,
            aspect = videoAspect,
            preparingSelection = preparingSelection.value,
        )
    }
}
