package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.tachiyomi.R

internal val LocalPlaybackControlMotion = staticCompositionLocalOf { false }

/** Animate only the visual layer; the control's existing hit area and layout stay fixed. */
@Composable
internal fun Modifier.playbackPressFeedback(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val enabled = LocalPlaybackControlMotion.current
    val scale = animateFloatAsState(
        if (enabled && pressed) 0.96f else 1f,
        ModernMotion.controlResponse(enabled),
        label = "playback_control_press",
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
internal fun PlaybackControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick, modifier, enabled, interactionSource = interaction) {
        Box(Modifier.playbackPressFeedback(interaction), contentAlignment = Alignment.Center) { content() }
    }
}

/** The same reversible vector morph in page, fullscreen and room controls. */
@Composable
internal fun PlaybackPlayPauseGlyph(
    paused: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (reduceMotion) {
        Icon(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription,
            modifier,
            tint = Color.White,
        )
    } else {
        val icon = AnimatedImageVector.animatedVectorResource(R.drawable.anim_play_to_pause)
        Image(rememberAnimatedVectorPainter(icon, !paused), contentDescription, modifier)
    }
}
