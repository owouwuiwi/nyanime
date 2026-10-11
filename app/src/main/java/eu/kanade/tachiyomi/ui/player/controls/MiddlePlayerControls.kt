package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.WatchRecovery
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.data.watch.recovery
import eu.kanade.tachiyomi.data.watch.showPreparationFeedback
import eu.kanade.tachiyomi.ui.player.PlaybackFailure
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.PlaybackErrorControls
import eu.kanade.tachiyomi.ui.player.controls.components.PlaybackPlayPauseGlyph
import eu.kanade.tachiyomi.ui.player.controls.components.TogetherLoadingGlyph
import eu.kanade.tachiyomi.ui.player.controls.components.WatchPlaybackButton
import eu.kanade.tachiyomi.ui.player.controls.components.playbackPressFeedback
import `is`.xyz.mpv.Utils
import it.lorelore.nyanime.presentation.player.VideoTransportControls
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs

@Composable
fun MiddlePlayerControls(
    // previous
    hasPrevious: Boolean,
    onSkipPrevious: () -> Unit,

    // middle
    isLoading: Boolean,
    isLoadingEpisode: Boolean,
    controlsShown: Boolean,
    areControlsLocked: Boolean,
    showLoadingCircle: Boolean,
    paused: Boolean,
    gestureSeekAmount: Pair<Int, Int>?,
    onPlayPauseClick: () -> Unit,

    // next
    hasNext: Boolean,
    onSkipNext: () -> Unit,

    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
    failure: PlaybackFailure? = null,
    onRetry: () -> Unit = {},
    onOpenSource: (() -> Unit)? = null,
    watchRoom: WatchRoomState = WatchRoomState(),
    reduceMotion: Boolean = false,
    onWatchRetry: () -> Unit = {},
) {
    if (failure == null && gestureSeekAmount == null && !watchRoom.active) {
        AnimatedVisibility(visible = controlsShown && !areControlsLocked, enter = enter, exit = exit) {
            VideoTransportControls(
                playing = !paused,
                buffering = (isLoading || isLoadingEpisode) && showLoadingCircle,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onPlayPause = onPlayPauseClick,
                onPrevious = onSkipPrevious,
                onNext = onSkipNext,
                modifier = modifier,
                reduceMotion = reduceMotion,
            )
        }
        return
    }
    val roomActionOverlay = !controlsShown &&
        watchRoom.activity?.let { it.actorId != watchRoom.localMemberId } == true
    val compactLargeText = LocalConfiguration.current.screenHeightDp < 360 && LocalDensity.current.fontScale > 1.3f
    val sharedBusy = watchRoom.active &&
        (
            watchRoom.showPreparationFeedback ||
                watchRoom.resumeSeconds != null ||
                isLoading ||
                isLoadingEpisode ||
                watchRoom.recovery != WatchRecovery.None
            )
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.large),
    ) {
        AnimatedVisibility(
            visible = controlsShown && !areControlsLocked && !sharedBusy,
            enter = enter,
            exit = exit,
        ) {
            if (gestureSeekAmount == null) {
                ControlsButton(
                    Icons.Filled.SkipPrevious,
                    onClick = onSkipPrevious,
                    iconSize = 48.dp,
                    enabled = hasPrevious,
                )
            }
        }

        val interaction = remember { MutableInteractionSource() }
        when {
            failure != null -> PlaybackErrorControls(failure, onRetry, onOpenSource)
            gestureSeekAmount != null -> {
                Text(
                    stringResource(
                        AYMR.strings.player_gesture_seek_indicator,
                        if (gestureSeekAmount.second >= 0) '+' else '-',
                        Utils.prettyTime(abs(gestureSeekAmount.second)),
                        Utils.prettyTime(gestureSeekAmount.first + gestureSeekAmount.second),
                    ),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        shadow = Shadow(Color.Black, blurRadius = 5f),
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            watchRoom.active -> AnimatedVisibility(
                visible = controlsShown && !areControlsLocked || sharedBusy,
                enter = enter,
                exit = exit,
            ) {
                WatchPlaybackButton(
                    room = watchRoom,
                    loading = isLoading || isLoadingEpisode,
                    paused = paused,
                    enabled =
                    !areControlsLocked &&
                        (
                            watchRoom.host ||
                                watchRoom.sharedControls ||
                                watchRoom.localHold ||
                                watchRoom.recovery != WatchRecovery.None
                            ),
                    reduceMotion = reduceMotion,
                    onClick = onPlayPauseClick,
                    onRetry = onWatchRetry,
                    showParticipants = !roomActionOverlay && (!controlsShown || !compactLargeText),
                )
            }
            (isLoading || isLoadingEpisode) && showLoadingCircle -> {
                if (LocalNyanimeStyle.current) {
                    TogetherLoadingGlyph(reduceMotion, shared = false)
                } else {
                    CircularProgressIndicator(Modifier.size(96.dp))
                }
            }
            else -> {
                AnimatedVisibility(
                    visible = controlsShown && !areControlsLocked,
                    enter = enter,
                    exit = exit,
                ) {
                    PlaybackPlayPauseGlyph(
                        paused = paused,
                        reduceMotion = reduceMotion,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .clickable(
                                interaction,
                                ripple(),
                                onClick = onPlayPauseClick,
                            )
                            .playbackPressFeedback(interaction)
                            .padding(MaterialTheme.padding.medium),
                        contentDescription = androidx.compose.ui.res.stringResource(
                            if (paused) R.string.playback_play else R.string.playback_pause,
                        ),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsShown && !areControlsLocked && !sharedBusy,
            enter = enter,
            exit = exit,
        ) {
            if (gestureSeekAmount == null) {
                ControlsButton(
                    Icons.Filled.SkipNext,
                    onClick = onSkipNext,
                    iconSize = 48.dp,
                    enabled = hasNext,
                )
            }
        }
    }
}
