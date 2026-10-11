// SPDX-License-Identifier: Apache-2.0
// Nyanime host adapter; derived UI remains GPL-3.0-only in flow/.
package it.lorelore.nyanime.presentation.player

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.lorelore.nyanime.presentation.player.flow.data.local.GestureOverlayStyle
import it.lorelore.nyanime.presentation.player.flow.data.local.PlayerOverlayPreferences
import it.lorelore.nyanime.presentation.player.flow.data.model.PlayerChapter
import it.lorelore.nyanime.presentation.player.flow.ui.components.shared.FlowBottomSheet
import it.lorelore.nyanime.presentation.player.flow.ui.components.shared.FlowNavRow
import it.lorelore.nyanime.presentation.player.flow.ui.components.shared.FlowRowGroup
import it.lorelore.nyanime.presentation.player.flow.ui.components.shared.FlowSectionHeader
import it.lorelore.nyanime.presentation.player.flow.ui.components.shared.FlowSheetHeader
import it.lorelore.nyanime.presentation.player.flow.ui.components.shared.flowRowGroupShape
import it.lorelore.nyanime.presentation.player.flow.ui.components.shared.rememberFlowBottomSheetState
import it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls.PlayerControlActions
import it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls.PlayerControlsOverlay
import it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls.PlayerControlsUiState
import it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls.PlayerTransportControls
import it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.info.ActionChip
import it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.overlay.BrightnessOverlay
import it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.overlay.SeekAnimationOverlay
import it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.overlay.SpeedBoostOverlay
import it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.overlay.VolumeOverlay
import it.lorelore.nyanime.presentation.player.flow.ui.theme.PlayerScrimContent

/** The exact FLOW overlay, including its timeline, lock UI, top bar and motion. */
@Composable
fun VideoPlaybackChrome(
    state: VideoChromeState,
    actions: VideoChromeActions,
    position: () -> Long,
    modifier: Modifier = Modifier,
    buffered: Float = 0f,
    reduceMotion: Boolean = false,
    windowInsets: WindowInsets = WindowInsets(0),
    center: (@Composable () -> Unit)? = null,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    VideoChromeTheme(reduceMotion) {
        CompositionLocalProvider(
            LocalHostSeekFeedback provides true,
            LocalHostSeekCancelled provides { actions.scrubbing(false) },
        ) {
            fun click(action: () -> Unit): () -> Unit = {
                actions.interaction()
                action()
            }
            val markers = remember(state.chapters) {
                state.chapters.map { chapter ->
                    PlayerChapter(chapter.title, (chapter.positionMs / 1000L).toInt())
                }
            }
            PlayerControlsOverlay(
                state = PlayerControlsUiState(
                    isVisible = state.visible,
                    isPlaying = state.playing,
                    isBuffering = state.buffering,
                    videoTitle = state.title,
                    channelName = state.subtitle,
                    duration = state.durationMs,
                    qualityLabel = state.quality,
                    playbackSpeed = state.speed,
                    resizeMode = state.resizeMode,
                    isFullscreen = state.fullscreen,
                    isPortraitFullscreen = state.portraitFullscreen,
                    isPipSupported = state.pip,
                    isLive = state.live,
                    hasPrevious = state.previous,
                    hasNext = state.next,
                    hasQueue = state.queue && !(state.fullscreen && state.portraitFullscreen),
                    chapters = markers,
                    isSubtitlesEnabled = state.subtitles,
                    autoplayEnabled = state.autoplay,
                    isSleepTimerActive = state.sleepTimer,
                    isTouchLocked = state.locked,
                    lockModeEnabled = state.fullscreen && !state.portraitFullscreen,
                    lockOverlayRevealSignal = if (state.visible) 1 else 0,
                    showRemainingTime = state.showRemainingTime,
                    isGestureReadoutActive = state.gestureActive,
                ),
                actions = PlayerControlActions(
                    onPlayPause = click(actions.playPause),
                    onPrevious = click(actions.previous),
                    onNext = click(actions.next),
                    onBack = click(actions.back),
                    onSettingsClick = click(actions.settings),
                    onFullscreenClick = click(actions.fullscreen),
                    onSeek = actions.seek,
                    onScrubbingChange = actions.scrubbing,
                    onQualityClick = click(actions.quality),
                    onSpeedClick = click(actions.speed),
                    onSubtitleClick = click(actions.subtitles),
                    onSubtitleLongClick = click(actions.subtitleSettings),
                    onPipClick = click(actions.pip),
                    onResizeClick = click(actions.resize),
                    onQueueClick = click(actions.queue),
                    onChapterClick = click(actions.chapters),
                    onDescriptionClick = click(actions.queue),
                    onCastClick = click(actions.cast),
                    onSleepTimerClick = click(actions.sleepTimer),
                    onTouchLockToggle = click(actions.lock),
                    onAutoplayToggle = actions.autoplay,
                    onToggleRemainingTime = click(actions.toggleRemainingTime),
                    onLiveClick = click(actions.live),
                ),
                currentPosition = position,
                modifier = modifier,
                bufferedPercentage = buffered.coerceIn(0f, 1f),
                windowInsets = windowInsets,
                hostPreferences = PlayerOverlayPreferences(
                    captionsEnabled = !state.live && !(state.fullscreen && state.portraitFullscreen),
                    pipEnabled = state.fullscreen && !state.portraitFullscreen,
                    autoplayEnabled = state.fullscreen && !state.portraitFullscreen && !state.live,
                    sleepTimerEnabled = state.fullscreen && !state.portraitFullscreen && !state.live,
                    speedIndicatorEnabled = !state.live,
                    commentsEnabled = false,
                    sponsorBlockEnabled = false,
                    showControlsWhileLoading = true,
                ),
                hostCenter = center,
                hostActions = extraActions,
                hostOnPreview = actions.preview,
                hostNavigation = !state.live || state.previous || state.next,
            )
        }
    }
}

@Composable
fun VideoChromeButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    selected: Boolean = false,
    selectedContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    description: String = label,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            contentColor = if (selected) selectedContentColor else PlayerScrimContent,
        ),
        modifier = modifier.size(40.dp).semantics {
            contentDescription = description
            this.selected = selected
        },
    ) {
        if (icon == null) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Icon(icon, null, Modifier.size(24.dp))
        }
    }
}

@Composable
fun VideoActionChip(icon: ImageVector, label: String, onClick: () -> Unit) = ActionChip(icon, label, onClick)

data class VideoSettingsAction(
    val title: String,
    val icon: ImageVector,
    val value: String? = null,
    val onClick: () -> Unit,
)

@Composable
fun VideoSettingsGroup(title: String, actions: List<VideoSettingsAction>) {
    FlowSectionHeader(title)
    FlowRowGroup {
        actions.forEachIndexed { index, action ->
            FlowNavRow(
                title = action.title,
                leadingIcon = action.icon,
                trailingText = action.value,
                onClick = action.onClick,
                shape = flowRowGroupShape(index, actions.size),
            )
        }
    }
}

@Composable
fun VideoPlayerSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissEvent: Boolean = false,
    title: String? = null,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    VideoChromeTheme(reduceMotion) {
        val state = rememberFlowBottomSheetState()
        LaunchedEffect(dismissEvent) { if (dismissEvent) state.dismiss() }
        FlowBottomSheet(
            onDismiss = onDismiss,
            modifier = modifier,
            state = state,
            motionEnabled = !reduceMotion,
            header = title?.let { heading ->
                { drag -> FlowSheetHeader(heading, onClose = { state.dismiss() }, modifier = drag) }
            },
        ) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun VideoChromeTheme(reduceMotion: Boolean, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        motionScheme = if (reduceMotion) StillVideoMotion else MotionScheme.expressive(),
        content = content,
    )
}

@Composable
fun VideoGestureFeedback(
    seekSeconds: Int,
    boostSpeed: Float?,
    volumeVisible: Boolean,
    brightnessVisible: Boolean,
    volume: () -> Float,
    brightness: () -> Float,
    reduceMotion: Boolean,
    swapSides: Boolean = false,
    maxVolumeLevel: Float = 2f,
) {
    VideoChromeTheme(reduceMotion) {
        // A zero amount starts the exit animation; it is not a new label.
        // Keep its payload until Flow's fixed-side indicator has faded away.
        var lastSeekSeconds by remember { mutableIntStateOf(0) }
        SideEffect { if (seekSeconds != 0) lastSeekSeconds = seekSeconds }
        val displayedSeekSeconds = if (seekSeconds != 0) seekSeconds else lastSeekSeconds
        Box(Modifier.fillMaxSize()) {
            if (!reduceMotion) {
                SeekAnimationOverlay(seekSeconds < 0, seekSeconds > 0, kotlin.math.abs(displayedSeekSeconds))
            } else if (seekSeconds != 0) {
                Text(
                    (if (seekSeconds > 0) "+" else "−") + kotlin.math.abs(seekSeconds) + " s",
                    Modifier.align(if (seekSeconds < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(32.dp),
                    color = PlayerScrimContent,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            SpeedBoostOverlay(
                boostSpeed != null,
                boostSpeed ?: 2f,
                Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            )
            VolumeOverlay(
                volumeVisible,
                volume,
                GestureOverlayStyle.VERTICAL,
                maxVolumeLevel = maxVolumeLevel,
                modifier = Modifier.align(if (swapSides) Alignment.CenterEnd else Alignment.CenterStart).padding(24.dp),
            )
            BrightnessOverlay(
                brightnessVisible,
                brightness,
                GestureOverlayStyle.VERTICAL,
                modifier = Modifier.align(if (swapSides) Alignment.CenterStart else Alignment.CenterEnd).padding(24.dp),
            )
        }
    }
}

private object StillVideoMotion : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

/** Gesture meaning stays recognisable regardless of the application's accent colour. */
internal object VideoGestureColors {
    val Volume = Color(0xFF81D4FA)
    val VolumeBoost = Color(0xFFCE93D8)
    val Brightness = Color(0xFFFFD54F)
    val BrightnessBoost = Color(0xFFFFB74D)
}

/** The same presentation for every engine, with no player or provider dependency in its caller. */
@Composable
fun VideoTransportControls(
    playing: Boolean,
    buffering: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    VideoChromeTheme(reduceMotion) {
        PlayerTransportControls(
            isPlaying = playing,
            hasEnded = false,
            showBufferingSpinner = buffering,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            showSkipButtons = true,
            showFrameStep = false,
            actions = PlayerControlActions(onPlayPause = onPlayPause, onPrevious = onPrevious, onNext = onNext),
            modifier = modifier,
        )
    }
}
