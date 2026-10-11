package eu.kanade.tachiyomi.ui.watch

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.data.watch.WatchChatMessage
import eu.kanade.tachiyomi.data.watch.WatchReactionEvent
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import kotlinx.coroutines.delay

/** Presentation only. Never opens controls or reads the native player. */
@Composable
fun WatchRoomPlayerSocial(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { WatchTogetherManager.get(context) }
    val room by manager.controller.state.collectAsState()
    val social by manager.interactions.state.collectAsState()
    val motion = modernMotionEnabled()
    val message = social.messages.lastOrNull { !it.own }
    var displayedMessage by remember { mutableStateOf<WatchChatMessage?>(null) }
    var displayedReactions by remember { mutableStateOf(emptyList<WatchReactionEvent>()) }
    LaunchedEffect(message?.id) { if (message != null) displayedMessage = message }
    LaunchedEffect(social.reactions) {
        if (social.reactions.isNotEmpty()) {
            displayedReactions =
                social.reactions.takeLast(4)
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(message?.id, social.overlayEnabled) {
        now = SystemClock.elapsedRealtime()
        if (message != null && social.overlayEnabled) {
            val remaining = message.receivedAt + 7000 - now
            if (remaining > 0) {
                delay(remaining)
                now = SystemClock.elapsedRealtime()
            }
        }
    }
    if (!room.active) return
    Column(
        modifier.widthIn(max = 300.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val recent = message?.takeIf { social.overlayEnabled && now - it.receivedAt in 0 until 7000 }
        AnimatedVisibility(
            recent != null,
            enter = fadeIn(tween(if (motion) 180 else 0)),
            exit = fadeOut(tween(if (motion) 100 else 0)),
        ) {
            displayedMessage?.let {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = .72f),
                    contentColor = Color.White,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(it.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            it.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            social.reactionsEnabled && social.reactions.isNotEmpty(),
            enter = fadeIn(tween(if (motion) 160 else 0)),
            exit = fadeOut(tween(if (motion) 100 else 0)),
        ) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = .5f)) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    displayedReactions.forEach {
                        Text(it.reaction.glyph, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}
