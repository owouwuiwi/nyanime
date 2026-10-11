package eu.kanade.tachiyomi.ui.watch

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.data.watch.WatchOpeningState
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager

/** Browsing reads the room snapshot only; it never reads the detached native player. */
@Composable
fun WatchMiniController(modifier: Modifier = Modifier, includeNavigationInsets: Boolean = true) {
    val context = LocalContext.current
    val manager = remember { WatchTogetherManager.get(context) }
    val room by manager.controller.state.collectAsState()
    val opening by manager.opening.collectAsState()
    val reading by manager.readingSession.state.collectAsState()
    val live by manager.tv.state.collectAsState()
    val cast = remember { CastController.get(context) }
    val casting by cast.state.collectAsState()
    val motion = modernMotionEnabled()
    AnimatedVisibility(
        visible = room.active && !casting.active && !casting.connecting,
        enter = fadeIn(tween(if (motion) 180 else 0)) + expandVertically(tween(if (motion) 240 else 0)),
        exit = fadeOut(tween(if (motion) 120 else 0)) + shrinkVertically(tween(if (motion) 180 else 0)),
    ) {
        val openRoom = { context.startActivity(Intent(context, WatchTogetherActivity::class.java)) }
        WatchMiniBar(
            room,
            opening,
            onOpenPlayer = when {
                room.sharedReadingMode && reading.selection != null -> {
                    {
                        (context as? android.app.Activity)?.let {
                            eu.kanade.tachiyomi.data.reading.ReadingTogetherManager.get(context).reachShared(it)
                        }
                        Unit
                    }
                }
                room.liveMode -> manager::openSelectedLive
                room.media != null -> manager::openSelectedVideo
                else -> openRoom
            },
            onOpenRoom = openRoom,
            modifier = modifier,
            includeNavigationInsets = includeNavigationInsets,
            readingTitle = reading.selection?.chapter?.title.takeIf { room.sharedReadingMode },
            readingChapter = reading.selection?.chapter?.chapterName.takeIf { room.sharedReadingMode },
            liveTitle = live.selection?.name.takeIf { room.liveMode },
        )
    }
}

@Composable
internal fun WatchMiniBar(
    room: WatchRoomState,
    opening: WatchOpeningState,
    onOpenPlayer: () -> Unit,
    onOpenRoom: () -> Unit,
    modifier: Modifier = Modifier,
    includeNavigationInsets: Boolean = false,
    readingTitle: String? = null,
    readingChapter: String? = null,
    liveTitle: String? = null,
) {
    val text = rememberRoomText()
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(
            (if (includeNavigationInsets) Modifier.navigationBarsPadding() else Modifier)
                .fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).clickable(
                    enabled = !opening.loading,
                    role = Role.Button,
                    onClickLabel = if (room.media !=
                        null
                    ) {
                        text(R.string.room_return_video)
                    } else {
                        text(R.string.room_open_room)
                    },
                    onClick = onOpenPlayer,
                ).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    when {
                        room.sharedReadingMode -> Icons.Default.MenuBook
                        room.liveMode -> Icons.Default.LiveTv
                        else -> Icons.Default.PlayArrow
                    },
                    null,
                    Modifier.size(28.dp),
                    colors.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        readingTitle ?: liveTitle ?: room.media?.title ?: text(R.string.room_watch_invitation),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            room.sharedReadingMode -> readingChapter ?: text(R.string.room_shared_reading)
                            room.liveMode -> text(R.string.tv_live)
                            opening.loading -> text(R.string.room_opening_video)
                            opening.error != null -> text(R.string.room_video_retry)
                            room.media != null -> text(R.string.room_return_episode, room.media.episode)
                            else -> text(R.string.room_choose_episode)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (opening.error != null) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenRoom) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Groups,
                        text(R.string.room_open_participants, room.members.size),
                        Modifier.size(22.dp),
                    )
                    Text(room.members.size.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
