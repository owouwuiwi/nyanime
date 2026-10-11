package eu.kanade.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** A compact catalog link; touching the body opens its destination. */
@Composable
internal fun ContinuityCard(
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
    preview: @Composable ColumnScope.() -> Unit = {},
) {
    val motion = modernMotionEnabled()
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize(tween(if (motion) ModernMotion.RESIZE_MILLIS else 0)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), Color.Transparent),
                ),
            ).clickable(onClick = onOpen).fillMaxWidth().heightIn(min = 76.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            AnimatedContent(
                targetState = status,
                transitionSpec = { ModernMotion.transform(motion) },
                label = "continuityStatus",
            ) { message ->
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
            preview()
        }
    }
}

/** A failed automatic match never silently turns into a title-based association. */
@Composable
internal fun ContinuityMissingCopySheet(onSearch: () -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(MR.strings.continuity_missing_copy), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(MR.strings.continuity_missing_copy_details),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(MR.strings.action_search))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(MR.strings.action_cancel))
            }
        }
    }
}

internal data class ContinuityDestination(
    val title: String,
    val subtitle: String? = null,
    val chapterReferences: List<String> = emptyList(),
)

/** Only ambiguous destinations require a choice; the card itself never expands. */
@Composable
internal fun ContinuityDestinationSheet(
    title: String,
    destinations: List<ContinuityDestination>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                itemsIndexed(destinations) { index, destination ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onSelect(index) }
                            .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        Text(
                            destination.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        destination.subtitle?.takeIf(String::isNotBlank)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (destination.chapterReferences.isNotEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                        RoundedCornerShape(12.dp),
                                    ).padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                destination.chapterReferences.forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
