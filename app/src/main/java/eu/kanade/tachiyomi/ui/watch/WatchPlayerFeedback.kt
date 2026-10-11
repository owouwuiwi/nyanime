package eu.kanade.tachiyomi.ui.watch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.WatchActivity
import eu.kanade.tachiyomi.data.watch.WatchMember
import eu.kanade.tachiyomi.data.watch.WatchProblem
import eu.kanade.tachiyomi.data.watch.WatchRecovery
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.data.watch.description
import eu.kanade.tachiyomi.data.watch.preparationCaption
import eu.kanade.tachiyomi.data.watch.recovery

/** Kept in the full player overlay so its touch target stays inside the parent's bounds. */
@Composable
fun WatchRecoveryCaption(
    room: WatchRoomState,
    loading: Boolean,
    visible: Boolean,
    reduceMotion: Boolean,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = rememberRoomText()
    AnimatedContent(
        targetState = room.preparationCaption(text, loading).takeIf {
            visible && room.recovery == WatchRecovery.Details
        },
        modifier = modifier.widthIn(max = 280.dp),
        transitionSpec = {
            fadeIn(tween(if (reduceMotion) 0 else 220))
                .togetherWith(fadeOut(tween(if (reduceMotion) 0 else 120)))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        label = "sharedRecoveryDetails",
    ) { caption ->
        if (caption != null) {
            TextButton(onClick = onDetails, enabled = visible) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        caption,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text(R.string.room_details),
                        color = Color(0xFFFF6983),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun WatchParticipantStrip(members: List<WatchMember>, modifier: Modifier = Modifier) {
    val text = rememberRoomText()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        members.take(8).forEach { member ->
            key(member.id) {
                val ready = member.ready && !member.buffering && member.problem == WatchProblem.None
                val color = if (ready) Color(0xFF80D4AA) else Color(0xFFFFB76C)
                val status = if (ready) {
                    text(R.string.room_ready)
                } else {
                    member.problem.takeIf { it != WatchProblem.None }
                        ?.description(text) ?: text(R.string.room_loading)
                }
                Box(
                    Modifier.size(26.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .border(1.5.dp, color, CircleShape)
                        .semantics { contentDescription = "${member.name}: $status" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        member.name.trim().take(1).uppercase(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun WatchActivityCaption(activity: WatchActivity?, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val text = rememberRoomText()
    AnimatedContent(
        targetState = activity,
        contentKey = { it?.id },
        modifier = modifier.widthIn(max = 440.dp).semantics { liveRegion = LiveRegionMode.Polite },
        transitionSpec = {
            fadeIn(tween(if (reduceMotion) 0 else 200))
                .togetherWith(fadeOut(tween(if (reduceMotion) 0 else 180)))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        label = "sharedActionCaption",
    ) { visible ->
        if (visible != null) {
            Text(
                visible.label(text),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(shadow = Shadow(Color.Black, blurRadius = 12f)),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
