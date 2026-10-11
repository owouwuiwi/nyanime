package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/** Preserve Cloud's existing presentation without changing motion in the rest of the app. */
internal object CloudMotion {
    const val PAGE_MILLIS = 280
    const val RESIZE_MILLIS = 240
    const val SURFACE_ENTER_MILLIS = 250
    const val SURFACE_EXIT_MILLIS = 150
    const val SURFACE_SCALE = 0.96f
    val SmoothOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    fun duration(enabled: Boolean, millis: Int) = if (enabled) millis else 0
    fun <T> resize(enabled: Boolean) = tween<T>(duration(enabled, RESIZE_MILLIS), easing = SmoothOut)
    fun enter(duration: Int) = fadeIn(tween(duration, easing = SmoothOut))
    fun exit(duration: Int) = fadeOut(tween(duration, easing = SmoothOut))
}
