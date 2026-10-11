@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.community

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.community.CommunityProfile
import eu.kanade.tachiyomi.data.community.SocialPresence
import eu.kanade.tachiyomi.data.community.SocialWatchRequest
import eu.kanade.tachiyomi.data.community.WatchRequestStatus
import kotlinx.coroutines.delay

@Composable
internal fun FriendActivityCard(
    profile: CommunityProfile,
    presence: SocialPresence?,
    pending: Boolean,
    onProfile: () -> Unit,
    onChat: () -> Unit,
    onWatch: () -> Unit,
) {
    val accent = Color(profile.accent)
    val activity = presence?.activity
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accent.copy(alpha = if (activity != null) .38f else .12f)),
    ) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(accent.copy(alpha = .12f), Color.Transparent)),
            )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onProfile),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(profile, 48)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (presence !=
                            null
                        ) {
                            Surface(Modifier.size(6.dp), shape = CircleShape, color = Color(0xFF71DBB1)) {}
                        }
                        Text(
                            when {
                                activity?.manga == true -> "Sta leggendo adesso"
                                activity != null -> "Sta guardando adesso"
                                presence != null -> "Online"
                                else -> "Il tuo prossimo «lo guardiamo?»"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (activity != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = .15f)) {
                        Box(Modifier.padding(12.dp)) {
                            Icon(
                                if (activity.manga) Icons.Outlined.MenuBook else Icons.Outlined.PlayArrow,
                                null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            activity.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (activity.item.isNotBlank()) {
                            Text(
                                activity.item,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (activity != null && !activity.manga) {
                    Button(onClick = if (pending) onChat else onWatch) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                        Text(if (pending) "  Invito in attesa" else "  Guarda insieme")
                    }
                }
                OutlinedButton(onClick = onChat) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                    Text("  Scrivi")
                }
            }
        }
    }
}

@Composable
internal fun SocialWatchCard(
    request: SocialWatchRequest,
    mine: String,
    friendName: String,
    respond: (Boolean) -> Unit,
    enter: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis(), request.expires) {
        while (value <= request.expires) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    val waiting = request.pending(now)
    val incoming = mine == request.host
    val available = request.expires > now
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .3f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "UN EPISODIO, INSIEME",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(request.activity.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (request.activity.item.isNotBlank()) {
                Text(
                    request.activity.item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    !available -> "Invito scaduto. Potete inviarne un altro."
                    waiting && incoming -> "$friendName vorrebbe guardarlo con te. Accettando aprirai questo episodio in una nuova stanza."
                    waiting -> "Invito in attesa di risposta. Puoi continuare a usare l’app."
                    request.status == WatchRequestStatus.Accepted -> "Ci siete: la vostra stanza è pronta."
                    request.status == WatchRequestStatus.Cancelled -> "Invito annullato."
                    else -> "Magari la prossima volta."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (waiting && incoming) Button(onClick = { respond(true) }) { Text("Accetta e guarda") }
                if (waiting) {
                    TextButton(onClick = {
                        respond(false)
                    }) { Text(if (incoming) "Non ora" else "Annulla invito") }
                }
                if (available && request.status == WatchRequestStatus.Accepted) {
                    Button(onClick = enter) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Text(if (incoming) " Apri la stanza" else " Entriamo in stanza")
                    }
                }
            }
        }
    }
}
