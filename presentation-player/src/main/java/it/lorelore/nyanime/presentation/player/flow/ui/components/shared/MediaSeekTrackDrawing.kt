// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

package it.lorelore.nyanime.presentation.player.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import it.lorelore.nyanime.presentation.player.flow.data.model.PlayerChapter
import it.lorelore.nyanime.presentation.player.flow.data.model.TimelineSegment
import it.lorelore.nyanime.presentation.player.flow.ui.theme.TIMELINE_SEGMENT_ALPHA
import it.lorelore.nyanime.presentation.player.flow.ui.theme.defaultTimelineColor

internal val RestTrackHeight = 5.dp
internal val ActiveTrackHeight = 10.dp

internal fun chapterFractions(chapters: List<PlayerChapter>, duration: Long): List<Float> {
    if (duration <= 0 || chapters.isEmpty()) return emptyList()
    return chapters.asSequence()
        .map { it.startTimeSeconds }
        .filter { it > 0 }
        .map { it * 1000f / duration }
        .filter { it in 0f..1f }
        .sorted()
        .toList()
}

/**
 * Paints the track: base, buffered, progress, chapter gaps and SponsorBlock segments.
 *
 * Shared by both shapes so their appearance cannot drift apart — the earlier version drew the same
 * thing twice, once in a sibling Canvas and once for the locked variant.
 */
@Composable
internal fun Modifier.seekTrack(
    fraction: () -> Float,
    expansion: () -> Float,
    chapterFractions: List<Float>,
    sponsorSegments: List<TimelineSegment>,
    sponsorColors: Map<String, Color>,
    duration: Long,
    bufferedValue: () -> Float,
    colors: SeekTrackColors,
    bottomAligned: Boolean,
): Modifier = drawWithCache {
    val expanded = expansion()
    val trackHeightPx = lerp(RestTrackHeight.toPx(), ActiveTrackHeight.toPx(), expanded)
    val width = size.width
    val trackTop =
        if (bottomAligned) {
            size.height - trackHeightPx
        } else {
            (size.height - trackHeightPx) / 2f
        }
    val trackBottom = trackTop + trackHeightPx
    val capRadius = CornerRadius(trackHeightPx / 2f)

    val gapWidth = lerp(2.dp.toPx(), 3.dp.toPx(), expanded)
    val segments =
        buildList {
            var segStart = 0f
            for (chapter in chapterFractions) {
                val boundary = chapter * width
                val segEnd = boundary - gapWidth / 2f
                if (segEnd > segStart) {
                    add(segStart to segEnd)
                }
                segStart = (boundary + gapWidth / 2f).coerceAtMost(width)
            }
            if (width > segStart) {
                add(segStart to width)
            }
        }

    val trackPath =
        Path().apply {
            segments.forEachIndexed { index, (segStart, segEnd) ->
                val startRadius = if (index == 0) capRadius else CornerRadius.Zero
                val endRadius = if (index == segments.lastIndex) capRadius else CornerRadius.Zero
                addRoundRect(
                    RoundRect(
                        rect = Rect(segStart, trackTop, segEnd, trackBottom),
                        topLeft = startRadius,
                        topRight = endRadius,
                        bottomRight = endRadius,
                        bottomLeft = startRadius,
                    ),
                )
            }
        }

    val sponsorRects = if (duration > 0) {
        sponsorSegments.mapNotNull { segment ->
            val start = (segment.startTime * 1000f / duration).coerceIn(0f, 1f)
            val end = (segment.endTime * 1000f / duration).coerceIn(0f, 1f)
            if (end > start) {
                val color = sponsorColors[segment.category] ?: defaultTimelineColor(segment.category)
                Rect(start * width, trackTop, end * width, trackBottom) to color.copy(alpha = TIMELINE_SEGMENT_ALPHA)
            } else {
                null
            }
        }
    } else {
        emptyList()
    }

    // Playhead and buffer updates only repaint. The track outline and chapter gaps stay cached.
    onDrawBehind {
        val progress = fraction()
        val buffered = bufferedValue()
        clipPath(trackPath) {
            drawRect(
                color = colors.track,
                topLeft = Offset(0f, trackTop),
                size = Size(width, trackHeightPx),
            )

            if (buffered > 0f) {
                drawRect(
                    color = colors.buffered,
                    topLeft = Offset(0f, trackTop),
                    size = Size(width * buffered.coerceIn(0f, 1f), trackHeightPx),
                )
            }

            drawRect(
                color = colors.active,
                topLeft = Offset(0f, trackTop),
                size = Size(width * progress, trackHeightPx),
            )

            // Drawn above progress so a segment stays visible after playback passes it.
            if (duration > 0) {
                sponsorRects.forEach { (rect, color) ->
                    drawRect(color = color, topLeft = rect.topLeft, size = rect.size)
                }

                val currentTimeSeconds = progress.coerceIn(0f, 1f) * duration / 1000f
                val isInsideSponsorSegment =
                    sponsorSegments.any { segment ->
                        currentTimeSeconds >= segment.startTime && currentTimeSeconds < segment.endTime
                    }
                if (isInsideSponsorSegment) {
                    val playheadX = width * progress.coerceIn(0f, 1f)
                    val outerWidth = minOf(4.dp.toPx(), width)
                    val innerWidth = minOf(2.dp.toPx(), width)
                    drawRect(
                        color = colors.playhead,
                        topLeft =
                        Offset(
                            x = (playheadX - outerWidth / 2f).coerceIn(0f, width - outerWidth),
                            y = trackTop,
                        ),
                        size = Size(outerWidth, trackHeightPx),
                    )
                    drawRect(
                        color = colors.active,
                        topLeft =
                        Offset(
                            x = (playheadX - innerWidth / 2f).coerceIn(0f, width - innerWidth),
                            y = trackTop,
                        ),
                        size = Size(innerWidth, trackHeightPx),
                    )
                }
            }
        }
    }
}
