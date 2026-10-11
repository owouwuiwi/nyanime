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

package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import dev.vivvvek.seeker.Segment
import eu.kanade.presentation.discovery.SourceHomeArtwork
import eu.kanade.presentation.more.settings.screen.player.custombutton.getButtons
import eu.kanade.presentation.theme.playerRippleConfiguration
import eu.kanade.tachiyomi.data.download.anime.ultra.UltraFiles
import eu.kanade.tachiyomi.data.watch.WatchRecovery
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.data.watch.recovery
import eu.kanade.tachiyomi.data.watch.showPreparationFeedback
import eu.kanade.tachiyomi.ui.cast.CastDevicesDialog
import eu.kanade.tachiyomi.ui.cast.CastRemoteScreen
import eu.kanade.tachiyomi.ui.player.Anime4K
import eu.kanade.tachiyomi.ui.player.Anime4KMode
import eu.kanade.tachiyomi.ui.player.Anime4KProfile
import eu.kanade.tachiyomi.ui.player.Dialogs
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerUpdates
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.VideoAspect
import eu.kanade.tachiyomi.ui.player.controls.components.Anime4KDiagnosticsOverlay
import eu.kanade.tachiyomi.ui.player.controls.components.BrightnessOverlay
import eu.kanade.tachiyomi.ui.player.controls.components.BrightnessSlider
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.HoldSpeedPlayerUpdate
import eu.kanade.tachiyomi.ui.player.controls.components.NextEpisodeCard
import eu.kanade.tachiyomi.ui.player.controls.components.SeekbarWithTimers
import eu.kanade.tachiyomi.ui.player.controls.components.TextPlayerUpdate
import eu.kanade.tachiyomi.ui.player.controls.components.ThumbnailPreview
import eu.kanade.tachiyomi.ui.player.controls.components.VolumeSlider
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.QualitySheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.toFixed
import eu.kanade.tachiyomi.ui.player.session.LocalPlayerSession
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.watch.WatchActivityCaption
import eu.kanade.tachiyomi.ui.watch.WatchRecoveryCaption
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Suppress("CompositionLocalAllowlist")
val LocalPlayerButtonsClickEvent = staticCompositionLocalOf { {} }

@Composable
fun PlayerControls(
    viewModel: PlayerViewModel,
    onBackPress: () -> Unit,
    onToggleAnime4KSmart: () -> Unit,
    onSelectAnime4KCustom: (Anime4KMode) -> Unit,
    modifier: Modifier = Modifier,
    toolsOnly: Boolean = false,
    onPresentationDrag: ((Float) -> Unit)? = null,
    onPresentationDragEnd: ((Boolean) -> Unit)? = null,
    horizontalSeekEnabled: Boolean = true,
) {
    val spacing = MaterialTheme.padding
    val castActivity = LocalPlayerSession.current
    val castState by castActivity.castController.state.collectAsState()
    val cinemaState by castActivity.headphoneCinema.state.collectAsState()
    LaunchedEffect(castState.active, castState.connecting) {
        if (castState.active || castState.connecting) {
            viewModel.watchTogether.state.collect { if (it.active) viewModel.watchTogether.leave() }
        }
    }
    var showCastDevices by remember { mutableStateOf(false) }
    if (showCastDevices) {
        CastDevicesDialog(request = { castActivity.castRequest() }, onDismiss = { showCastDevices = false })
    }
    if (castState.active || castState.connecting) {
        val episode by viewModel.currentEpisode.collectAsState()
        var qualityShown by remember { mutableStateOf(false) }
        val sameEpisode = episode?.id == castState.media?.episodeId && castState.active
        CastRemoteScreen(
            onBack = onBackPress,
            modifier = modifier,
            onQuality = if (sameEpisode) ({ qualityShown = true }) else null,
        )
        if (qualityShown && sameEpisode) {
            val hosters by viewModel.hosterState.collectAsState()
            val expanded by viewModel.hosterExpandedList.collectAsState()
            val selected by viewModel.selectedHosterVideoIndex.collectAsState()
            val loading by viewModel.isLoadingHosters.collectAsState()
            QualitySheet(
                isLoadingHosters = loading,
                hosterState = hosters,
                expandedState = expanded,
                selectedVideoIndex = selected,
                onClickHoster = viewModel::onHosterClicked,
                onClickVideo = { host, video ->
                    qualityShown = false
                    viewModel.onVideoClicked(host, video)
                },
                displayHosters = true to true,
                onDismissRequest = { qualityShown = false },
                dismissSheet = false,
            )
        }
        return
    }
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    val gesturePreferences = remember { Injekt.get<GesturePreferences>() }
    val audioPreferences = remember { Injekt.get<AudioPreferences>() }
    val advancedPlayerPreferences = remember { Injekt.get<AdvancedPlayerPreferences>() }
    val subtitlePreferences = remember { Injekt.get<SubtitlePreferences>() }
    val interactionSource = remember { MutableInteractionSource() }

    val reduceMotion by playerPreferences.reduceMotion().collectAsState()
    val controlsShown by viewModel.controlsShown.collectAsState()
    val areControlsLocked by viewModel.areControlsLocked.collectAsState()
    val seekBarShown by viewModel.seekBarShown.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingEpisode by viewModel.isLoadingEpisode.collectAsState()
    val isEpisodeOnline by viewModel.isEpisodeOnline.collectAsState()
    val playbackLoad by viewModel.playbackLoadState.collectAsState()
    val playbackActivity = LocalPlayerSession.current
    val isSeeking by viewModel.isSeeking.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val gestureSeekAmount by viewModel.gestureSeekAmount.collectAsState()
    val doubleTapSeekAmount by viewModel.doubleTapSeekAmount.collectAsState()
    val seekText by viewModel.seekText.collectAsState()
    val currentChapter by viewModel.currentChapter.collectAsState()
    val indexedChapters by viewModel.chapters.collectAsState()
    val currentBrightness by viewModel.currentBrightness.collectAsState()
    val playerRoom by viewModel.watchTogether.state.collectAsState()
    val currentVideo by viewModel.currentVideo.collectAsState()
    val ultraVideo = UltraFiles.isUltra(currentVideo)
    val sheetShown by viewModel.sheetShown.collectAsState()
    val panel by viewModel.panelShown.collectAsState()
    val dialog by viewModel.dialogShown.collectAsState()
    // Refresh when entering/leaving PiP, and stop the shared animation behind other screens.
    val inPip by playbackActivity.pip.collectAsState()
    val playerOverlayVisible = !inPip &&
        sheetShown == Sheets.None &&
        panel == Panels.None &&
        dialog == Dialogs.None
    val sharedPlaybackVisible = !toolsOnly && playerRoom.active && playerOverlayVisible
    val sharedPlaybackBusy = sharedPlaybackVisible &&
        (
            playerRoom.showPreparationFeedback ||
                playerRoom.resumeSeconds != null ||
                isLoading ||
                isLoadingEpisode ||
                playerRoom.recovery != WatchRecovery.None
            )
    val anime4kSelection by advancedPlayerPreferences.anime4kActiveSelection().collectAsState()
    val anime4kDiagnosticsEnabled by advancedPlayerPreferences.anime4kDiagnosticsEnabled().collectAsState()

    val playerTimeToDisappear by playerPreferences.playerTimeToDisappear().collectAsState()
    var resetControls by remember { mutableStateOf(true) }

    val customButtons by viewModel.customButtons.collectAsState()
    val customButton by viewModel.primaryButton.collectAsState()

    val chapters = remember(indexedChapters) {
        indexedChapters.map { it.toSegment() }.toImmutableList()
    }

    LaunchedEffect(
        controlsShown,
        paused,
        isSeeking,
        resetControls,
    ) {
        if (controlsShown && !paused && !isSeeking) {
            delay(playerTimeToDisappear.toLong())
            viewModel.hideControls()
        }
    }

    val transparentOverlay by animateFloatAsState(
        if (controlsShown && !areControlsLocked) .28f else 0f,
        animationSpec = playerControlsExitAnimationSpec(),
        label = "controls_transparent_overlay",
    )
    if (!toolsOnly && !inPip) {
        GestureHandler(
            viewModel = viewModel,
            interactionSource = interactionSource,
            onPresentationDrag = onPresentationDrag,
            onPresentationDragEnd = onPresentationDragEnd,
            horizontalSeekEnabled = horizontalSeekEnabled,
        )
        DoubleTapToSeekOvals(doubleTapSeekAmount, seekText, interactionSource)
    }
    CompositionLocalProvider(
        LocalRippleConfiguration provides playerRippleConfiguration,
        LocalPlayerButtonsClickEvent provides { resetControls = !resetControls },
        LocalContentColor provides Color.White,
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr,
        ) {
            if (!toolsOnly && !inPip) {
                ConstraintLayout(
                    modifier = modifier
                        .fillMaxSize()
                        .background(
                            Color.Black.copy(alpha = transparentOverlay),
                        )
                        .padding(horizontal = MaterialTheme.padding.medium),
                ) {
                    val (topLeftControls, topRightControls) = createRefs()
                    val (volumeSlider, brightnessSlider) = createRefs()
                    val unlockControlsButton = createRef()
                    val (bottomRightControls, bottomLeftControls) = createRefs()
                    val centerControls = createRef()
                    val thumbnail = createRef()
                    val seekbar = createRef()
                    val (playerUpdates) = createRefs()
                    val anime4kDiagnosticsOverlay = createRef()

                    if (anime4kDiagnosticsEnabled && !playerRoom.active && !ultraVideo) {
                        val anime4kDiagnostics by advancedPlayerPreferences.anime4kDiagnostics().collectAsState()
                        Anime4KDiagnosticsOverlay(
                            diagnostics = anime4kDiagnostics,
                            modifier = Modifier.constrainAs(anime4kDiagnosticsOverlay) {
                                top.linkTo(parent.top, spacing.medium)
                                start.linkTo(parent.start, spacing.medium)
                            },
                        )
                    }

                    val hasPreviousEpisode by viewModel.hasPreviousEpisode.collectAsState()
                    val hasNextEpisode by viewModel.hasNextEpisode.collectAsState()
                    val isBrightnessSliderShown by viewModel.isBrightnessSliderShown.collectAsState()
                    val isVolumeSliderShown by viewModel.isVolumeSliderShown.collectAsState()
                    val brightness by viewModel.currentBrightness.collectAsState()
                    val volume by viewModel.currentVolume.collectAsState()
                    val mpvVolume by viewModel.currentMPVVolume.collectAsState()
                    val swapVolumeAndBrightness by gesturePreferences.swapVolumeBrightness().collectAsState()

                    LaunchedEffect(volume, mpvVolume, isVolumeSliderShown) {
                        delay(2000)
                        if (isVolumeSliderShown) viewModel.isVolumeSliderShown.update { false }
                    }
                    LaunchedEffect(brightness, isBrightnessSliderShown) {
                        delay(2000)
                        if (isBrightnessSliderShown) viewModel.isBrightnessSliderShown.update { false }
                    }
                    AnimatedVisibility(
                        isBrightnessSliderShown,
                        enter =
                        if (!reduceMotion) {
                            slideInHorizontally(playerControlsEnterAnimationSpec()) {
                                if (swapVolumeAndBrightness) -it else it
                            } +
                                fadeIn(
                                    playerControlsEnterAnimationSpec(),
                                )
                        } else {
                            fadeIn(playerControlsEnterAnimationSpec())
                        },
                        exit =
                        if (!reduceMotion) {
                            slideOutHorizontally(playerControlsExitAnimationSpec()) {
                                if (swapVolumeAndBrightness) -it else it
                            } +
                                fadeOut(
                                    playerControlsExitAnimationSpec(),
                                )
                        } else {
                            fadeOut(playerControlsExitAnimationSpec())
                        },
                        modifier = Modifier.constrainAs(brightnessSlider) {
                            if (swapVolumeAndBrightness) {
                                start.linkTo(parent.start, spacing.medium)
                            } else {
                                end.linkTo(parent.end, spacing.medium)
                            }
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                        },
                    ) {
                        BrightnessSlider(
                            brightness = brightness,
                            positiveRange = 0f..1f,
                            negativeRange = 0f..0.75f,
                        )
                    }

                    AnimatedVisibility(
                        isVolumeSliderShown,
                        enter =
                        if (!reduceMotion) {
                            slideInHorizontally(playerControlsEnterAnimationSpec()) {
                                if (swapVolumeAndBrightness) it else -it
                            } +
                                fadeIn(
                                    playerControlsEnterAnimationSpec(),
                                )
                        } else {
                            fadeIn(playerControlsEnterAnimationSpec())
                        },
                        exit =
                        if (!reduceMotion) {
                            slideOutHorizontally(playerControlsExitAnimationSpec()) {
                                if (swapVolumeAndBrightness) it else -it
                            } +
                                fadeOut(
                                    playerControlsExitAnimationSpec(),
                                )
                        } else {
                            fadeOut(playerControlsExitAnimationSpec())
                        },
                        modifier = Modifier.constrainAs(volumeSlider) {
                            if (swapVolumeAndBrightness) {
                                end.linkTo(parent.end, spacing.medium)
                            } else {
                                start.linkTo(parent.start, spacing.medium)
                            }
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                        },
                    ) {
                        val boostCap by audioPreferences.volumeBoostCap().collectAsState()
                        val displayVolumeAsPercentage by playerPreferences.displayVolPer().collectAsState()
                        VolumeSlider(
                            volume = volume,
                            mpvVolume = mpvVolume,
                            range = 0..viewModel.maxVolume,
                            boostRange = if (boostCap > 0) 0..audioPreferences.volumeBoostCap().get() else null,
                            displayAsPercentage = displayVolumeAsPercentage,
                        )
                    }

                    val currentPlayerUpdate by viewModel.playerUpdate.collectAsState()
                    val aspectRatio by playerPreferences.aspectState().collectAsState()
                    LaunchedEffect(currentPlayerUpdate, aspectRatio) {
                        if (currentPlayerUpdate is PlayerUpdates.HoldSpeed ||
                            currentPlayerUpdate is PlayerUpdates.None
                        ) {
                            return@LaunchedEffect
                        }
                        delay(2000)
                        viewModel.playerUpdate.update { PlayerUpdates.None }
                    }
                    AnimatedVisibility(
                        currentPlayerUpdate !is PlayerUpdates.None,
                        enter = fadeIn(playerControlsEnterAnimationSpec()),
                        exit = fadeOut(playerControlsExitAnimationSpec()),
                        modifier = Modifier.constrainAs(playerUpdates) {
                            linkTo(parent.start, parent.end)
                            linkTo(parent.top, parent.bottom, bias = 0.2f)
                        },
                    ) {
                        when (currentPlayerUpdate) {
                            is PlayerUpdates.HoldSpeed -> HoldSpeedPlayerUpdate(
                                (currentPlayerUpdate as PlayerUpdates.HoldSpeed).speed,
                            )
                            is PlayerUpdates.AspectRatio -> TextPlayerUpdate(stringResource(aspectRatio.titleRes))
                            is PlayerUpdates.ShowText -> TextPlayerUpdate(
                                (currentPlayerUpdate as PlayerUpdates.ShowText).value,
                            )
                            is PlayerUpdates.ShowTextResource -> TextPlayerUpdate(
                                stringResource((currentPlayerUpdate as PlayerUpdates.ShowTextResource).textResource),
                            )
                            else -> {}
                        }
                    }

                    AnimatedVisibility(
                        controlsShown && areControlsLocked,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.constrainAs(unlockControlsButton) {
                            top.linkTo(parent.top, spacing.medium)
                            start.linkTo(parent.start, spacing.medium)
                        },
                    ) {
                        ControlsButton(
                            Icons.Filled.Lock,
                            onClick = { viewModel.unlockControls() },
                        )
                    }
                    AnimatedVisibility(
                        visible =
                        (controlsShown && !areControlsLocked || gestureSeekAmount != null) ||
                            isLoading ||
                            isLoadingEpisode ||
                            sharedPlaybackBusy ||
                            playbackLoad.failure != null,
                        enter = fadeIn(playerControlsEnterAnimationSpec()),
                        exit = fadeOut(playerControlsExitAnimationSpec()),
                        modifier = Modifier.constrainAs(centerControls) {
                            end.linkTo(parent.absoluteRight)
                            start.linkTo(parent.absoluteLeft)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                        },
                    ) {
                        val showLoadingCircle by playerPreferences.showLoadingCircle().collectAsState()
                        MiddlePlayerControls(
                            hasPrevious = hasPreviousEpisode,
                            onSkipPrevious = { viewModel.changeEpisode(true) },
                            hasNext = hasNextEpisode,
                            onSkipNext = { viewModel.changeEpisode(false) },
                            isLoading = isLoading,
                            isLoadingEpisode = isLoadingEpisode,
                            controlsShown = controlsShown,
                            areControlsLocked = areControlsLocked,
                            showLoadingCircle = showLoadingCircle,
                            paused = if (playerRoom.active) {
                                !playerRoom.wantsPlayback || playerRoom.localHold
                            } else {
                                paused
                            },
                            gestureSeekAmount = gestureSeekAmount,
                            onPlayPauseClick = viewModel::pauseUnpause,
                            watchRoom = if (sharedPlaybackVisible) playerRoom else WatchRoomState(),
                            reduceMotion = reduceMotion || !playerOverlayVisible,
                            failure = playbackLoad.failure,
                            onRetry = viewModel::retryPlayback,
                            onWatchRetry = {
                                when (playerRoom.recovery) {
                                    WatchRecovery.Command -> viewModel.watchTogether.retryFailedCommand()
                                    WatchRecovery.Connection -> viewModel.watchTogether.retryConnection()
                                    else -> viewModel.showSheet(Sheets.WatchTogether)
                                }
                            },
                            onOpenSource = if (viewModel.isEpisodeOnline() ==
                                true
                            ) {
                                playbackActivity::openPlaybackSource
                            } else {
                                null
                            },
                            enter = fadeIn(playerControlsEnterAnimationSpec()),
                            exit = fadeOut(playerControlsExitAnimationSpec()),
                        )
                    }

                    AnimatedVisibility(
                        visible = (controlsShown || seekBarShown) && !areControlsLocked,
                        enter = if (!reduceMotion) {
                            slideInVertically(playerControlsEnterAnimationSpec()) { it } +
                                fadeIn(playerControlsEnterAnimationSpec())
                        } else {
                            fadeIn(playerControlsEnterAnimationSpec())
                        },
                        exit = if (!reduceMotion) {
                            slideOutVertically(playerControlsExitAnimationSpec()) { it } +
                                fadeOut(playerControlsExitAnimationSpec())
                        } else {
                            fadeOut(playerControlsExitAnimationSpec())
                        },
                        modifier = Modifier.constrainAs(seekbar) {
                            bottom.linkTo(parent.bottom, spacing.medium)
                        },
                    ) {
                        PlayerTimeline(viewModel, playerPreferences, gesturePreferences, chapters)
                    }

                    val mediaTitle by viewModel.mediaTitle.collectAsState()
                    val animeTitle by viewModel.animeTitle.collectAsState()
                    AnimatedVisibility(
                        controlsShown && !areControlsLocked,
                        enter = if (!reduceMotion) {
                            slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                                fadeIn(playerControlsEnterAnimationSpec())
                        } else {
                            fadeIn(playerControlsEnterAnimationSpec())
                        },
                        exit = if (!reduceMotion) {
                            slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                                fadeOut(playerControlsExitAnimationSpec())
                        } else {
                            fadeOut(playerControlsExitAnimationSpec())
                        },
                        modifier = Modifier.constrainAs(topLeftControls) {
                            top.linkTo(parent.top, spacing.medium)
                            start.linkTo(parent.start)
                            width = Dimension.fillToConstraints
                            end.linkTo(topRightControls.start)
                        },
                    ) {
                        TopLeftPlayerControls(
                            animeTitle = animeTitle,
                            mediaTitle = mediaTitle,
                            activity = playerRoom.activity.takeIf {
                                sharedPlaybackVisible && it?.actorId != playerRoom.localMemberId
                            },
                            reduceMotion = reduceMotion,
                            onTitleClick = { viewModel.showEpisodeListDialog() },
                            onBackClick = onBackPress,
                        )
                    }
                    // Top right controls
                    val autoPlayEnabled by playerPreferences.autoplayEnabled().collectAsState()
                    AnimatedVisibility(
                        controlsShown && !areControlsLocked,
                        enter = if (!reduceMotion) {
                            slideInHorizontally(playerControlsEnterAnimationSpec()) { it } +
                                fadeIn(playerControlsEnterAnimationSpec())
                        } else {
                            fadeIn(playerControlsEnterAnimationSpec())
                        },
                        exit = if (!reduceMotion) {
                            slideOutHorizontally(playerControlsExitAnimationSpec()) { it } +
                                fadeOut(playerControlsExitAnimationSpec())
                        } else {
                            fadeOut(playerControlsExitAnimationSpec())
                        },
                        modifier = Modifier.constrainAs(topRightControls) {
                            top.linkTo(parent.top, spacing.medium)
                            end.linkTo(parent.end)
                        },
                    ) {
                        val sleepTimerTimeRemaining by viewModel.remainingTime.collectAsState()
                        val sleepTimerEndEpisode by viewModel.sleepTimerEndEpisode.collectAsState()
                        val watchRoom by viewModel.watchTogether.state.collectAsState()
                        val showAudioShortcut by playerPreferences.showAudioShortcut().collectAsState()
                        val showQualityShortcut by playerPreferences.showQualityShortcut().collectAsState()
                        TopRightPlayerControls(
                            onCastClick = {
                                if (castActivity.castRequest() != null) {
                                    showCastDevices = true
                                } else {
                                    castActivity.showToast("Attendi il caricamento del video")
                                }
                            },
                            autoPlayEnabled = autoPlayEnabled,
                            onToggleAutoPlay = { viewModel.setAutoPlay(it) },
                            onSubtitlesClick = { viewModel.showSheet(Sheets.SubtitleTracks) },
                            onSubtitlesLongClick = { viewModel.showPanel(Panels.SubtitleSettings) },
                            onAudioClick = { viewModel.showSheet(Sheets.AudioTracks) },
                            onAudioLongClick = { viewModel.showPanel(Panels.AudioDelay) },
                            onQualityClick = { viewModel.showSheet(Sheets.QualityTracks) },
                            isEpisodeOnline = isEpisodeOnline,
                            showAudioShortcut = showAudioShortcut,
                            showQualityShortcut = showQualityShortcut,
                            isUltraVideo = ultraVideo,
                            isAnime4KSmartEnabled = anime4kSelection.profile == Anime4KProfile.Smart,
                            anime4KSmartLabel = if (anime4kSelection.profile == Anime4KProfile.Smart) {
                                Anime4K.smartButtonLabel(anime4kSelection.mode)
                            } else {
                                "SM"
                            },
                            onToggleAnime4KSmart = onToggleAnime4KSmart,
                            sleepTimerRemaining = sleepTimerTimeRemaining,
                            sleepTimerAtEpisodeEnd = sleepTimerEndEpisode != null,
                            onSleepTimerClick = { viewModel.showSheet(Sheets.SleepTimer) },
                            onMoreClick = { viewModel.showSheet(Sheets.More) },
                            onMoreLongClick = { viewModel.showPanel(Panels.VideoFilters) },
                            watchRoom = watchRoom,
                            onWatchTogetherClick = { viewModel.showSheet(Sheets.WatchTogether) },
                        )
                    }
                    // Bottom right controls
                    val skipIntroButton by viewModel.skipIntroText.collectAsState()
                    val customButtonTitle by viewModel.primaryButtonTitle.collectAsState()
                    AnimatedVisibility(
                        controlsShown && !areControlsLocked,
                        enter = if (!reduceMotion) {
                            slideInHorizontally(playerControlsEnterAnimationSpec()) { it } +
                                fadeIn(playerControlsEnterAnimationSpec())
                        } else {
                            fadeIn(playerControlsEnterAnimationSpec())
                        },
                        exit = if (!reduceMotion) {
                            slideOutHorizontally(playerControlsExitAnimationSpec()) { it } +
                                fadeOut(playerControlsExitAnimationSpec())
                        } else {
                            fadeOut(playerControlsExitAnimationSpec())
                        },
                        modifier = Modifier.constrainAs(bottomRightControls) {
                            bottom.linkTo(seekbar.top)
                            end.linkTo(seekbar.end)
                        },
                    ) {
                        val activity = LocalPlayerSession.current
                        BottomRightPlayerControls(
                            customButton = customButton,
                            customButtonTitle = customButtonTitle,
                            skipIntroButton = skipIntroButton,
                            onPressSkipIntroButton = viewModel::onSkipIntro,
                            isPipAvailable = activity.isPipSupportedAndEnabled,
                            onPipClick = {
                                if (!viewModel.isLoadingEpisode.value) {
                                    activity.enterPictureInPictureMode(activity.createPipParams())
                                }
                            },
                            onAspectClick = {
                                viewModel.changeVideoAspect(
                                    when (aspectRatio) {
                                        VideoAspect.Fit -> VideoAspect.Stretch
                                        VideoAspect.Stretch -> VideoAspect.Crop
                                        VideoAspect.Crop -> VideoAspect.Fit
                                    },
                                )
                            },
                        )
                    }
                    // Bottom left controls
                    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
                    AnimatedVisibility(
                        controlsShown && !areControlsLocked,
                        enter = if (!reduceMotion) {
                            slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                                fadeIn(playerControlsEnterAnimationSpec())
                        } else {
                            fadeIn(playerControlsEnterAnimationSpec())
                        },
                        exit = if (!reduceMotion) {
                            slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                                fadeOut(playerControlsExitAnimationSpec())
                        } else {
                            fadeOut(playerControlsExitAnimationSpec())
                        },
                        modifier = Modifier.constrainAs(bottomLeftControls) {
                            bottom.linkTo(seekbar.top)
                            start.linkTo(seekbar.start)
                            width = Dimension.fillToConstraints
                            end.linkTo(bottomRightControls.start)
                        },
                    ) {
                        BottomLeftPlayerControls(
                            playbackSpeed,
                            currentChapter = currentChapter?.toSegment(),
                            onLockControls = viewModel::lockControls,
                            onCycleRotation = viewModel::cycleScreenRotations,
                            onPlaybackSpeedChange = {
                                viewModel.setPlaybackSpeedByUser(it.toDouble())
                            },
                            onOpenSheet = viewModel::showSheet,
                        )
                    }

                    PlayerSeekPreview(
                        viewModel,
                        isSeeking,
                        chapters,
                        Modifier.fillMaxWidth().constrainAs(thumbnail) {
                            bottom.linkTo(seekbar.top, spacing.medium)
                        },
                    )
                }
            }
        }

        val dismissSheet by viewModel.dismissSheet.collectAsState()
        val subtitles by viewModel.subtitleTracks.collectAsState()
        val selectedSubtitles by viewModel.selectedSubtitles.collectAsState()
        val audioTracks by viewModel.audioTracks.collectAsState()
        val selectedAudio by viewModel.selectedAudio.collectAsState()
        val isLoadingHosters by viewModel.isLoadingHosters.collectAsState()
        val hosterState by viewModel.hosterState.collectAsState()
        val expandedState by viewModel.hosterExpandedList.collectAsState()
        val selectedHosterVideoIndex by viewModel.selectedHosterVideoIndex.collectAsState()
        val decoder by viewModel.currentDecoder.collectAsState()
        val speed by viewModel.playbackSpeed.collectAsState()
        val sleepTimerTimeRemaining by viewModel.remainingTime.collectAsState()
        val sleepTimerEndEpisode by viewModel.sleepTimerEndEpisode.collectAsState()
        val lastCustomTimerMinutes by playerPreferences.lastSleepTimerMinutes().collectAsState()
        val showSubtitles by subtitlePreferences.screenshotSubtitles().collectAsState()
        val currentSource by viewModel.currentSource.collectAsState()
        val showFailedHosters by playerPreferences.showFailedHosters().collectAsState()
        val emptyHosters by playerPreferences.showEmptyHosters().collectAsState()
        val sheetAnime by viewModel.currentAnime.collectAsState()

        PlayerSheets(
            cinemaState = cinemaState,
            onCinemaSelect = castActivity.headphoneCinema::setEnabled,
            anime = sheetAnime,
            sheetShown = sheetShown,
            onOpenSheet = { target ->
                if (target == Sheets.Screenshot) viewModel.pauseByUser()
                viewModel.showSheet(target)
            },
            isEpisodeOnline = isEpisodeOnline == true,
            subtitles = subtitles.toImmutableList(),
            selectedSubtitles = selectedSubtitles.toList().toImmutableList(),
            onAddSubtitle = viewModel::addSubtitle,
            onSelectSubtitle = viewModel::selectSub,
            audioTracks = audioTracks.toImmutableList(),
            selectedAudio = selectedAudio,
            onAddAudio = viewModel::addAudio,
            onSelectAudio = {
                if (it != selectedAudio) {
                    castActivity.headphoneCinema.audioTrackChanging()
                    viewModel.selectAudio(it)
                    castActivity.headphoneCinema.refresh()
                }
            },

            isLoadingHosters = isLoadingHosters,

            hosterState = hosterState,
            expandedState = expandedState,
            selectedVideoIndex = selectedHosterVideoIndex,
            onClickHoster = viewModel::onHosterClicked,
            onClickVideo = viewModel::onVideoClicked,
            displayHosters = Pair(showFailedHosters, emptyHosters),

            chapter = currentChapter?.toSegment(),
            chapters = chapters,
            onSeekToChapter = {
                viewModel.selectChapter(it)
                viewModel.dismissSheet()
                viewModel.unpause()
            },
            decoder = decoder,
            onUpdateDecoder = viewModel::updateDecoder,
            speed = speed,
            onSpeedChange = { viewModel.setPlaybackSpeedByUser(it.toFixed(2).toDouble()) },
            sleepTimerTimeRemaining = sleepTimerTimeRemaining,
            onStartSleepTimer = viewModel::startTimer,
            onStartCustomSleepTimer = viewModel::startCustomTimer,
            onEndSleepTimer = viewModel::stopAtEpisodeEnd,
            sleepTimerAtEpisodeEnd = sleepTimerEndEpisode != null,
            lastCustomTimerMinutes = lastCustomTimerMinutes,
            onExtendSleepTimer = viewModel::extendTimer,
            onOpenSleepTimer = { viewModel.showSheet(Sheets.SleepTimer) },
            onOpenWatchTogether = { viewModel.showSheet(Sheets.WatchTogether) },
            onShareContent = {
                viewModel.showSheet(Sheets.None)
                playbackActivity.shareCurrentContent()
            }.takeIf { currentSource is eu.kanade.tachiyomi.animesource.online.AnimeHttpSource },
            reduceMotion = reduceMotion,
            buttons = customButtons.getButtons().toImmutableList(),
            onSelectAnime4KCustom = onSelectAnime4KCustom,
            anime4kAvailable = !playerRoom.active && !ultraVideo,

            isLocalSource = currentSource?.id == LocalAnimeSource.ID,
            showSubtitles = showSubtitles,
            onToggleShowSubtitles = { subtitlePreferences.screenshotSubtitles().set(it) },
            cachePath = viewModel.cachePath,
            onSetAsArt = viewModel::setAsArt,
            onShare = { viewModel.shareImage(it, viewModel.pos.value.toInt()) },
            onSave = { viewModel.saveImage(it, viewModel.pos.value.toInt()) },
            takeScreenshot = viewModel::takeScreenshot,
            onDismissScreenshot = {
                viewModel.showSheet(Sheets.None)
            },
            onOpenPanel = viewModel::showPanel,
            onDismissRequest = { viewModel.showSheet(Sheets.None) },
            dismissSheet = dismissSheet,
        )
        PlayerPanels(
            panelShown = panel,
            onDismissRequest = { viewModel.showPanel(Panels.None) },
        )

        val activity = LocalPlayerSession.current
        val anime by viewModel.currentAnime.collectAsState()
        val playlist by viewModel.currentPlaylist.collectAsState()
        val nextEpisodePrompt by viewModel.nextEpisodePrompt.collectAsState()
        val skippedFillers by viewModel.skippedFillers.collectAsState()
        if (!toolsOnly && !inPip && skippedFillers > 0) {
            Box(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 90.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                eu.kanade.presentation.episodeguide.GuideSkippedFeedback(
                    skippedFillers,
                    onHidden = viewModel::clearSkippedFillers,
                ) {
                    viewModel.showEpisodeListDialog()
                    viewModel.clearSkippedFillers()
                }
            }
        }
        val watchRoom by viewModel.watchTogether.state.collectAsState()

        Box(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            WatchActivityCaption(
                activity = playerRoom.activity.takeIf {
                    sharedPlaybackVisible &&
                        !controlsShown &&
                        !areControlsLocked &&
                        playbackLoad.failure == null &&
                        it?.actorId != playerRoom.localMemberId
                },
                reduceMotion = reduceMotion,
                modifier = Modifier.offset(y = (-76).dp),
            )
            WatchRecoveryCaption(
                room = playerRoom,
                loading = isLoading || isLoadingEpisode,
                visible = sharedPlaybackVisible && !areControlsLocked && playbackLoad.failure == null,
                reduceMotion = reduceMotion,
                onDetails = { viewModel.showSheet(Sheets.WatchTogether) },
                modifier = Modifier.offset(y = 80.dp),
            )
        }

        Box(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            AnimatedVisibility(
                visible = !toolsOnly &&
                    (
                        nextEpisodePrompt != null ||
                            watchRoom.active &&
                            (
                                watchRoom.skip != null ||
                                    watchRoom.next != null
                                )
                        ) &&
                    !areControlsLocked &&
                    !inPip &&
                    sheetShown == Sheets.None &&
                    panel == Panels.None &&
                    dialog == Dialogs.None,
                enter = fadeIn(tween(if (reduceMotion) 0 else 220)) +
                    slideInVertically(tween(if (reduceMotion) 0 else 260)) { it / 5 },
                exit = fadeOut(tween(if (reduceMotion) 0 else 120)),
            ) {
                val prompt = nextEpisodePrompt
                val nextEpisode = playlist.firstOrNull { it.id == prompt?.episodeId }
                if (watchRoom.active) {
                    eu.kanade.tachiyomi.ui.watch.WatchRoomCues(
                        watchRoom,
                        { viewModel.watchTogether.requestSkip() },
                        { viewModel.watchTogether.cancelSkip() },
                        viewModel::playNextEpisodeNow,
                        viewModel::cancelNextEpisode,
                        reduceMotion = reduceMotion,
                        artwork = { anime?.let { SourceHomeArtwork(it, Modifier.fillMaxSize()) } },
                    )
                } else if (prompt != null && nextEpisode != null) {
                    NextEpisodeCard(
                        seriesTitle = anime?.title.orEmpty(),
                        episodeTitle = nextEpisode.name,
                        secondsRemaining = prompt.secondsRemaining,
                        onPlayNow = viewModel::playNextEpisodeNow,
                        onCancel = {
                            viewModel.cancelNextEpisode()
                            viewModel.showControls()
                        },
                        reduceMotion = reduceMotion,
                        artwork = { anime?.let { SourceHomeArtwork(it, Modifier.fillMaxSize()) } },
                    )
                }
            }
        }

        PlayerDialogs(
            dialogShown = dialog,
            episodeDisplayMode = anime?.displayMode,
            episodeList = playlist,
            currentEpisodeIndex = viewModel.getCurrentEpisodeIndex(),
            dateRelativeTime = viewModel.relativeTime,
            dateFormat = viewModel.dateFormat,
            onBookmarkClicked = viewModel::bookmarkEpisode,
            onFillermarkClicked = viewModel::fillermarkEpisode,
            onEpisodeClicked = {
                viewModel.showDialog(Dialogs.None)
                activity.changeEpisode(it)
            },
            onDismissRequest = { viewModel.showDialog(Dialogs.None) },
        )

        if (!toolsOnly) {
            BrightnessOverlay(brightness = currentBrightness)
        }
    }
}

fun <T> playerControlsExitAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = eu.kanade.presentation.motion.ModernMotion.EXIT_MILLIS,
    easing = FastOutSlowInEasing,
)

fun <T> playerControlsEnterAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = eu.kanade.presentation.motion.ModernMotion.RESIZE_MILLIS,
    easing = LinearOutSlowInEasing,
)

/** Playback ticks recompose the visible timeline, never the surrounding player and its sheets. */
@Composable
private fun PlayerTimeline(
    viewModel: PlayerViewModel,
    playerPreferences: PlayerPreferences,
    gesturePreferences: GesturePreferences,
    chapters: ImmutableList<Segment>,
) {
    val position by viewModel.timelinePosition.collectAsState()
    val seekPosition by viewModel.seekPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val gestureSeekAmount by viewModel.gestureSeekAmount.collectAsState()
    val isSeeking by viewModel.isSeeking.collectAsState()
    val invertDuration by playerPreferences.invertDuration().collectAsState()
    val readAhead by viewModel.readAhead.collectAsState()
    val preciseSeeking by gesturePreferences.playerSmoothSeek().collectAsState()
    SeekbarWithTimers(
        playerPosition = position,
        seekPosition = seekPosition,
        isGestureSeeking = gestureSeekAmount != null,
        isSeeking = isSeeking,
        duration = duration,
        readAheadValue = readAhead,
        onValueChange = {
            viewModel.updateSeekPos(it)
            viewModel.updateIsSeeking(true)
        },
        onValueChangeFinished = {
            viewModel.seekTo(it.toInt(), preciseSeeking)
            viewModel.updateIsSeeking(false)
        },
        timersInverted = Pair(false, invertDuration),
        durationTimerOnCLick = { playerPreferences.invertDuration().set(!invertDuration) },
        positionTimerOnClick = {},
        chapters = chapters,
        onValueChangeCancelled = { viewModel.updateIsSeeking(false) },
    )
}

@Composable
private fun PlayerSeekPreview(
    viewModel: PlayerViewModel,
    visible: Boolean,
    chapters: ImmutableList<Segment>,
    modifier: Modifier,
) {
    // Keep the preview's exit fade, but stop observing its clock once seeking finishes.
    val image = if (visible) viewModel.thumbnailImage.collectAsState().value else viewModel.thumbnailImage.value
    val position = if (visible) viewModel.seekPosition.collectAsState().value else viewModel.seekPosition.value
    val duration = if (visible) viewModel.duration.collectAsState().value else viewModel.duration.value
    ThumbnailPreview(visible, image, position.toLong(), duration.toLong(), chapters, modifier)
}
