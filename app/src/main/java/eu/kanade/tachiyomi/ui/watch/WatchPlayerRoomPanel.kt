package eu.kanade.tachiyomi.ui.watch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager

/** One stable player entry point. Opening communication never pauses or resizes the video. */
@Composable
fun WatchPlayerRoomAccess(room: WatchRoomState, onClick: () -> Unit) {
    if (!room.active) return
    val context = LocalContext.current
    val manager = remember { WatchTogetherManager.get(context) }
    val social by manager.interactions.state.collectAsState()
    val voice by manager.voice.state.collectAsState()
    val text = rememberRoomText()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color(0xFF16151B).copy(alpha = .88f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = .14f)),
        modifier = Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) {
            contentDescription = text(R.string.room_player_open_chat)
            role = Role.Button
            stateDescription = if (social.unread > 0) {
                text(R.string.room_player_unread, social.unread)
            } else {
                text(R.string.room_player_participants, room.members.size)
            }
        },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            BadgedBox(badge = {
                if (social.unread > 0) {
                    Badge { Text(if (social.unread > 99) "99+" else social.unread.toString()) }
                }
            }) {
                Icon(Icons.Default.Group, null, Modifier.size(20.dp))
            }
            Text(text(R.string.room_player_button, room.members.size), style = MaterialTheme.typography.labelLarge)
            if (voice.active) {
                Icon(
                    if (voice.muted) Icons.Default.MicOff else Icons.Default.Mic,
                    text(if (voice.muted) R.string.room_voice_muted else R.string.room_voice_live),
                    Modifier.size(16.dp),
                    tint = if (voice.muted) Color.White.copy(alpha = .65f) else Color(0xFF8DDEBF),
                )
            }
        }
    }
}

/** Conversation first: the header exposes the room and voice without replacing the chat. */
@Composable
fun WatchPlayerRoomPanel(visible: Boolean, onRoomDetails: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { WatchTogetherManager.get(context) }
    val room by manager.controller.state.collectAsState()
    val text = rememberRoomText()
    // The holder outlives the sheet: closing it or visiting the room preserves
    // the conversation's scroll position. The session owns the message draft.
    val holder = key(room.invite) { rememberSaveableStateHolder() }
    if (visible) {
        WatchAdaptivePanel(
            title = text(R.string.room_chat),
            onDismiss = onDismiss,
            overVideo = true,
            titleContent = {
                PlayerRoomHeading(room) {
                    onDismiss()
                    onRoomDetails()
                }
                WatchVoiceCompactControl(manager)
            },
        ) {
            holder.SaveableStateProvider("conversation") {
                Column(Modifier.weight(1f).fillMaxSize()) {
                    WatchChatContent(manager.interactions)
                }
            }
        }
    }
}

@Composable
private fun RowScope.PlayerRoomHeading(room: WatchRoomState, onClick: () -> Unit) {
    val text = rememberRoomText()
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics(mergeDescendants = true) {
            contentDescription = text(R.string.room_player_open_lounge)
            stateDescription = text(R.string.room_player_participants, room.members.size)
            role = Role.Button
        },
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
    ) {
        BoxWithConstraints {
            val readableWidth = maxWidth / LocalDensity.current.fontScale
            Row(
                Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (readableWidth >= 160.dp) {
                    Icon(
                        Icons.Default.Group,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text(R.string.room_chat),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text(R.string.room_player_participants, room.members.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (readableWidth >= 96.dp) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
