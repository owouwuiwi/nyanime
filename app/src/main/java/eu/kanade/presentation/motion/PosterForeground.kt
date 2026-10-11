package eu.kanade.presentation.motion

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.presentation.theme.LocalNyanimeStyle

/**
 * Shared artwork escapes the page's alpha and is drawn over ordinary siblings. Decorations must
 * occupy the same overlay, with their own enter/exit animation, before returning to normal drawing.
 * Only the selected card is elevated; unrelated rows must never cover the travelling poster.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.posterForeground(
    source: PosterSource? = null,
    zIndex: Float = 2f,
    fadeOnEnter: Boolean = true,
): Modifier {
    if (!LocalNyanimeStyle.current) return this
    val scene = LocalPosterScene.current ?: return this
    val active = scene.active ?: return this
    if (!scene.enabled || source != null && (active.origin != scene.route || active.element != source.element)) {
        return this
    }
    return with(scene.shared) {
        with(scene.visibility) {
            this@posterForeground
                .renderInSharedTransitionScopeOverlay(zIndexInOverlay = zIndex)
                .animateEnterExit(
                    enter = if (fadeOnEnter) ModernMotion.enter(POSTER_TRANSITION_MILLIS) else EnterTransition.None,
                    exit = ModernMotion.exit(),
                    label = "poster_foreground",
                )
        }
    }
}

/** Decorations inside travelling artwork keep the image's animated bounds and clipping. */
@Composable
internal fun Modifier.posterInlineForeground(): Modifier {
    val scene = LocalPosterScene.current ?: return this
    if (!scene.enabled || scene.active == null) return this
    return with(scene.visibility) {
        this@posterInlineForeground.animateEnterExit(
            enter = ModernMotion.enter(POSTER_TRANSITION_MILLIS),
            exit = ModernMotion.exit(),
            label = "poster_inline_foreground",
        )
    }
}

/** Backdrop effects draw their own layers and need the page opacity explicitly. */
@Composable
internal fun posterBackdropOpacity(): State<Float> {
    val scene = LocalPosterScene.current
    if (scene?.enabled != true) return remember { mutableStateOf(1f) }
    return scene.visibility.transition.animateFloat(
        transitionSpec = { tween(if (targetState == EnterExitState.PostExit) ModernMotion.EXIT_MILLIS else 0) },
        label = "poster_backdrop_opacity",
    ) { if (it == EnterExitState.PostExit) 0f else 1f }
}
