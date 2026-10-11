package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.player.PlaybackScrubCoordinator
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.delay
import kotlin.math.abs

private class LastScrubFrame {
    var state: PlaybackScrubCoordinator.State? = null
    var image: ImageBitmap? = null
    var thumbnailExpected = false
}

/** Only this small leaf observes pointer updates; the surrounding player does not recompose per pixel. */
@Composable
internal fun PlayerScrubFeedback(
    model: PlayerViewModel,
    visible: Boolean,
    compact: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier,
) {
    val state by model.scrubState.collectAsState()
    val image = if (state != null) model.thumbnailImage.collectAsState().value else null
    PlaybackScrubFeedback(state.takeIf { visible }, image, model.hasSeekThumbnails, compact, reduceMotion, modifier)
}

/** Native playback stays paused; this readout never depends on artwork or controls visibility. */
@Composable
internal fun PlaybackScrubFeedback(
    state: PlaybackScrubCoordinator.State?,
    image: ImageBitmap?,
    thumbnailExpected: Boolean,
    compact: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val last = remember { LastScrubFrame() }
    val reserveThumbnail = remember(state?.identity, state?.origin) { thumbnailExpected } || image != null
    if (state != null) {
        last.state = state
        last.image = image
        last.thumbnailExpected = reserveThumbnail
    }
    LaunchedEffect(state == null, reduceMotion) {
        if (state == null) {
            if (!reduceMotion) delay(ModernMotion.EXIT_MILLIS.toLong())
            last.image = null
            last.state = null
        }
    }
    val shown = state ?: last.state ?: return
    val target = Utils.prettyTime(shown.target.toInt())
    val delta = (if (shown.delta < 0) "−" else "+") + Utils.prettyTime(abs(shown.delta).toInt())
    val description = stringResource(R.string.playback_scrub_description, target, delta)
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(tween(if (reduceMotion) 0 else ModernMotion.EXIT_MILLIS)),
        exit = fadeOut(tween(if (reduceMotion) 0 else ModernMotion.EXIT_MILLIS)),
        modifier = modifier,
    ) {
        Surface(
            color = Color(0xFF121722).copy(alpha = .96f),
            contentColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.widthIn(max = 320.dp).animateContentSize(
                tween(if (reduceMotion) 0 else ModernMotion.RESIZE_MILLIS, easing = ModernMotion.SmoothOutEasing),
                alignment = Alignment.CenterEnd,
            ).semantics(mergeDescendants = true) {
                contentDescription = description
            },
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (last.thumbnailExpected && LocalDensity.current.fontScale <= 1.3f) {
                    Box(
                        Modifier.size(if (compact) 80.dp else 112.dp, if (compact) 45.dp else 63.dp)
                            .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .5f)),
                    ) {
                        last.image?.let {
                            Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        target,
                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        delta,
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = Color.White.copy(alpha = .78f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
