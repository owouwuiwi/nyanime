package eu.kanade.tachiyomi.ui.watch

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.WatchChatDelivery
import eu.kanade.tachiyomi.data.watch.WatchInteractionSession
import eu.kanade.tachiyomi.data.watch.WatchMember
import eu.kanade.tachiyomi.data.watch.WatchReaction
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import kotlinx.coroutines.launch
import nyanime.rooms.voice.RoomVoicePhase

/** Same room conversation in a player, a reader or the lobby; no media-specific dependencies. */
@Composable
fun WatchRoomSocialBar(compact: Boolean = false, includeLounge: Boolean = false) {
    val context = LocalContext.current
    val manager = remember { WatchTogetherManager.get(context) }
    val room by manager.controller.state.collectAsState()
    val social by manager.interactions.state.collectAsState()
    val voice by manager.voice.state.collectAsState()
    val text = rememberRoomText()
    val motion = modernMotionEnabled()
    var chat by rememberSaveable { mutableStateOf(false) }
    var voicePanel by rememberSaveable { mutableStateOf(false) }
    var reactions by rememberSaveable { mutableStateOf(false) }
    var participant by remember { mutableStateOf<WatchMember?>(null) }
    var confirmingRemoval by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(participant?.id) { confirmingRemoval = false }
    if (!room.active) return
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 16.dp)) {
        if (includeLounge) WatchLounge(room.members, social.voices, social.reactions) { participant = it }
        if (!compact && room.host) {
            room.memberRequests.forEach { request ->
                Column(Modifier.fillMaxWidth()) {
                    Text(text(R.string.room_member_approval, request.name), style = MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick = {
                            manager.controller.approveMember(request.id)
                        }) { Text(text(R.string.room_accept)) }
                        TextButton(onClick = {
                            manager.controller.rejectMember(request.id)
                        }) { Text(text(R.string.room_reject)) }
                    }
                }
            }
        }
        Row(
            Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth()),
            horizontalArrangement = if (compact) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
                IconButton(onClick = { voicePanel = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (voice.active && !voice.muted) Icons.Default.Mic else Icons.Default.Headphones,
                        text(R.string.room_voice),
                        tint = if (voice.active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                }
                IconButton(onClick = { chat = true }, modifier = Modifier.size(48.dp)) {
                    BadgedBox(badge = { if (social.unread > 0) Badge { Text(social.unread.toString()) } }) {
                        Icon(Icons.Default.ChatBubbleOutline, text(R.string.room_chat))
                    }
                }
            } else {
                FilledTonalButton(onClick = { voicePanel = true }, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (voice.active && !voice.muted) Icons.Default.Mic else Icons.Default.Headphones,
                        null,
                        Modifier.padding(end = 8.dp).size(20.dp),
                    )
                    Text(text(R.string.room_voice))
                }
                FilledTonalButton(onClick = { chat = true }, modifier = Modifier.weight(1f)) {
                    BadgedBox(badge = { if (social.unread > 0) Badge { Text(social.unread.toString()) } }) {
                        Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(20.dp))
                    }
                    Text(text(R.string.room_chat), Modifier.padding(start = 8.dp))
                }
                IconButton(onClick = { reactions = !reactions }) {
                    Icon(Icons.Default.SentimentSatisfiedAlt, text(R.string.room_reactions))
                }
            }
        }
        AnimatedVisibility(
            reactions && !compact,
            enter = fadeIn(tween(if (motion) 160 else 0)),
            exit = fadeOut(tween(if (motion) 100 else 0)),
        ) {
            WatchReactionPicker(manager.interactions)
        }
        if (!social.supported && !compact) {
            Text(
                text(R.string.room_features_update),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (chat) WatchRoomChat(manager) { chat = false }
    if (voicePanel) WatchVoicePanel(manager) { voicePanel = false }
    participant?.let { member ->
        AlertDialog(
            onDismissRequest = { participant = null },
            title = { Text(if (confirmingRemoval) text(R.string.room_remove_member) else member.name) },
            text = {
                if (confirmingRemoval) {
                    Text(text(R.string.room_player_remove_confirm, member.name))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            room.members.firstOrNull { it.id == member.id }?.let {
                                when {
                                    it.buffering -> text(R.string.room_loading)
                                    it.reading -> text(R.string.room_reading)
                                    it.ready -> text(R.string.room_ready)
                                    else -> text(R.string.room_waiting)
                                }
                            } ?: text(R.string.room_participant_left),
                        )
                        if (room.host && member.id != room.localMemberId) {
                            TextButton(onClick = {
                                confirmingRemoval = true
                            }) { Text(text(R.string.room_remove_member), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmingRemoval && room.host && member.id != room.localMemberId) {
                        manager.controller.removeMember(member.id)
                    }
                    participant = null
                }) {
                    Text(text(if (confirmingRemoval) R.string.room_remove_member else R.string.room_close_dialog))
                }
            },
            dismissButton = {
                if (confirmingRemoval) {
                    TextButton(onClick = { confirmingRemoval = false }) { Text(text(R.string.room_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun WatchReactionPicker(session: WatchInteractionSession) {
    val state by session.state.collectAsState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 288.dp) 3 else 6
        Column(Modifier.fillMaxWidth()) {
            WatchReaction.entries.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { reaction ->
                        IconButton(onClick = {
                            session.react(reaction)
                        }, enabled = state.supported && state.connected) {
                            Text(
                                reaction.glyph,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchRoomChat(manager: WatchTogetherManager, onDismiss: () -> Unit) {
    val text = rememberRoomText()
    WatchAdaptivePanel(
        text(R.string.room_chat),
        onDismiss,
        titleContent = {
            Text(text(R.string.room_chat), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            WatchVoiceCompactControl(manager)
        },
    ) { WatchChatContent(manager.interactions) }
}

/** Direct conversation actions shared by the player and the room chat. */
@Composable
internal fun WatchVoiceCompactControl(manager: WatchTogetherManager) {
    val voice by manager.voice.state.collectAsState()
    val social by manager.interactions.state.collectAsState()
    val context = LocalContext.current
    val text = rememberRoomText()
    var options by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            manager.voice.requestMicrophone()
        } else {
            Toast.makeText(context, text(R.string.room_voice_permission), Toast.LENGTH_LONG).show()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                when {
                    !voice.available && voice.active -> manager.voice.leave()
                    !voice.active -> manager.voice.requestJoin()
                    !voice.muted -> manager.voice.mute()
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> manager.voice.requestMicrophone()
                    else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = social.supported && (voice.active || social.connected),
        ) {
            Icon(
                when {
                    !voice.active -> Icons.Default.Headphones
                    voice.muted -> Icons.Default.MicOff
                    else -> Icons.Default.Mic
                },
                text(
                    when {
                        !voice.available && voice.active -> R.string.room_voice_leave
                        !voice.available -> R.string.room_retry
                        !voice.active -> R.string.room_voice_join
                        voice.muted -> R.string.room_voice_unmute
                        else -> R.string.room_voice_mute
                    },
                ),
                tint = if (voice.active && !voice.muted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Box {
            IconButton(onClick = { options = true }) {
                Icon(Icons.Default.MoreHoriz, text(R.string.room_options))
            }
            DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                if (voice.active) {
                    Text(
                        text(if (voice.muted) R.string.room_voice_muted else R.string.room_voice_live),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Column(Modifier.width(260.dp).padding(horizontal = 16.dp)) {
                        Text(text(R.string.room_voice_volume), style = MaterialTheme.typography.labelMedium)
                        Slider(voice.gain, onValueChange = manager.voice::setGain)
                    }
                    DropdownMenuItem(
                        text = { Text(text(R.string.room_voice_leave)) },
                        onClick = {
                            options = false
                            manager.voice.leave()
                        },
                    )
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text(text(R.string.room_reactions)) },
                    trailingIcon = { Switch(social.reactionsEnabled, onCheckedChange = null) },
                    onClick = { manager.interactions.setReactions(!social.reactionsEnabled) },
                )
                DropdownMenuItem(
                    text = { Text(text(R.string.room_chat_overlay)) },
                    trailingIcon = { Switch(social.overlayEnabled, onCheckedChange = null) },
                    onClick = { manager.interactions.setOverlay(!social.overlayEnabled) },
                )
                Text(
                    text(R.string.room_chat_temporary),
                    Modifier.width(260.dp).padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.WatchChatContent(session: WatchInteractionSession) {
    val state by session.state.collectAsState()
    val text = rememberRoomText()
    val list = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val following by remember {
        derivedStateOf {
            list.firstVisibleItemIndex == 0 &&
                list.firstVisibleItemScrollOffset < 24
        }
    }
    val motion = modernMotionEnabled()
    val compactKeyboard = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val draft = state.draft
    var reactions by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(following) {
        session.chatVisible(following)
        onDispose { session.chatVisible(false) }
    }
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (following && state.messages.isNotEmpty()) {
            if (motion) list.animateScrollToItem(0) else list.scrollToItem(0)
        }
    }
    if (!state.supported) {
        Text(
            text(R.string.room_features_update),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!state.connected) {
        Text(
            text(R.string.room_chat_waiting),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (compactKeyboard) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        state = list,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.messages.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        null,
                        Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text(R.string.room_chat_empty),
                        Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!compactKeyboard) {
                        Text(
                            text(R.string.room_chat_temporary),
                            Modifier.padding(top = 8.dp).widthIn(max = 280.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
        items(state.messages.asReversed(), key = { it.id }) { message ->
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = if (message.own) Alignment.End else Alignment.Start,
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (message.own) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (!message.own) {
                            Text(
                                message.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (message.own) {
                    if (message.delivery ==
                        WatchChatDelivery.Failed
                    ) {
                        TextButton(onClick = { session.retryChat(message.id) }) {
                            Text(text(R.string.room_chat_retry), color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text(
                            text(
                                if (message.delivery ==
                                    WatchChatDelivery.Sending
                                ) {
                                    R.string.room_chat_sending
                                } else {
                                    R.string.room_chat_sent
                                },
                            ),
                            Modifier.padding(top = 3.dp, end = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (!following) {
        TextButton(onClick = {
            scrollScope.launch { if (motion) list.animateScrollToItem(0) else list.scrollToItem(0) }
        }) {
            Text(text(R.string.room_chat_latest))
        }
    }
    AnimatedVisibility(
        reactions && !compactKeyboard,
        enter = fadeIn(tween(if (motion) 160 else 0)),
        exit = fadeOut(tween(if (motion) 100 else 0)),
    ) { WatchReactionPicker(session) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = draft,
            onValueChange = session::setDraft,
            modifier = Modifier.weight(1f),
            placeholder = { Text(text(R.string.room_chat_placeholder)) },
            leadingIcon = if (state.reactionsEnabled && !compactKeyboard) {
                {
                    IconButton(onClick = { reactions = !reactions }) {
                        Icon(Icons.Default.SentimentSatisfiedAlt, text(R.string.room_reactions))
                    }
                }
            } else {
                null
            },
            maxLines = if (compactKeyboard) 1 else 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (state.supported && draft.isNotBlank()) session.sendChat(draft)
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(22.dp),
            supportingText = if (draft.length > 900) ({ Text("${draft.length}/1000") }) else null,
        )
        FilledIconButton(
            onClick = { session.sendChat(draft) },
            modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
            enabled =
            state.supported && draft.isNotBlank(),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                text(R.string.room_chat_send),
            )
        }
    }
}

@Composable
private fun WatchVoicePanel(manager: WatchTogetherManager, onDismiss: () -> Unit) {
    WatchAdaptivePanel(rememberRoomText()(R.string.room_voice), onDismiss, scrollable = true) {
        WatchVoiceContent(manager)
    }
}

@Composable
internal fun ColumnScope.WatchVoiceContent(manager: WatchTogetherManager) {
    val voice by manager.voice.state.collectAsState()
    val social by manager.interactions.state.collectAsState()
    val room by manager.controller.state.collectAsState()
    val text = rememberRoomText()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) manager.voice.requestMicrophone()
    }
    Text(text(R.string.room_voice_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    if (!social.supported) Text(text(R.string.room_features_update), style = MaterialTheme.typography.bodySmall)
    if (!voice.available) Text(text(R.string.room_voice_device), color = colors.error)
    if (!voice.active) {
        Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(24.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Default.Headphones, null, Modifier.size(32.dp), tint = colors.primary)
                Text(
                    text(R.string.room_voice_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Button(
                    onClick = manager.voice::requestJoin,
                    enabled = social.supported && social.connected,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(text(if (voice.available) R.string.room_voice_join else R.string.room_retry)) }
            }
        }
    } else {
        Surface(
            onClick = {
                if (!voice.muted) {
                    manager.voice.mute()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    manager.voice.requestMicrophone()
                } else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = voice.available,
            color = if (voice.muted) colors.surfaceContainerHigh else colors.secondaryContainer,
            shape = RoundedCornerShape(24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (voice.muted) colors.surfaceContainerHighest else colors.secondary,
                    contentColor = if (voice.muted) colors.onSurface else colors.onSecondary,
                ) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Icon(if (voice.muted) Icons.Default.MicOff else Icons.Default.Mic, null, Modifier.size(26.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text(if (voice.muted) R.string.room_voice_muted else R.string.room_voice_live),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text(if (voice.muted) R.string.room_voice_unmute else R.string.room_voice_mute),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (voice.muted) colors.onSurfaceVariant else colors.onSecondaryContainer,
                    )
                }
            }
        }
        if (denied ||
            voice.microphoneUnavailable
        ) {
            Text(
                text(R.string.room_voice_permission),
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text(R.string.room_voice_volume),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "${(voice.gain * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Slider(voice.gain, onValueChange = { manager.voice.setGain(it) })
            }
        }
        val joined = social.voices.filter { it.value.joined && it.key != room.localMemberId }
        if (joined.isEmpty()) {
            Text(
                text(R.string.room_voice_waiting),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        joined.forEach { (id, presence) ->
            val status = voice.peers[id]?.phase ?: RoomVoicePhase.Connecting
            Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (presence.muted) Icons.Default.MicOff else Icons.Default.Mic,
                        null,
                        Modifier.size(20.dp),
                        tint = colors.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            room.members.firstOrNull {
                                it.id == id
                            }?.name ?: text(R.string.room_friend),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text(
                                when (status) {
                                    RoomVoicePhase.Connected -> if (presence.muted) {
                                        R.string.room_voice_muted
                                    } else {
                                        R.string.room_voice_live
                                    }
                                    RoomVoicePhase.Unavailable -> R.string.room_voice_network
                                    else -> R.string.room_voice_connecting
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (voice.peers.values.any { it.phase == RoomVoicePhase.Unavailable } || !voice.available) {
            TextButton(onClick = manager.voice::retry) { Text(text(R.string.room_retry)) }
        }
        if (voice.peers.values.any {
                it.phase == RoomVoicePhase.Connecting || it.phase == RoomVoicePhase.Reconnecting
            }
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        TextButton(onClick = manager.voice::leave, modifier = Modifier.align(Alignment.End)) {
            Text(text(R.string.room_voice_leave), color = colors.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchAdaptivePanel(
    title: String,
    onDismiss: () -> Unit,
    scrollable: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    overVideo: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val compactKeyboard = landscape && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val body: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, rememberRoomText()(R.string.room_close_dialog))
            }
        }
        if (!compactKeyboard) header?.invoke()
        if (scrollable) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        } else {
            content()
        }
    }
    if (landscape) {
        Dialog(
            onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { if (overVideo) window?.setDimAmount(0f) }
            // The dialog owns IME insets, instead of allowing Android's adjustPan
            // to scroll the whole conversation (including its header) out of view.
            // Measure inside the combined keyboard, system-bar and cutout area once.
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val panelWidth = (maxWidth * .48f).coerceIn(320.dp, 440.dp).coerceAtMost(maxWidth)
                Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
                Surface(
                    Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight(),
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = body,
                    )
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            scrimColor = if (overVideo) Color.Transparent else BottomSheetDefaults.ScrimColor,
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(
                    if (overVideo && WindowInsets.ime.getBottom(LocalDensity.current) == 0) .64f else .82f,
                )
                    .imePadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = body,
            )
        }
    }
}
