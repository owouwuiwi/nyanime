package eu.kanade.presentation.discovery

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Real cards and their loading placeholders must share the same layout policy. */
internal object HomeLayout {
    fun stackHeroActions(width: Dp, fontScale: Float, sourceAction: Boolean): Boolean {
        val primaryWidth = 128.dp * fontScale.coerceAtLeast(1f)
        val secondaryWidth = if (sourceAction) 112.dp else 56.dp
        return width < primaryWidth + secondaryWidth
    }

    fun heroHeight(width: Dp, fontScale: Float = 1f): Dp {
        val base = if (width < 600.dp) (width * 0.94f).coerceIn(360.dp, 430.dp) else 400.dp
        return base + (72 * (fontScale - 1f).coerceIn(0f, 1f)).dp
    }

    fun posterWidth(fontScale: Float): Dp = (132 * fontScale.coerceIn(1f, 1.5f)).dp

    fun resumeWidth(fontScale: Float, viewport: Dp): Dp = (228 * fontScale.coerceIn(1f, 1.4f)).dp
        .coerceAtMost((viewport - 32.dp).coerceAtLeast(160.dp))
}
