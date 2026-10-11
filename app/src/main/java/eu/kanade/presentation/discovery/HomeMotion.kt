package eu.kanade.presentation.discovery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.motion.posterNavigationRunning

internal object HomeMotion {
    const val CONTENT_MILLIS = 220
    const val RESIZE_MILLIS = 240
    const val PULSE_HALF_MILLIS = 1100
}

internal val LocalHomeContentActive = compositionLocalOf { true }

/** Only the body fades. The header, its layout and its remembered state stay outside this scope. */
@Composable
internal fun <T> HomeCategoryTransition(
    page: T,
    modifier: Modifier = Modifier,
    content: @Composable (T, Boolean) -> Unit,
) {
    val motion = appMotionEnabled() && !posterNavigationRunning()
    val tabActive = LocalHomeContentActive.current
    Crossfade(
        targetState = page,
        modifier = modifier,
        animationSpec = tween(if (motion) HomeMotion.CONTENT_MILLIS else 0),
        label = "homeCategoryContent",
    ) { displayed ->
        val active = tabActive && displayed == page
        val inactive = if (active) {
            Modifier
        } else {
            Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
        }
        CompositionLocalProvider(LocalHomeContentActive provides active) {
            Box(Modifier.fillMaxSize().zIndex(if (active) 1f else 0f).then(inactive)) {
                content(displayed, active)
            }
        }
    }
}

/** The skeleton and the loaded section exchange opacity while any height change is interpolated. */
@Composable
fun HomeLoadingTransition(
    loading: Boolean,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val motion = appMotionEnabled() && !posterNavigationRunning()
    AnimatedContent(
        targetState = loading,
        modifier = modifier,
        transitionSpec = {
            (
                fadeIn(tween(if (motion) HomeMotion.CONTENT_MILLIS else 0)) togetherWith
                    fadeOut(tween(if (motion) HomeMotion.CONTENT_MILLIS else 0))
                ).using(
                SizeTransform(clip = false) { _, _ -> tween(if (motion) HomeMotion.RESIZE_MILLIS else 0) },
            )
        },
        label = "homeLoadingContent",
    ) { waiting ->
        if (waiting) placeholder() else content()
    }
}
