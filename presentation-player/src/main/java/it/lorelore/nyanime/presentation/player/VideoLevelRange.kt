// SPDX-License-Identifier: Apache-2.0
package it.lorelore.nyanime.presentation.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection

/** The normal 0–100% fill stays full while amplification fills a second pass over it. */
@JvmInline
value class VideoLevelRange internal constructor(val level: Float) {
    val fraction: Float get() = level.coerceIn(0f, 1f)
    val boostFraction: Float get() = (level - 1f).coerceIn(0f, 1f)
}

fun videoLevelRange(level: Float, maximum: Float): VideoLevelRange {
    val ceiling = maximum.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val finiteLevel = level.takeIf { it.isFinite() } ?: 0f
    return VideoLevelRange(finiteLevel.coerceIn(0f, ceiling))
}

/**
 * Tint only the filled amplification segment of the native M3 indicator. The library still owns
 * its shape, wave phase, animation, track and accessibility; no second wave or animation is created.
 */
internal fun Modifier.videoLevelSegments(
    progress: () -> Float,
    boostProgress: () -> Float,
    boostColor: Color?,
    circular: Boolean = false,
): Modifier {
    if (boostColor == null) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val end = minOf(progress().coerceIn(0f, 1f), boostProgress().coerceIn(0f, 1f))
        if (end > 0f) {
            if (circular) {
                val segment = Path().apply {
                    moveTo(size.width / 2f, size.height / 2f)
                    arcTo(Rect(Offset.Zero, size), 270f, end * 360f, false)
                    close()
                }
                clipPath(segment) { drawRect(boostColor, blendMode = BlendMode.SrcAtop) }
            } else {
                val left = if (layoutDirection == LayoutDirection.Rtl) 1f - end else 0f
                val right = if (layoutDirection == LayoutDirection.Rtl) 1f else end
                clipRect(left = left * size.width, right = right * size.width) {
                    drawRect(boostColor, blendMode = BlendMode.SrcAtop)
                }
            }
        }
    }
}
