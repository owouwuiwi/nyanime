package eu.kanade.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import eu.kanade.presentation.motion.posterBackdropOpacity
import eu.kanade.presentation.motion.posterForeground

/** Adapts the app's poster navigation transition to the visual header. */
@Composable
internal fun NyanimeGlassHeader(
    state: HazeState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    sharedTransitionOverlay: Boolean = false,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    atmosphereColor: Color = nyanimeBrandColors.atmosphere,
    content: @Composable () -> Unit,
) {
    val controls = if (sharedTransitionOverlay) Modifier.posterForeground(zIndex = 2.5f) else Modifier
    val backdrop = if (sharedTransitionOverlay) {
        Modifier.posterForeground(zIndex = 2.4f, fadeOnEnter = false)
    } else {
        Modifier
    }
    val opacity = posterBackdropOpacity()
    GlassHeader(
        state = state,
        modifier = modifier,
        topInset = topInset,
        controlsOverlay = controls,
        backdropOverlay = backdrop,
        backdropOpacity = { opacity.value },
        backgroundColor = backgroundColor,
        atmosphereColor = atmosphereColor,
        content = content,
    )
}
