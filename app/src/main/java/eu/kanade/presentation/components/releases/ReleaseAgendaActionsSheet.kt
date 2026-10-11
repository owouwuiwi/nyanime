package eu.kanade.presentation.components.releases

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Navigation and local agenda actions stay separate, including for merged editions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReleaseAgendaActionsSheet(
    item: ReleaseAgendaItem,
    onDismiss: () -> Unit,
    onTitle: () -> Unit,
    onContent: () -> Unit,
    onUnfollow: () -> Unit,
    onRemove: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val motion = appMotionEnabled()
    val cue = releaseColor(item.medium)
    val locale = LocalConfiguration.current.locales[0]
    var acting by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { revealed = true }
    val reveal by animateFloatAsState(
        if (revealed) 1f else 0f,
        animationSpec = if (motion) tween(ModernMotion.PAGE_MILLIS) else snap(),
        label = "agenda-actions-reveal",
    )
    fun act(action: () -> Unit) {
        if (acting) return
        acting = true
        scope.launch {
            try {
                sheet.hide()
                if (!sheet.isVisible) {
                    onDismiss()
                    action()
                }
            } finally {
                // A swipe may interrupt dismissal; the menu must remain usable.
                acting = false
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .graphicsLayer {
                    alpha = reveal
                    translationY = (1f - reveal) * 8.dp.toPx()
                },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ItemCover.Book(
                    data = item.cover,
                    modifier = Modifier.width(52.dp).height(78.dp),
                    shape = RoundedCornerShape(10.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Instant.ofEpochMilli(item.at).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale)),
                        style = MaterialTheme.typography.labelMedium,
                        color = cue,
                    )
                }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    AgendaActionRow(
                        stringResource(R.string.release_go_title),
                        Icons.Outlined.OpenInNew,
                        cue,
                        enabled = !acting,
                    ) { act(onTitle) }
                    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val anime = item.medium == ReleaseMedium.ANIME
                    val available = ReleaseAgendaActions.playableOptions(item).isNotEmpty()
                    AgendaActionRow(
                        stringResource(if (anime) R.string.release_go_episode else R.string.release_go_chapter),
                        if (anime) Icons.Outlined.PlayCircleOutline else Icons.Outlined.AutoStories,
                        cue,
                        description = if (available) null else stringResource(R.string.release_content_not_available),
                        enabled = !acting && available,
                    ) { act(onContent) }
                }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    AgendaActionRow(
                        stringResource(R.string.release_menu_unfollow),
                        Icons.Outlined.NotificationsOff,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        description = stringResource(R.string.release_menu_unfollow_description),
                        enabled = !acting,
                    ) { act(onUnfollow) }
                    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    AgendaActionRow(
                        stringResource(R.string.release_remove_entry),
                        Icons.Outlined.DeleteOutline,
                        MaterialTheme.colorScheme.error,
                        description = stringResource(R.string.release_remove_entry_description),
                        enabled = !acting,
                    ) { act(onRemove) }
                }
            }
        }
    }
}

@Composable
private fun AgendaActionRow(
    title: String,
    icon: ImageVector,
    tint: Color,
    description: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) tint else tint.copy(alpha = .38f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .6f),
                )
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (enabled) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
