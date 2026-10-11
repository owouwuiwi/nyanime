package eu.kanade.tachiyomi.ui.watch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.WatchMember
import eu.kanade.tachiyomi.data.watch.WatchProblem
import eu.kanade.tachiyomi.data.watch.WatchReactionEvent
import eu.kanade.tachiyomi.data.watch.WatchVoicePresence
import eu.kanade.tachiyomi.data.watch.description

/** Invitations keep the same footprint while the connection and copy acknowledgement change. */
@Composable
fun WatchLoungeInvite(
    code: String,
    ready: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onQr: () -> Unit,
    inviteReady: Boolean = ready,
) {
    val text = rememberRoomText()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(text(R.string.room_lounge_share_hint), color = colors.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text(R.string.room_code),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        code.takeIf(String::isNotBlank)?.chunked(4)?.joinToString(" ") ?: "···· ····",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text(if (ready) R.string.room_code_ready else R.string.room_preparing_invites),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                )
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        Button(onClick = onShare, enabled = inviteReady, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Default.Share, null, Modifier.padding(end = 10.dp).size(20.dp))
            Text(text(R.string.room_invite_friend))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.4f
            if (stacked) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCopy, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            null,
                            Modifier.padding(end = 8.dp).size(18.dp),
                        )
                        Text(text(if (copied) R.string.room_copied else R.string.room_copy).trim())
                    }
                    FilledTonalButton(onClick = onQr, enabled = inviteReady, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCode2, null, Modifier.padding(end = 8.dp).size(18.dp))
                        Text(text(R.string.room_qr_title))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = onCopy, enabled = ready, modifier = Modifier.weight(1f)) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            null,
                            Modifier.padding(end = 8.dp).size(18.dp),
                        )
                        Text(text(if (copied) R.string.room_copied else R.string.room_copy).trim())
                    }
                    FilledTonalButton(onClick = onQr, enabled = inviteReady, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.QrCode2, null, Modifier.padding(end = 8.dp).size(18.dp))
                        Text("QR")
                    }
                }
            }
        }
    }
}

/** A native living room: stable seats, personal avatars and quiet, readable presence. */
@Composable
fun WatchLounge(
    members: List<WatchMember>,
    voices: Map<String, WatchVoicePresence> = emptyMap(),
    reactions: List<WatchReactionEvent> = emptyList(),
    onMember: (WatchMember) -> Unit = {},
) {
    val motion = modernMotionEnabled()
    val text = rememberRoomText()
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(
            Brush.verticalGradient(listOf(colors.primary.copy(alpha = .045f), colors.surfaceContainerLow)),
        ).padding(horizontal = 8.dp, vertical = 20.dp),
    ) {
        val minSeat = 88f * density.fontScale.coerceAtMost(1.8f)
        val columns = (maxWidth.value / minSeat).toInt().coerceIn(1, 4)
        val participants = members.take(8)
        val addEmptySeat = participants.size < columns || participants.isEmpty()
        val seats = participants.map<WatchMember, WatchMember?> { it } + if (addEmptySeat) listOf(null) else emptyList()
        val rows = seats.chunked(columns)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            rows.forEach { row ->
                Box(Modifier.fillMaxWidth()) {
                    Canvas(Modifier.fillMaxWidth().height(110.dp).align(Alignment.TopCenter).padding(top = 52.dp)) {
                        drawRoundRect(
                            colors.primary.copy(alpha = .065f),
                            Offset(10.dp.toPx(), 0f),
                            Size(size.width - 20.dp.toPx(), size.height * .72f),
                            CornerRadius(22.dp.toPx()),
                        )
                        drawRoundRect(
                            colors.primary.copy(alpha = .1f),
                            Offset(0f, size.height * .5f),
                            Size(size.width, size.height * .5f),
                            CornerRadius(14.dp.toPx()),
                        )
                        val seatWidth = size.width / row.size
                        row.indices.drop(1).forEach { index ->
                            drawLine(
                                colors.surface.copy(alpha = .55f),
                                Offset(seatWidth * index, size.height * .58f),
                                Offset(seatWidth * index, size.height * .92f),
                                1.dp.toPx(),
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top,
                    ) {
                        row.forEach { member ->
                            key(member?.id ?: "emptySeat") {
                                if (member == null) {
                                    Column(
                                        Modifier.weight(1f).padding(horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(Modifier.height(104.dp), contentAlignment = Alignment.Center) {
                                            Box(
                                                Modifier.size(
                                                    54.dp,
                                                ).background(colors.surface.copy(alpha = .7f), CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Default.PersonAddAlt1,
                                                    null,
                                                    Modifier.size(24.dp),
                                                    tint = colors.onSurfaceVariant.copy(alpha = .65f),
                                                )
                                            }
                                        }
                                        Text(
                                            text(
                                                if (participants.isEmpty()) {
                                                    R.string.room_lounge_empty
                                                } else {
                                                    R.string.room_lounge_empty_seat
                                                },
                                            ),
                                            Modifier.widthIn(max = 150.dp).padding(top = 10.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colors.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                } else {
                                    val voice = voices[member.id]
                                    val host = member.id == members.firstOrNull()?.id
                                    val status = when {
                                        member.problem != WatchProblem.None -> member.problem.description(text)
                                        member.buffering -> text(R.string.room_loading)
                                        member.reading -> text(R.string.room_reading)
                                        member.observer -> text(R.string.playback_observing_short)
                                        member.ready -> text(R.string.room_ready)
                                        else -> text(R.string.room_waiting)
                                    }
                                    val palette = listOf(colors.primary, colors.tertiary, colors.secondary)
                                    val personalColor = palette[(member.id.hashCode() and Int.MAX_VALUE) % palette.size]
                                    val accent by animateColorAsState(
                                        targetValue = if (member.problem !=
                                            WatchProblem.None
                                        ) {
                                            colors.error
                                        } else {
                                            personalColor
                                        },
                                        animationSpec = tween(if (motion) 220 else 0),
                                        label = "loungeAvatarColor",
                                    )
                                    Column(
                                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable(
                                            onClick = { onMember(member) },
                                            onClickLabel = text(R.string.room_participant_details),
                                        ).semantics(mergeDescendants = true) {
                                            contentDescription = member.name +
                                                ", " +
                                                status +
                                                if (host) ", " + text(R.string.room_lounge_host) else ""
                                        }.padding(horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(Modifier.height(104.dp), contentAlignment = Alignment.BottomCenter) {
                                            WatchPawn(accent, host, voice?.joined == true && !voice.muted)
                                            val reaction = reactions.lastOrNull {
                                                it.author == member.id
                                            }
                                            AnimatedContent(
                                                targetState = reaction,
                                                modifier = Modifier.align(Alignment.TopEnd),
                                                transitionSpec = { ModernMotion.transform(motion, 140) },
                                                label = "loungeReaction",
                                            ) { event ->
                                                if (event !=
                                                    null
                                                ) {
                                                    Text(
                                                        event.reaction.glyph,
                                                        style = MaterialTheme.typography.headlineSmall,
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            member.name,
                                            Modifier.padding(top = 10.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                        )
                                        Row(
                                            Modifier.padding(top = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            if (voice?.joined == true) {
                                                Icon(
                                                    if (voice.muted) Icons.Default.MicOff else Icons.Default.Mic,
                                                    text(
                                                        if (voice.muted) {
                                                            R.string.room_voice_muted
                                                        } else {
                                                            R.string.room_voice_live
                                                        },
                                                    ),
                                                    Modifier.size(12.dp),
                                                    tint = if (voice.muted) colors.onSurfaceVariant else colors.primary,
                                                )
                                            }
                                            Text(
                                                status,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchPawn(color: Color, host: Boolean, microphoneOn: Boolean) {
    val outline = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(64.dp, 90.dp)) {
        val cx = size.width / 2
        if (microphoneOn) {
            drawCircle(
                color.copy(alpha = .16f),
                size.width * .47f,
                Offset(cx, size.height * .48f),
                style = Stroke(3.dp.toPx()),
            )
        }
        drawRoundRect(
            color.copy(alpha = .9f),
            Offset(size.width * .15f, size.height * .5f),
            Size(size.width * .7f, size.height * .45f),
            CornerRadius(size.width * .25f),
        )
        drawCircle(color, size.width * .3f, Offset(cx, size.height * .37f))
        drawCircle(outline, 1.5.dp.toPx(), Offset(cx - 5.dp.toPx(), size.height * .35f))
        drawCircle(outline, 1.5.dp.toPx(), Offset(cx + 5.dp.toPx(), size.height * .35f))
        drawArc(
            outline,
            15f,
            150f,
            false,
            Offset(cx - 4.dp.toPx(), size.height * .4f),
            Size(8.dp.toPx(), 4.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
        if (host) {
            val crown = Path().apply {
                moveTo(cx - 10.dp.toPx(), 13.dp.toPx())
                lineTo(cx - 12.dp.toPx(), 5.dp.toPx())
                lineTo(cx - 4.dp.toPx(), 9.dp.toPx())
                lineTo(cx, 3.dp.toPx())
                lineTo(cx + 4.dp.toPx(), 9.dp.toPx())
                lineTo(cx + 12.dp.toPx(), 5.dp.toPx())
                lineTo(cx + 10.dp.toPx(), 13.dp.toPx())
                close()
            }
            drawPath(crown, Color(0xFFE8B85A))
        }
    }
}
