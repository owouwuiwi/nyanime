package eu.kanade.tachiyomi.ui.player.controls

import android.content.res.Configuration
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraFiles
import eu.kanade.tachiyomi.data.watch.WatchRecovery
import eu.kanade.tachiyomi.data.watch.recovery
import eu.kanade.tachiyomi.data.watch.showPreparationFeedback
import eu.kanade.tachiyomi.ui.cast.CastDevicesDialog
import eu.kanade.tachiyomi.ui.player.Anime4K
import eu.kanade.tachiyomi.ui.player.Anime4KProfile
import eu.kanade.tachiyomi.ui.player.Dialogs
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.player.PlayerUpdates
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.VideoAspect
import eu.kanade.tachiyomi.ui.player.controls.components.TogetherLoadingGlyph
import eu.kanade.tachiyomi.ui.player.playbackChromeVisibility
import eu.kanade.tachiyomi.ui.player.session.PlaybackPresentation
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import it.lorelore.nyanime.presentation.player.VideoActionChip
import it.lorelore.nyanime.presentation.player.VideoChromeActions
import it.lorelore.nyanime.presentation.player.VideoChromeButton
import it.lorelore.nyanime.presentation.player.VideoChromeChapter
import it.lorelore.nyanime.presentation.player.VideoChromeState
import it.lorelore.nyanime.presentation.player.VideoGestureFeedback
import it.lorelore.nyanime.presentation.player.VideoPlaybackChrome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

/** MPV supplies state and commands. Rendering is shared with FLOW, never its player singleton. */
@Composable
internal fun MpvPlaybackChrome(session: PlayerSession, fullscreen: Boolean, onBack: () -> Unit) {
    val model = session.viewModel
    val snapshot by remember(session) {
        session.snapshot.map { it.copy(positionSeconds = 0.0) }.distinctUntilChanged()
    }.collectAsState(initial = session.snapshot.value)
    val position = model.timelinePosition.collectAsState()
    val previewPosition = model.scrubState.collectAsState()
    val buffered by model.readAhead.collectAsState()
    val visible by model.controlsShown.collectAsState()
    val locked by model.areControlsLocked.collectAsState()
    val loading by model.isLoading.collectAsState()
    val loadingEpisode by model.isLoadingEpisode.collectAsState()
    val failure by model.playbackLoadState.collectAsState()
    val previous by model.hasPreviousEpisode.collectAsState()
    val next by model.hasNextEpisode.collectAsState()
    val speed by model.playbackSpeed.collectAsState()
    val chapters by model.chapters.collectAsState()
    val selectedSubtitles by model.selectedSubtitles.collectAsState()
    val video by model.currentVideo.collectAsState()
    val room by model.watchTogether.state.collectAsState()
    val seekAmount by model.gestureSeekAmount.collectAsState()
    val scrubbing by model.isSeeking.collectAsState()
    val doubleTap by model.doubleTapSeekAmount.collectAsState()
    val update by model.playerUpdate.collectAsState()
    val volumeVisible by model.isVolumeSliderShown.collectAsState()
    val brightnessVisible by model.isBrightnessSliderShown.collectAsState()
    val volume = model.currentVolumeLevel.collectAsState()
    val brightness = model.currentBrightness.collectAsState()
    val remaining by model.remainingTime.collectAsState()
    val endTimer by model.sleepTimerEndEpisode.collectAsState()
    val skip by model.skipIntroText.collectAsState()
    val primary by model.primaryActionButton.collectAsState()
    val primaryTitle by model.primaryButtonTitle.collectAsState()
    val preferences = remember { Injekt.get<PlayerPreferences>() }
    val gestures = remember { Injekt.get<GesturePreferences>() }
    val advanced = remember { Injekt.get<AdvancedPlayerPreferences>() }
    val volumeBoostCap by model.volumeBoostCap.collectAsState()
    var smartOrigin by remember { mutableStateOf<Offset?>(null) }
    val reduceMotion by preferences.reduceMotion().collectPreferenceAsState()
    val remainingTime by preferences.invertDuration().collectPreferenceAsState()
    val autoplay by preferences.autoplayEnabled().collectPreferenceAsState()
    val precise by gestures.playerSmoothSeek().collectPreferenceAsState()
    val swapSides by gestures.swapVolumeBrightness().collectPreferenceAsState()
    val qualityShortcut by preferences.showQualityShortcut().collectPreferenceAsState()
    val audioShortcut by preferences.showAudioShortcut().collectPreferenceAsState()
    val timeout by preferences.playerTimeToDisappear().collectPreferenceAsState()
    val sheet by model.sheetShown.collectAsState()
    val panel by model.panelShown.collectAsState()
    val dialog by model.dialogShown.collectAsState()
    val enhancement by advanced.anime4kActiveSelection().collectAsState()
    val cloud by session.anime4kCloudState.collectAsState()
    val aspect by preferences.aspectState().collectPreferenceAsState()
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    var cast by rememberSaveable { mutableStateOf(false) }
    var conversation by rememberSaveable { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val busy =
        room.active &&
            (room.showPreparationFeedback || room.resumeSeconds != null || room.recovery != WatchRecovery.None)
    val chromeVisibility = playbackChromeVisibility(visible, loadingEpisode, busy, failure)
    val overlayVisible = chromeVisibility.controls
    val unobstructed = sheet == Sheets.None && panel == Panels.None && dialog == Dialogs.None && !conversation && !cast
    val waiting = chromeVisibility.waiting && unobstructed && !locked && !scrubbing
    var waitingVisible by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        waitingVisible = false
        if (waiting) {
            delay(300)
            waitingVisible = true
        }
    }
    LaunchedEffect(unobstructed, reduceMotion) {
        if (!unobstructed) model.cancelPlaybackScrub()
        if (!unobstructed || reduceMotion) session.finishAnime4KReveal()
    }
    val markers = remember(chapters) { chapters.map { VideoChromeChapter(it.name, (it.start * 1000).toLong()) } }
    LaunchedEffect(visible, snapshot.paused, scrubbing, interaction) {
        if (visible && !snapshot.paused && !scrubbing) {
            delay(timeout.toLong())
            model.hideControls()
        }
    }
    DisposableEffect(session, fullscreen, portrait) {
        onDispose { model.cancelPlaybackScrub() }
    }
    val customCenter: (@Composable () -> Unit)? = if (scrubbing) {
        {}
    } else if (room.active || seekAmount != null || failure.failure != null) {
        {
            MiddlePlayerControls(
                hasPrevious = previous,
                onSkipPrevious = { model.changeEpisode(true) },
                isLoading = loading,
                isLoadingEpisode = loadingEpisode,
                controlsShown = overlayVisible,
                areControlsLocked = locked,
                showLoadingCircle = true,
                paused = snapshot.paused,
                gestureSeekAmount = seekAmount,
                onPlayPauseClick = session::playPause,
                hasNext = next,
                onSkipNext = session::next,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.widthIn(max = 360.dp),
                failure = failure.failure,
                onRetry = model::retryPlayback,
                onOpenSource = session::openPlaybackSource,
                watchRoom = room,
                reduceMotion = reduceMotion,
                onWatchRetry = {
                    if (room.recovery == WatchRecovery.Connection) {
                        model.watchTogether.retryConnection()
                    } else {
                        model.watchTogether.retryFailedCommand()
                    }
                },
            )
        }
    } else {
        null
    }
    if (cast) CastDevicesDialog(request = session::castRequest, onDismiss = { cast = false })
    eu.kanade.tachiyomi.ui.watch.WatchPlayerRoomPanel(
        visible = conversation && room.active,
        onRoomDetails = { model.showSheet(Sheets.WatchTogether) },
        onDismiss = { conversation = false },
    )
    LaunchedEffect(room.active, locked) { if (!room.active || locked) conversation = false }
    LaunchedEffect(volumeVisible) {
        if (volumeVisible) {
            snapshotFlow { volume.value }.collectLatest {
                delay(2000)
                model.isVolumeSliderShown.value = false
            }
        }
    }
    LaunchedEffect(brightnessVisible) {
        if (brightnessVisible) {
            snapshotFlow { brightness.value }.collectLatest {
                delay(2000)
                model.isBrightnessSliderShown.value = false
            }
        }
    }
    LaunchedEffect(update) {
        if (update !is PlayerUpdates.None && update !is PlayerUpdates.HoldSpeed) {
            delay(2000)
            model.playerUpdate.value = PlayerUpdates.None
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize(),
    ) {
        val compactFeedback = !fullscreen || maxHeight < 220.dp
        val feedbackBottom = minOf(
            if (fullscreen) 110.dp else 64.dp,
            (maxHeight - if (compactFeedback) 100.dp else 130.dp).coerceAtLeast(8.dp),
        )
        if (unobstructed) Anime4KCloudOverlay(cloud, session::completeAnime4KReveal)
        if (unobstructed) {
            VideoPlaybackChrome(
                state = VideoChromeState(
                    visible = overlayVisible,
                    playing = !snapshot.paused,
                    buffering = loading || loadingEpisode || snapshot.buffering,
                    title = snapshot.title,
                    subtitle = snapshot.episode,
                    durationMs = (snapshot.durationSeconds * 1000).toLong().coerceAtLeast(0),
                    speed = speed,
                    quality = video?.quality?.takeIf { model.isEpisodeOnline.value == true && qualityShortcut },
                    resizeMode = when (aspect) {
                        VideoAspect.Fit -> 0
                        VideoAspect.Stretch -> 1
                        VideoAspect.Crop -> 2
                    },
                    fullscreen = fullscreen,
                    portraitFullscreen = portrait,
                    previous = previous,
                    next = next,
                    subtitles = selectedSubtitles.toList().any { it > 0 },
                    pip = session.isPipSupportedAndEnabled,
                    queue = true,
                    autoplay = autoplay,
                    sleepTimer = remaining > 0 || endTimer != null,
                    locked = locked,
                    showRemainingTime = remainingTime,
                    gestureActive = update is PlayerUpdates.HoldSpeed || scrubbing,
                    chapters = markers,
                ),
                actions = VideoChromeActions(
                    playPause = session::playPause,
                    back = onBack,
                    settings = { model.showSheet(Sheets.More) },
                    fullscreen = {
                        session.present(if (fullscreen) PlaybackPresentation.Page else PlaybackPresentation.Fullscreen)
                    },
                    seek = {
                        if (session.available) {
                            if (model.scrubState.value != null) {
                                model.updatePlaybackScrub(it / 1000f)
                                model.commitPlaybackScrub(precise)
                            }
                        }
                    },
                    preview = { model.updatePlaybackScrub(it / 1000f) },
                    scrubbing = {
                        if (it) model.beginPlaybackScrub() else model.cancelPlaybackScrub()
                    },
                    previous = { model.changeEpisode(true) },
                    next = session::next,
                    quality = { model.showSheet(Sheets.QualityTracks) },
                    speed = { model.showSheet(Sheets.PlaybackSpeed) },
                    subtitles = { model.showSheet(Sheets.SubtitleTracks) },
                    subtitleSettings = { model.showPanel(Panels.SubtitleSettings) },
                    pip = {
                        if (session.available &&
                            !loadingEpisode
                        ) {
                            session.enterPictureInPictureMode(session.createPipParams())
                        }
                    },
                    resize = {
                        model.changeVideoAspect(
                            when (aspect) {
                                VideoAspect.Fit -> VideoAspect.Stretch
                                VideoAspect.Stretch -> VideoAspect.Crop
                                VideoAspect.Crop -> VideoAspect.Fit
                            },
                        )
                    },
                    queue = model::showEpisodeListDialog,
                    chapters = { model.showSheet(Sheets.Chapters) },
                    cast = { if (session.castRequest() != null) cast = true },
                    sleepTimer = { model.showSheet(Sheets.SleepTimer) },
                    lock = { if (locked) model.unlockControls() else model.lockControls() },
                    autoplay = model::setAutoPlay,
                    toggleRemainingTime = { preferences.invertDuration().set(!remainingTime) },
                    interaction = { interaction++ },
                ),
                position = { ((previewPosition.value?.target ?: position.value) * 1000).toLong() },
                buffered = if (snapshot.durationSeconds > 0) (buffered / snapshot.durationSeconds).toFloat() else 0f,
                reduceMotion = reduceMotion,
                windowInsets = WindowInsets(0),
                center = customCenter,
                extraActions = {
                    if (audioShortcut && fullscreen && !portrait) {
                        VideoChromeButton(stringResource(R.string.playback_audio), {
                            model.showSheet(Sheets.AudioTracks)
                        }, Icons.Filled.Audiotrack)
                    }
                    if (!room.active && !UltraFiles.isUltra(video)) {
                        VideoChromeButton(
                            "SM",
                            {
                                interaction++
                                session.toggleAnime4KSmart(smartOrigin?.x, smartOrigin?.y, !reduceMotion)
                            },
                            selected = enhancement.profile == Anime4KProfile.Smart,
                            selectedContentColor = Color(0xFFFFD54F),
                            description = "Anime4K · SM · ${Anime4K.smartButtonLabel(enhancement.mode)}",
                            modifier = Modifier.onGloballyPositioned { smartOrigin = it.boundsInWindow().center },
                        )
                    }
                    if (!room.active) {
                        it.lorelore.nyanime.attention.AttentionPlayerButton { interaction++ }
                    }
                    VideoChromeButton(stringResource(R.string.playback_room), {
                        interaction++
                        if (room.active) conversation = true else model.showSheet(Sheets.WatchTogether)
                    }, Icons.Filled.People, selected = room.active)
                },
            )
        }
        VideoGestureFeedback(
            doubleTap,
            (update as? PlayerUpdates.HoldSpeed)?.speed?.toFloat(),
            volumeVisible,
            brightnessVisible,
            volume = { volume.value },
            brightness = { brightness.value.coerceIn(0f, 1f) },
            reduceMotion = reduceMotion,
            swapSides = swapSides,
            maxVolumeLevel = 1f + volumeBoostCap / 100f,
        )
        if (waiting && waitingVisible) {
            Box(Modifier.align(Alignment.Center)) {
                TogetherLoadingGlyph(reduceMotion, shared = false)
            }
        }
        val feedbackText = when (val status = update) {
            PlayerUpdates.AspectRatio -> tachiyomi.presentation.core.i18n.stringResource(aspect.titleRes)
            is PlayerUpdates.ShowText -> status.value
            is PlayerUpdates.ShowTextResource -> tachiyomi.presentation.core.i18n.stringResource(status.textResource)
            else -> null
        }
        if (unobstructed && feedbackText != null) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp)) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    eu.kanade.tachiyomi.ui.player.controls.components.TextPlayerUpdate(feedbackText)
                }
            }
        }
        val diagnosticEnabled by advanced.anime4kDiagnosticsEnabled().collectPreferenceAsState()
        if (diagnosticEnabled && !room.active && !UltraFiles.isUltra(video)) {
            val diagnostics by advanced.anime4kDiagnostics().collectAsState()
            eu.kanade.tachiyomi.ui.player.controls.components.Anime4KDiagnosticsOverlay(
                diagnostics,
                Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 16.dp),
            )
        }
        PlayerScrubFeedback(
            model = model,
            visible = unobstructed && !locked,
            compact = compactFeedback,
            reduceMotion = reduceMotion,
            modifier = Modifier.align(Alignment.BottomCenter).padding(
                start = 16.dp,
                end = 16.dp,
                bottom = feedbackBottom,
            ),
        )
        if (unobstructed && overlayVisible && !locked && !scrubbing && (!skip.isNullOrBlank() || primary != null)) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (fullscreen) 110.dp else 64.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                skip?.takeIf { it.isNotBlank() }?.let {
                    VideoActionChip(Icons.Filled.SkipNext, it, model::onSkipIntro)
                }
                primary?.let {
                    VideoActionChip(Icons.Filled.Extension, primaryTitle, model::onPrimaryCustomButton)
                }
            }
        }
    }
}
