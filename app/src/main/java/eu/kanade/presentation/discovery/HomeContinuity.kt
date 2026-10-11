package eu.kanade.presentation.discovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import eu.kanade.presentation.theme.NyanimeGlassHeader
import eu.kanade.presentation.theme.nyanimeBackdropSource

/** Captures Home content only; never observes scroll or includes the sharp header controls. */
@Stable
class HomeContinuityState {
    val backdrop = HazeState()
}

val LocalHomeContinuity = compositionLocalOf<HomeContinuityState?> { null }
val LocalHomeLogoInset = compositionLocalOf { 0.dp }

/** Initial space belongs to the scrolling content, so it can subsequently pass under the header. */
val LocalHomeContentInset = compositionLocalOf { 0.dp }

@Composable
fun Modifier.homeContinuitySource(state: HomeContinuityState): Modifier = nyanimeBackdropSource(state.backdrop)

@Composable
fun HomeContinuityHeader(state: HomeContinuityState, logoInset: Dp, content: @Composable () -> Unit) {
    NyanimeGlassHeader(state.backdrop, topInset = logoInset, sharedTransitionOverlay = true, content = content)
}
