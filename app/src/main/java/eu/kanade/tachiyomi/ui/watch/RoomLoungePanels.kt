package eu.kanade.tachiyomi.ui.watch

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.WatchOpeningState
import eu.kanade.tachiyomi.data.watch.WatchPhase
import eu.kanade.tachiyomi.data.watch.WatchProblem
import eu.kanade.tachiyomi.data.watch.WatchRoomState

@Composable
internal fun RoomLoungeWelcome(showHeading: Boolean) {
    val text = rememberRoomText()
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(76.dp).background(
                Brush.linearGradient(listOf(colors.primary.copy(alpha = .18f), colors.tertiary.copy(alpha = .08f))),
                RoundedCornerShape(28.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Group, null, Modifier.size(36.dp), tint = colors.primary)
        }
        if (showHeading) {
            Text(
                text(R.string.room_watch),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text(R.string.room_lounge_entry_hint),
            modifier = Modifier.widthIn(max = 320.dp),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun RoomLoungeSurface(accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = if (accent) {
            MaterialTheme.colorScheme.primaryContainer.copy(
                alpha = .45f,
            )
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun RoomLoungeNotice(message: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (error) colors.errorContainer.copy(alpha = .5f) else colors.surfaceContainerLow,
    ) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) colors.onErrorContainer else colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun RoomLoungeEntrySelector(joining: Boolean, onSelect: (Boolean) -> Unit) {
    val text = rememberRoomText()
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerHighest.copy(alpha = .65f), RoundedCornerShape(18.dp))
            .padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            false to R.string.room_lounge_create_tab,
            true to R.string.room_lounge_join_tab,
        ).forEach { (join, label) ->
            Surface(
                modifier = Modifier.weight(1f)
                    .selectable(selected = joining == join, role = Role.Tab, onClick = { onSelect(join) }),
                shape = RoundedCornerShape(14.dp),
                color = if (joining == join) colors.surface else androidx.compose.ui.graphics.Color.Transparent,
                tonalElevation = if (joining == join) 2.dp else 0.dp,
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text(label),
                        textAlign = TextAlign.Center,
                        style = if (LocalDensity.current.fontScale > 1.5f) {
                            MaterialTheme.typography.labelLarge
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (joining == join) FontWeight.Bold else FontWeight.Medium,
                        color = if (joining == join) colors.onSurface else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomLoungeSheet(onDismiss: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun RoomLoungeOption(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun RoomLoungeNowPlaying(
    room: WatchRoomState,
    opening: WatchOpeningState,
    liveChannel: String?,
    onChooseVideo: () -> Unit,
    onOpenPlayer: (() -> Unit)?,
    onTogglePlayback: () -> Unit,
    onRetry: () -> Unit,
) {
    val text = rememberRoomText()
    val colors = MaterialTheme.colorScheme
    val motion = modernMotionEnabled()
    val hasMedia = room.media != null || room.liveMode
    val loading = opening.loading ||
        room.preparingPlayback ||
        room.phase in listOf(WatchPhase.Connecting, WatchPhase.Reconnecting, WatchPhase.Buffering)
    RoomLoungeSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(42.dp).background(colors.primary.copy(alpha = .12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (room.liveMode) Icons.Default.Tv else Icons.Default.Movie,
                    null,
                    Modifier.size(22.dp),
                    tint = colors.primary,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text(
                        when {
                            !hasMedia -> R.string.room_watch
                            room.liveMode -> R.string.room_lounge_live
                            else -> R.string.room_lounge_now
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    liveChannel ?: room.media?.title
                        ?: text(if (room.host) R.string.room_choose_watch else R.string.room_friend_choosing),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        room.media?.episode?.takeIf(String::isNotBlank)?.let {
            Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        val status = opening.error ?: when {
            opening.loading -> text(R.string.room_opening_episode)
            room.preparingPlayback -> room.playbackPreparationMessage(text)
            else -> room.message
        }
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (status.isNotBlank()) {
            AnimatedContent(
                targetState = status,
                transitionSpec = { ModernMotion.transform(motion, 180) },
                label = "roomPlaybackStatus",
            ) { current ->
                Text(
                    current,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (opening.error !=
                        null
                    ) {
                        colors.error
                    } else {
                        colors.onSurfaceVariant
                    },
                )
            }
        }
        when {
            opening.error != null -> TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text(
                        if (opening.problem ==
                            WatchProblem.MissingSource
                        ) {
                            R.string.room_open_extensions
                        } else {
                            R.string.room_retry
                        },
                    ),
                )
            }
            !hasMedia && room.host -> Button(
                onClick = onChooseVideo,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Add, null, Modifier.padding(end = 8.dp).size(20.dp))
                Text(text(R.string.room_choose_video))
            }
            hasMedia && onOpenPlayer != null -> Button(
                onClick = onOpenPlayer,
                enabled = !opening.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.padding(end = 8.dp).size(20.dp))
                Text(text(R.string.room_open_player))
            }
        }
        if (room.media != null && (room.sharedControls || room.host || room.localHold)) {
            FilledTonalButton(
                onClick = onTogglePlayback,
                enabled =
                room.phase !in listOf(WatchPhase.Connecting, WatchPhase.Reconnecting, WatchPhase.DifferentVideo),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(
                    if (room.wantsPlayback &&
                        !room.localHold
                    ) {
                        Icons.Default.Pause
                    } else {
                        Icons.Default.PlayArrow
                    },
                    null,
                    Modifier.padding(end = 8.dp).size(20.dp),
                )
                Text(
                    text(
                        when {
                            !room.sharedControls && !room.host -> R.string.room_return_watch
                            room.wantsPlayback && !room.localHold -> R.string.room_pause_action
                            else -> R.string.room_play_action
                        },
                    ),
                )
            }
        }
    }
}

@Composable
internal fun RoomLoungeMediaActions(onChooseVideo: () -> Unit, onRead: () -> Unit, host: Boolean) {
    val text = rememberRoomText()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f
        if (stacked) {
            Column(Modifier.fillMaxWidth()) {
                if (host) {
                    TextButton(onClick = onChooseVideo, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Movie, null, Modifier.padding(end = 8.dp).size(18.dp))
                        Text(text(R.string.room_lounge_change_video))
                    }
                }
                TextButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.padding(end = 8.dp).size(18.dp))
                    Text(text(R.string.room_read))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (host) {
                    TextButton(onClick = onChooseVideo, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Movie, null, Modifier.padding(end = 8.dp).size(18.dp))
                        Text(text(R.string.room_lounge_change_video))
                    }
                }
                TextButton(onClick = onRead, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.padding(end = 8.dp).size(18.dp))
                    Text(text(R.string.room_read))
                }
            }
        }
    }
}
