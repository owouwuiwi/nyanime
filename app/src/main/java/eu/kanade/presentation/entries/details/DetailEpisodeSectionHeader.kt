package eu.kanade.presentation.entries.details

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R

@Composable
internal fun DetailEpisodeSectionHeader(
    section: DetailEpisodeSection,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        if (expanded) 90f else 0f,
        tween(if (modernMotionEnabled()) ModernMotion.EXIT_MILLIS else 0),
        label = "season-chevron",
    )
    val state = stringResource(if (expanded) R.string.episode_section_expanded else R.string.episode_section_collapsed)
    Surface(
        onClick = if (section.directOpen) onOpen else onToggle,
        modifier = modifier.padding(top = 14.dp, bottom = 6.dp).semantics { stateDescription = state },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .85f),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        section.total?.let {
                            if (it > 0) {
                                stringResource(R.string.episode_section_progress, section.completed, it)
                            } else {
                                pluralStringResource(R.plurals.episode_section_count, it, it)
                            }
                        } ?: stringResource(R.string.episode_section_not_loaded),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (section.canOpen) {
                    IconButton(onClick = onOpen) {
                        Icon(
                            TitleDetailsIcons.Info,
                            stringResource(R.string.episode_section_open),
                            Modifier.size(20.dp),
                        )
                    }
                }
                Icon(TitleDetailsIcons.Next, null, Modifier.size(22.dp).graphicsLayer { rotationZ = rotation })
            }
            section.total?.takeIf { it > 0 && section.completed > 0 }?.let { total ->
                LinearProgressIndicator(
                    progress = { (section.completed.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp).height(3.dp),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}
