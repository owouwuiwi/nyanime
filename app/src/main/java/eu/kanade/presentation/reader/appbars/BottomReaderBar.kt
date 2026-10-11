package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R

/** Chrome only: actions never own or replace the page viewer. */
@Composable
internal fun BottomReaderBar(
    onChapters: () -> Unit,
    onMode: () -> Unit,
    onQuality: () -> Unit,
    onMore: () -> Unit,
    qualityComparison: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth / LocalDensity.current.fontScale < 230.dp) 2 else 4
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            maxItemsInEachRow = columns,
        ) {
            ReaderDockAction(
                stringResource(R.string.reader_chapters),
                Icons.Outlined.MenuBook,
                onChapters,
                Modifier.weight(1f).testTag("reader_chapters_button"),
            )
            ReaderDockAction(
                stringResource(R.string.reader_mode),
                Icons.Outlined.Tune,
                onMode,
                Modifier.weight(1f).testTag("reader_mode_button"),
            )
            ReaderDockAction(
                stringResource(if (qualityComparison) R.string.reader_quality_original else R.string.reader_quality),
                if (qualityComparison) Icons.Outlined.Compare else Icons.Outlined.AutoAwesome,
                onQuality,
                Modifier.weight(1f).testTag("reader_quality_button"),
            )
            ReaderDockAction(
                stringResource(R.string.reader_more),
                Icons.Outlined.MoreHoriz,
                onMore,
                Modifier.weight(1f).testTag("reader_more_button"),
            )
        }
    }
}

@Composable
private fun ReaderDockAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .94f else 1f,
        ModernMotion.controlResponse(modernMotionEnabled()),
        label = "reader-control",
    )
    Column(
        modifier = modifier
            .heightIn(min = 58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
