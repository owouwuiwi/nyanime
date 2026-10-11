// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

package it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import it.lorelore.nyanime.presentation.player.flow.data.model.PlayerChapter
import it.lorelore.nyanime.presentation.player.flow.data.model.TimelineSegment
import it.lorelore.nyanime.presentation.player.flow.ui.components.shared.MediaSeekBar
@Immutable
data class PlayerSeekbarContent(
    val chapters: List<PlayerChapter> = emptyList(),
    val sponsorSegments: List<TimelineSegment> = emptyList(),
    val sponsorColors: Map<String, Color> = emptyMap(),
    val bufferedPercentage: Float = 0f,
)

/** Host owns the persistent scrub card and thumbnails; this row paints only the timeline. */
@Composable
internal fun PlayerSeekbarRow(
    positionProvider: () -> Long,
    duration: Long,
    isLive: Boolean,
    content: PlayerSeekbarContent,
    edgeAligned: Boolean,
    horizontalPadding: Dp,
    onScrubProgress: (Float, Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
    seekbarZIndex: Float = 0f,
    isScrubbing: Boolean = false,
) {
    val seekDuration = if (isLive) duration.coerceAtLeast(positionProvider()) else duration
    MediaSeekBar(
        value = { if (seekDuration > 0) (positionProvider().toFloat() / seekDuration).coerceIn(0f, 1f) else 0f },
        onValueChange = { onScrubProgress(it, seekDuration) }, onValueChangeFinished = onScrubFinished,
        chapters = content.chapters, sponsorSegments = content.sponsorSegments,
        sponsorColors = content.sponsorColors, duration = seekDuration,
        bufferedValue = content.bufferedPercentage, edgeAligned = edgeAligned,
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding).zIndex(seekbarZIndex),
    )
}
