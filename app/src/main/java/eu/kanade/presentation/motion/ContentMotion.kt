package eu.kanade.presentation.motion

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.theme.LocalNyanimeStyle

/** Bounded resizing avoids spring overshoot when a sheet or asynchronous section changes height. */
@Composable
internal fun Modifier.animateModernContentSize(): Modifier = if (LocalNyanimeStyle.current) {
    animateContentSize(
        tween(if (modernMotionEnabled()) ModernMotion.RESIZE_MILLIS else 0, easing = FastOutSlowInEasing),
    )
} else {
    animateContentSize()
}
