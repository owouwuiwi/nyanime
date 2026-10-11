package eu.kanade.tachiyomi.ui.reading

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.reading.ReadingRoomState
import eu.kanade.tachiyomi.data.reading.ReadingTogetherManager
import eu.kanade.tachiyomi.data.reading.ReadingTools
import eu.kanade.tachiyomi.ui.watch.rememberRoomText

@Composable
internal fun ReadingPresenceControl(
    manager: ReadingTogetherManager,
    room: ReadingRoomState,
    tools: ReadingTools,
    onOpenRoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = rememberRoomText()
    val colors = MaterialTheme.colorScheme
    val activity = LocalContext.current as? Activity
    val session by manager.watch.readingSession.state.collectAsState()
    var expanded by rememberSaveable(room.invite) { mutableStateOf(false) }
    val duration = if (modernMotionEnabled()) ModernMotion.PAGE_MILLIS else 0
    Surface(
        modifier,
        shape = RoundedCornerShape(22.dp),
        color = colors.surfaceContainerHigh.copy(alpha = .96f),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.animateContentSize(tween(duration))) {
            Row(Modifier.padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Group, null, Modifier.size(18.dp), colors.primary)
                Text(
                    if (room.connected) {
                        text(R.string.room_together_count, room.members.size)
                    } else {
                        text(R.string.room_reconnecting_short)
                    },
                    Modifier.weight(1f).clickable {
                        expanded = !expanded
                    }.padding(horizontal = 10.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                eu.kanade.tachiyomi.ui.watch.WatchRoomSocialBar(compact = true)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        text(R.string.room_reading_expand),
                    )
                }
            }
            tools.chapterNotice?.let { selection ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text(R.string.room_chapter_changed_notice),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                        )
                        Text(
                            selection.chapter.chapterName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(enabled = !tools.opening, onClick = { activity?.let(manager::reachShared) }) {
                        Text(text(R.string.room_reach_shared))
                    }
                }
            }
            AnimatedVisibility(expanded, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
                Column {
                    LazyColumn(
                        Modifier.heightIn(max = 260.dp).padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(room.members.toList(), key = { it.first }) { (id, peer) ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    peer.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                peer.position?.let { position ->
                                    Text(
                                        position.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text(R.string.room_chapter_page, position.chapterName, position.page + 1),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (id != room.localId) {
                                        TextButton(
                                            enabled = !tools.opening && room.connected,
                                            onClick = { activity?.let { manager.jump(it, position) } },
                                        ) {
                                            Text(text(R.string.room_reach_shared))
                                        }
                                    }
                                }
                                session.openings[id]?.let {
                                    Text(
                                        text(it.label()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    TextButton(onClick = onOpenRoom, modifier = Modifier.fillMaxWidth()) {
                        Text(text(R.string.room_open_room))
                    }
                }
            }
        }
    }
}
