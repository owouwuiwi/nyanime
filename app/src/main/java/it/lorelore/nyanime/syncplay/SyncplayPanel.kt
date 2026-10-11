package it.lorelore.nyanime.syncplay

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R

@Composable
fun SyncplayPanel(onBack: () -> Unit, onChooseVideo: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { SyncplayManager.get(context) }
    val state by manager.state.collectAsState()
    val video by manager.video.collectAsState()
    val paused by manager.paused.collectAsState()
    val approved by manager.approved.collectAsState()
    val aligning by manager.aligning.collectAsState()
    val busy = state.phase == SyncplayPhase.Connecting
    val connected = state.phase == SyncplayPhase.Connected
    var endpoint by rememberSaveable { mutableStateOf(manager.server) }
    var nickname by rememberSaveable { mutableStateOf(manager.nickname) }
    var room by rememberSaveable { mutableStateOf(manager.room) }
    // Passwords and privacy consent never enter saved activity state, preferences or backups.
    var password by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var tls by rememberSaveable { mutableStateOf(manager.tls) }
    var invalid by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val motion = modernMotionEnabled()
    LaunchedEffect(connected) { if (connected) password = "" }
    Column(
        Modifier.widthIn(max = 600.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Sync, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(stringResource(R.string.syncplay_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.syncplay_compatibility),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(stringResource(R.string.syncplay_intro), style = MaterialTheme.typography.bodyMedium)
        AnimatedContent(state.phase, transitionSpec = {
            ModernMotion.transform(motion)
        }, label = "syncplay-status") { phase ->
            when (phase) {
                SyncplayPhase.Connecting -> Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.syncplay_connecting))
                }
                SyncplayPhase.Connected -> Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.room, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.syncplay_connected, state.username, state.serverVersion))
                        Text(
                            stringResource(
                                if (state.tls) R.string.syncplay_tls_active else R.string.syncplay_plain_warning,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                SyncplayPhase.Failed -> Text(
                    stringResource(
                        when (state.error) {
                            "tls" -> R.string.syncplay_error_tls
                            "protocol" -> R.string.syncplay_error_protocol
                            "server" -> R.string.syncplay_error_server
                            else -> R.string.syncplay_error_connection
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
                SyncplayPhase.Idle -> Unit
            }
        }
        if (!connected) {
            OutlinedTextField(
                endpoint,
                {
                    endpoint = it.take(300)
                    invalid = false
                },
                Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                label = { Text(stringResource(R.string.syncplay_server)) },
                placeholder = {
                    Text("server.example:8999")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                nickname,
                {
                    nickname = it.take(16)
                    invalid = false
                },
                Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                label = { Text(stringResource(R.string.syncplay_nickname)) },
            )
            OutlinedTextField(
                room,
                {
                    room = it.take(35)
                    invalid = false
                },
                Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                label = { Text(stringResource(R.string.syncplay_room)) },
            )
            OutlinedTextField(
                password,
                { password = it.take(256) },
                Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.syncplay_password)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.syncplay_tls), Modifier.weight(1f))
                Switch(tls, {
                    tls = it
                    consent = false
                }, enabled = !busy)
            }
            if (!tls) Text(stringResource(R.string.syncplay_plain_warning), color = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.syncplay_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, { consent = it }, enabled = !busy)
                Text(stringResource(R.string.syncplay_consent), style = MaterialTheme.typography.bodyMedium)
            }
            if (invalid) Text(stringResource(R.string.syncplay_error_fields), color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                runCatching {
                    val options = SyncplayOptions(
                        SyncplayEndpoint.parse(endpoint),
                        nickname.trim(),
                        room.trim(),
                        tls,
                        password,
                    ).validate()
                    manager.connect(options)
                }.onFailure { invalid = true }
            }, Modifier.fillMaxWidth(), enabled = !busy && consent) { Text(stringResource(R.string.syncplay_connect)) }
        } else {
            Text(stringResource(R.string.syncplay_current_video), style = MaterialTheme.typography.titleMedium)
            Text(
                video.ifBlank {
                    stringResource(R.string.syncplay_no_video)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.syncplay_video_hint), style = MaterialTheme.typography.bodySmall)
            if (!approved) {
                Button(manager::approveVideo, Modifier.fillMaxWidth(), enabled = video.isNotBlank()) {
                    Text(stringResource(R.string.syncplay_approve_video))
                }
            }
            if (aligning) Text(stringResource(R.string.syncplay_aligning), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ manager.requestPause(!paused) }, Modifier.weight(1f), enabled = approved && !aligning) {
                    Text(stringResource(if (paused) R.string.syncplay_play else R.string.syncplay_pause))
                }
                OutlinedButton(onChooseVideo, Modifier.weight(1f)) {
                    Text(stringResource(R.string.syncplay_choose_video))
                }
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.syncplay_members, state.members.size),
                style = MaterialTheme.typography.titleMedium,
            )
            state.members.forEach { member ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(member.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        member.file.ifBlank {
                            stringResource(R.string.syncplay_no_video)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val duration = member.duration
                    if (duration != null) {
                        Text(
                            stringResource(R.string.syncplay_duration, duration.toInt() / 60),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.chat) {
                Text(stringResource(R.string.syncplay_chat), style = MaterialTheme.typography.titleMedium)
                state.messages.takeLast(20).forEach { chat ->
                    Text("${chat.name}: ${chat.text}", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    message,
                    { message = it.take(150) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.syncplay_message)) },
                    maxLines = 4,
                )
                TextButton({ if (manager.chat(message.trim())) message = "" }, enabled = message.isNotBlank()) {
                    Text(stringResource(R.string.syncplay_send))
                }
            }
        }
        if (busy || connected || state.phase == SyncplayPhase.Failed) {
            OutlinedButton({
                manager.leave()
                password = ""
            }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.syncplay_disconnect)) }
        }
        TextButton({
            manager.leave()
            onBack()
        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.syncplay_back)) }
    }
}
