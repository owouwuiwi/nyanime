package eu.kanade.presentation.more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.util.ProvideDocumentLinks
import eu.kanade.tachiyomi.R

@Composable
internal fun UpdateOverview(installed: String, target: String, notes: List<UpdateVersionNotes>) {
    val colors = MaterialTheme.colorScheme
    val counts =
        remember(notes) {
            notes
                .flatMap { it.groups }
                .filter { it.entries != null }
                .groupBy { it.category }
                .mapValues { (_, groups) -> groups.sumOf { it.entries.orEmpty().size } }
        }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VersionChip(installed, selected = false)
                Box(Modifier.heightIn(min = 38.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        null,
                        Modifier.size(18.dp),
                        colors.onSurfaceVariant,
                    )
                }
                VersionChip(target, selected = true)
            }
            Text(
                pluralStringResource(
                    R.plurals.app_update_included_versions,
                    notes.size,
                    notes.size,
                ),
                Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (counts.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                    Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UpdateNoteCategory.entries.forEach { category ->
                        counts[category]
                            ?.takeIf { it > 0 }
                            ?.let { count ->
                                NoteCountChip(category, count)
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionChip(version: String, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (selected) colors.primaryContainer else colors.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            version.removePrefix("v"),
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun NoteCountChip(category: UpdateNoteCategory, count: Int) {
    val accent = category.accent()
    Surface(color = accent.copy(alpha = 0.1f), shape = RoundedCornerShape(50)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(category.icon(), null, Modifier.size(15.dp), accent)
            Text(
                "$count · ${stringResource(category.label())}",
                color = accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun UpdateHistoryStatus(loading: Boolean, onDetails: () -> Unit) {
    if (loading) {
        Text(
            stringResource(R.string.app_update_history_loading),
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.app_update_history_partial),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun UpdateVersionCard(
    notes: UpdateVersionNotes,
    initiallyExpanded: Boolean,
    last: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val motion = appMotionEnabled()
    var expanded by rememberSaveable(notes.key) { mutableStateOf(initiallyExpanded) }
    val rotation by
        animateFloatAsState(
            if (expanded) 180f else 0f,
            tween(if (motion) ModernMotion.EXIT_MILLIS else 0),
            label = "releaseChevron",
        )
    val state =
        stringResource(
            if (expanded) R.string.app_update_expanded else R.string.app_update_collapsed,
        )
    Column(
        Modifier.fillMaxWidth()
            .drawBehind {
                val x = 5.dp.toPx()
                val y = 25.dp.toPx()
                drawLine(
                    colors.outlineVariant,
                    Offset(x, y),
                    Offset(x, if (last) y else size.height + 18.dp.toPx()),
                    1.dp.toPx(),
                )
                drawCircle(
                    if (initiallyExpanded) colors.primary else colors.outline,
                    4.dp.toPx(),
                    Offset(x, y),
                )
            }
            .padding(start = 20.dp),
    ) {
        Surface(
            onClick = { expanded = !expanded },
            color = Color.Transparent,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = state },
        ) {
            Row(
                Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            notes.version,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (notes.prerelease) {
                            Surface(
                                color = colors.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    stringResource(R.string.app_update_preview_badge),
                                    Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    color = colors.onTertiaryContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    notes.title?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    Icons.Outlined.ExpandMore,
                    null,
                    Modifier.size(22.dp).rotate(rotation),
                    colors.onSurfaceVariant,
                )
            }
        }
        // A single resize clock prevents section expansion from moving at conflicting speeds.
        Column(Modifier.animateContentSize(tween(if (motion) ModernMotion.RESIZE_MILLIS else 0))) {
            AnimatedVisibility(
                visible = expanded,
                enter =
                if (motion) {
                    ModernMotion.enter(ModernMotion.EXIT_MILLIS)
                } else {
                    androidx.compose.animation.EnterTransition.None
                },
                exit =
                if (motion) {
                    ModernMotion.exit()
                } else {
                    androidx.compose.animation.ExitTransition.None
                },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    notes.groups.forEachIndexed { index, group ->
                        UpdateNoteCard(notes.key, index, group, updateNotesDocument(notes.version))
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateNoteCard(
    version: String,
    index: Int,
    group: UpdateNoteGroup,
    documentUrl: String,
) {
    val colors = MaterialTheme.colorScheme
    val accent = group.category.accent()
    val motion = appMotionEnabled()
    var showAll by rememberSaveable(version, index) { mutableStateOf(false) }
    val entries = group.entries
    val canShorten = entries != null && entries.size > 4
    val content =
        if (canShorten && !showAll) entries!!.take(4).joinToString("\n\n") else group.markdown
    Surface(
        color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize(tween(if (motion) ModernMotion.RESIZE_MILLIS else 0))) {
            Surface(color = accent.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(group.category.icon(), null, Modifier.size(20.dp), accent)
                    Text(
                        stringResource(group.category.label()),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entries?.let {
                        Text(
                            it.size.toString(),
                            color = accent,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface)) {
                ProvideDocumentLinks(documentUrl) {
                    RichText(
                        modifier = Modifier.padding(16.dp),
                        style =
                        RichTextStyle(
                            stringStyle =
                            RichTextStringStyle(
                                linkStyle = SpanStyle(color = colors.primary),
                            ),
                        ),
                    ) {
                        Markdown(content)
                    }
                }
            }
            if (canShorten) {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (showAll) {
                                R.string.app_update_show_less
                            } else {
                                R.string.app_update_show_all
                            },
                            entries!!.size,
                        ),
                    )
                }
            }
        }
    }
}

private fun UpdateNoteCategory.label() =
    when (this) {
        UpdateNoteCategory.FEATURES -> R.string.app_update_notes_features
        UpdateNoteCategory.FIXES -> R.string.app_update_notes_fixes
        UpdateNoteCategory.PERFORMANCE -> R.string.app_update_notes_performance
        UpdateNoteCategory.INTERFACE -> R.string.app_update_notes_interface
        UpdateNoteCategory.COMPATIBILITY -> R.string.app_update_notes_compatibility
        UpdateNoteCategory.SECURITY -> R.string.app_update_notes_security
        UpdateNoteCategory.DISTRIBUTION -> R.string.app_update_notes_distribution
        UpdateNoteCategory.VERIFICATION -> R.string.app_update_notes_verification
        UpdateNoteCategory.LIMITATIONS -> R.string.app_update_notes_limitations
        UpdateNoteCategory.ORIGINAL -> R.string.app_update_notes_original
    }

private fun UpdateNoteCategory.icon() =
    when (this) {
        UpdateNoteCategory.FEATURES -> Icons.Outlined.AutoAwesome
        UpdateNoteCategory.FIXES -> Icons.Outlined.BugReport
        UpdateNoteCategory.PERFORMANCE -> Icons.Outlined.Speed
        UpdateNoteCategory.INTERFACE -> Icons.Outlined.Palette
        UpdateNoteCategory.COMPATIBILITY -> Icons.Outlined.Devices
        UpdateNoteCategory.SECURITY -> Icons.Outlined.Security
        UpdateNoteCategory.DISTRIBUTION -> Icons.Outlined.CloudDownload
        UpdateNoteCategory.VERIFICATION -> Icons.Outlined.FactCheck
        UpdateNoteCategory.LIMITATIONS -> Icons.Outlined.WarningAmber
        UpdateNoteCategory.ORIGINAL -> Icons.Outlined.Notes
    }

@Composable
private fun UpdateNoteCategory.accent(): Color {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    return when (this) {
        UpdateNoteCategory.FEATURES -> colors.primary
        UpdateNoteCategory.FIXES -> if (dark) Color(0xFFFFC568) else Color(0xFF815100)
        UpdateNoteCategory.PERFORMANCE -> if (dark) Color(0xFF7AE2AA) else Color(0xFF16643C)
        UpdateNoteCategory.INTERFACE -> if (dark) Color(0xFFEBA9DB) else Color(0xFF843068)
        UpdateNoteCategory.COMPATIBILITY -> if (dark) Color(0xFF9BCBFF) else Color(0xFF205E93)
        UpdateNoteCategory.SECURITY -> if (dark) Color(0xFFCAB6FF) else Color(0xFF63469A)
        UpdateNoteCategory.DISTRIBUTION -> if (dark) Color(0xFF83DAD4) else Color(0xFF176B65)
        UpdateNoteCategory.VERIFICATION -> if (dark) Color(0xFFBDCCFF) else Color(0xFF46568E)
        UpdateNoteCategory.LIMITATIONS -> if (dark) Color(0xFFFFB895) else Color(0xFF934A25)
        UpdateNoteCategory.ORIGINAL -> if (dark) Color(0xFFBBCCD6) else Color(0xFF445D6C)
    }
}
