package eu.kanade.presentation.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith

/** One timing contract for a page and everything lifted out of that page into the poster overlay. */
internal object ModernMotion {
    const val PAGE_MILLIS = 280
    const val EXIT_MILLIS = 180
    const val RESIZE_MILLIS = 240

    // Native adaptation of transitions.dev card-resize: one transform clock, smooth-out easing.
    const val CONTINUOUS_RESIZE_MILLIS = 300
    val SmoothOutEasing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** Small controls retain their current velocity when a press or morph reverses. */
    fun controlResponse(enabled: Boolean): FiniteAnimationSpec<Float> = if (enabled) {
        spring(dampingRatio = 0.88f, stiffness = 650f)
    } else {
        tween(0)
    }

    fun enter(duration: Int = PAGE_MILLIS) = fadeIn(tween(duration, easing = LinearOutSlowInEasing))
    fun exit() = fadeOut(tween(EXIT_MILLIS, easing = FastOutSlowInEasing))
    fun transform(enabled: Boolean, duration: Int = PAGE_MILLIS) = if (enabled) {
        enter(duration) togetherWith exit()
    } else {
        EnterTransition.None togetherWith ExitTransition.None
    }
}
