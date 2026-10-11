package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.discovery.ArtworkPlaceholder
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.ui.player.PlayerPlaybackCompletion
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

/** Uses playlist metadata only; resolving the next stream waits for the user's decision or deadline. */
@Composable
fun NextEpisodeCard(
    seriesTitle: String,
    episodeTitle: String,
    secondsRemaining: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    statusText: String? = null,
    showActions: Boolean = true,
    playEnabled: Boolean = true,
    artwork: @Composable () -> Unit = { ArtworkPlaceholder(Modifier.size(64.dp, 88.dp)) },
) {
    val modern = LocalNyanimeStyle.current
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    val progress = animateFloatAsState(
        targetValue = secondsRemaining.toFloat() / PlayerPlaybackCompletion.COUNTDOWN_SECONDS,
        animationSpec = tween(if (reduceMotion) 0 else 250),
        label = "next_episode_progress",
    )
    Surface(
        modifier = modifier.widthIn(max = if (modern) 420.dp else 480.dp).fillMaxWidth(),
        shape = RoundedCornerShape(if (modern) 8.dp else 24.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 8.dp,
    ) {
        BoxWithConstraints {
            // In the watch page the outer list owns scrolling; fullscreen overlays provide
            // a finite height and let this card scroll when large text needs extra space.
            val scrollModifier = if (constraints.hasBoundedHeight) Modifier.verticalScroll(scrollState) else Modifier
            Column(
                Modifier
                    .background(
                        Brush.linearGradient(
                            listOf(
                                if (modern) colors.surfaceContainerHigh else colors.primary.copy(alpha = 0.09f),
                                colors.surfaceContainer,
                            ),
                        ),
                    )
                    .then(scrollModifier)
                    .padding(if (modern) 14.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (modern) 10.dp else 16.dp),
            ) {
                val heading: @Composable (Modifier) -> Unit = { headingModifier ->
                    Text(
                        stringResource(AYMR.strings.player_up_next),
                        modifier = headingModifier,
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                val countdown: @Composable () -> Unit = {
                    Text(
                        statusText ?: stringResource(AYMR.strings.player_up_next_countdown, secondsRemaining),
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = colors.primary,
                    )
                }
                if (statusText != null || LocalDensity.current.fontScale > 1.3f) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        heading(Modifier)
                        countdown()
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        heading(Modifier.weight(1f).padding(end = 12.dp))
                        countdown()
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (modern) 12.dp else 16.dp),
                ) {
                    Box(
                        Modifier.size(if (modern) 52.dp else 64.dp, if (modern) 72.dp else 88.dp)
                            .clip(RoundedCornerShape(if (modern) 5.dp else 10.dp)),
                    ) { artwork() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            seriesTitle,
                            style = if (modern) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            episodeTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { if (statusText == null) progress.value.coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(if (modern) 2.dp else 3.dp),
                    trackColor = colors.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
                val playButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    Button(
                        onClick = onPlayNow,
                        enabled = playEnabled,
                        modifier = buttonModifier.heightIn(min = 48.dp),
                        shape = if (modern) {
                            RoundedCornerShape(
                                6.dp,
                            )
                        } else {
                            androidx.compose.material3.ButtonDefaults.shape
                        },
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.padding(end = 6.dp).size(20.dp))
                        Text(stringResource(AYMR.strings.player_up_next_play_now))
                    }
                }
                val cancelButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    TextButton(onClick = onCancel, modifier = buttonModifier.heightIn(min = 48.dp)) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                }
                if (!showActions) return@Column
                if (LocalDensity.current.fontScale > 1.3f) {
                    playButton(Modifier.fillMaxWidth())
                    cancelButton(Modifier.fillMaxWidth())
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cancelButton(Modifier.weight(1f))
                        playButton(Modifier.weight(1.4f))
                    }
                }
            }
        }
    }
}
