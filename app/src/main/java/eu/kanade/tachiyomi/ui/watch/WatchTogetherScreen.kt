package eu.kanade.tachiyomi.ui.watch

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.RoomText
import eu.kanade.tachiyomi.data.watch.WatchInvite
import eu.kanade.tachiyomi.data.watch.WatchOpeningState
import eu.kanade.tachiyomi.data.watch.WatchPhase
import eu.kanade.tachiyomi.data.watch.WatchProblem
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.data.watch.WatchShortRooms
import eu.kanade.tachiyomi.data.watch.WatchShortState
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import eu.kanade.tachiyomi.data.watch.description
import eu.kanade.tachiyomi.data.watch.roomMessage
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity

class WatchTogetherActivity : BaseActivity() {
    private var incomingCode by mutableStateOf("")
    private var inviteError by mutableStateOf<String?>(null)

    private fun acceptIntent(intent: Intent) {
        if (intent.action == OPEN_SHARED) {
            val manager = WatchTogetherManager.get(this)
            manager.present(this)
            val room = manager.controller.state.value
            when {
                room.sharedReadingMode ->
                    eu.kanade.tachiyomi.data.reading.ReadingTogetherManager.get(this).reachShared(this)
                room.liveMode -> manager.openSelectedLive()
                room.media != null -> manager.openSelectedVideo()
            }
            intent.action = null
            return
        }
        if (intent.action != Intent.ACTION_VIEW) return
        val parsed = runCatching { WatchInvite.codeFromLink(intent.dataString.orEmpty(), System.currentTimeMillis()) }
        incomingCode = parsed.getOrDefault("")
        inviteError = parsed.exceptionOrNull()?.roomMessage(RoomText.from(this))
        // Keep invitation secrets out of saved activity state and subsequent launches.
        intent.data = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptIntent(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerSecureActivity(this)
        enableEdgeToEdge()
        acceptIntent(intent)
        WatchTogetherManager.get(this).present(this)
        setContent {
            TachiyomiTheme {
                val text = rememberRoomText()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text(R.string.room_title)) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, text(R.string.room_back))
                                }
                            },
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                        WatchTogetherPanel(
                            onChooseVideo = {
                                if (isTaskRoot) {
                                    startActivity(
                                        Intent(
                                            this@WatchTogetherActivity,
                                            eu.kanade.tachiyomi.ui.main.MainActivity::class.java,
                                        ),
                                    )
                                }
                                finish()
                            },
                            incomingCode = incomingCode,
                            inviteError = inviteError,
                            onInviteConsumed = {
                                incomingCode = ""
                                inviteError = null
                            },
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val OPEN_SHARED = "nyanime.watch.OPEN_SHARED"
    }
}

@Composable
fun WatchTogetherSheet(onDismiss: () -> Unit) {
    PlayerSheet(onDismissRequest = onDismiss) { WatchTogetherPanel(onChooseVideo = onDismiss) }
}

@Composable
fun WatchTogetherButton() {
    val text = rememberRoomText()
    val context = LocalContext.current
    IconButton(onClick = { context.startActivity(Intent(context, WatchTogetherActivity::class.java)) }) {
        Icon(Icons.Default.Group, text(R.string.room_watch))
    }
}

@Composable
fun WatchTogetherPanel(
    onChooseVideo: () -> Unit,
    incomingCode: String = "",
    inviteError: String? = null,
    onInviteConsumed: () -> Unit = {},
) {
    val text = rememberRoomText()
    val context = LocalContext.current
    val manager = remember { WatchTogetherManager.get(context) }
    val room by manager.controller.state.collectAsState()
    val live by manager.tv.state.collectAsState()
    val short by manager.shortRooms.state.collectAsState()
    val opening by manager.opening.collectAsState()
    val preparation by manager.preparation.collectAsState()
    val recoverable by manager.recoverable.collectAsState()
    val saveFailed by manager.roomSaveFailed.collectAsState()
    var showQr by remember { mutableStateOf(false) }
    val invitationLink = remember(short.code) {
        runCatching { WatchShortRooms.link(short.code) }.getOrNull()
    }
    if (showQr && room.active && invitationLink != null) {
        WatchQrDialog(invitationLink) { showQr = false }
    }
    var name by rememberSaveable { mutableStateOf(manager.displayName) }
    var copied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }
    DisposableEffect(manager) {
        (context as? Activity)?.let(manager::present)
        onDispose {}
    }
    var readingPanel by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(room.sharedReadingMode) { readingPanel = room.sharedReadingMode }
    if (readingPanel && room.active) {
        eu.kanade.tachiyomi.ui.reading.ReadingRoomPanel(
            manager = eu.kanade.tachiyomi.data.reading.ReadingTogetherManager.get(context),
            onChooseManga = {
                context.startActivity(
                    Intent(context, eu.kanade.tachiyomi.ui.main.MainActivity::class.java).apply {
                        action = eu.kanade.tachiyomi.core.common.Constants.SHORTCUT_LIBRARY
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
            },
            onVideo = { readingPanel = false },
        )
        return
    }
    WatchTogetherContent(
        room = room, liveChannel = live.selection?.name, short = short, opening = opening, name = name, onName = {
            name =
                it.take(32)
        },
        incomingCode = incomingCode, inviteError = inviteError, preparation = preparation,
        recoverable = recoverable,
        saveFailed = saveFailed,
        onResumeRoom = manager::resumePendingRoom,
        onDiscardRoom = manager::discardPendingRoom,
        onQr = { showQr = true },
        onRead = { readingPanel = true },
        onSkip = manager.controller::requestSkip, onCancelSkip = manager.controller::cancelSkip,
        onNext = manager.controller::playNextNow, onCancelNext = manager.controller::cancelNext,
        onExtensions = {
            context.startActivity(
                Intent(context, eu.kanade.tachiyomi.ui.main.MainActivity::class.java).apply {
                    action = eu.kanade.tachiyomi.core.common.Constants.SHORTCUT_ANIMEEXTENSIONS
                },
            )
        },
        onCreate = {
            manager.displayName = name
            manager.createRoom(name)
        },
        onJoin = { code ->
            manager.displayName = name
            manager.joinRoom(code, name)
            onInviteConsumed()
        },
        onCancelShort = manager.shortRooms::close,
        onApproveShort = manager.shortRooms::approve,
        onRejectShort = manager.shortRooms::reject,
        onCopy = {
            context.getSystemService(
                ClipboardManager::class.java,
            ).setPrimaryClip(ClipData.newPlainText(text(R.string.room_code), short.code))
            copied = true
        },
        copied = copied,
        onShare = {
            if (short.code.isNotBlank() && short.relayCount > 0 && room.relayCount > 0) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, WatchShortRooms.shareText(short.code, text))
                }
                context.startActivity(Intent.createChooser(intent, text(R.string.room_invite_friend)))
            }
        },
        onTogglePlayback = {
            if (room.wantsPlayback &&
                !room.localHold
            ) {
                manager.controller.requestPause(true)
            } else {
                manager.controller.resumeByUser()
            }
        },
        onResync = {
            manager.controller.resync()
            manager.retryOpening()
            manager.retryPreparation()
        },
        onLeave = manager.controller::leave,
        onSharedControls = manager.controller::setSharedControls,
        onWaitForEveryone = manager.controller::setWaitForEveryone,
        onPrebufferOnStart = manager.controller::setPrebufferOnStart,
        onChooseVideo = onChooseVideo,
        onOpenPlayer = if (context is eu.kanade.tachiyomi.ui.player.PlayerActivity) {
            null
        } else {
            manager::openSelectedVideo
        },
    )
}

/** Pure UI so phone, landscape and large-text layouts can be verified without a running player. */
@Composable
fun WatchTogetherContent(
    room: WatchRoomState,
    opening: WatchOpeningState,
    name: String,
    onName: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onTogglePlayback: () -> Unit,
    onResync: () -> Unit,
    onLeave: () -> Unit,
    onSharedControls: (Boolean) -> Unit,
    onWaitForEveryone: (Boolean) -> Unit,
    onChooseVideo: () -> Unit,
    onPrebufferOnStart: (Boolean) -> Unit = {},
    short: WatchShortState = WatchShortState(),
    onCancelShort: () -> Unit = {},
    onApproveShort: (String) -> Unit = {},
    onRejectShort: (String) -> Unit = {},
    copied: Boolean = false,
    onOpenPlayer: (() -> Unit)? = null,
    incomingCode: String = "",
    inviteError: String? = null,
    preparation: WatchOpeningState = WatchOpeningState(),
    onQr: () -> Unit = {},
    onSkip: () -> Unit = {},
    onCancelSkip: () -> Unit = {},
    onNext: () -> Unit = {},
    onCancelNext: () -> Unit = {},
    onExtensions: () -> Unit = {},
    onRead: () -> Unit = {},
    recoverable: Boolean = false,
    saveFailed: Boolean = false,
    onResumeRoom: () -> Boolean = { false },
    onDiscardRoom: () -> Unit = {},
    liveChannel: String? = null,
) {
    val text = rememberRoomText()
    var joining by rememberSaveable { mutableStateOf(false) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(incomingCode) {
        if (incomingCode.isNotBlank()) {
            code = incomingCode
            joining = true
        }
    }
    var options by rememberSaveable { mutableStateOf(false) }
    var resumeError by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val motionDuration = if (modernMotionEnabled()) 180 else 0
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(56.dp).background(
                    Brush.linearGradient(listOf(colors.primary, colors.tertiary)),
                    RoundedCornerShape(18.dp),
                ),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Group, null, Modifier.size(30.dp), tint = colors.onPrimary) }
            Column(Modifier.weight(1f)) {
                Text(
                    text(R.string.room_watch),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (room.active) text(R.string.room_your_room) else text(R.string.room_intro),
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (inviteError != null) Text(inviteError, color = colors.error)
        if (room.active && saveFailed) {
            Text(
                text(R.string.room_save_pending),
                color = colors.error,
            )
        }
        if (room.active && incomingCode.isNotBlank() && incomingCode != room.invite && incomingCode != short.code) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text(R.string.room_another_invite), style = MaterialTheme.typography.titleMedium)
                    Text(text(R.string.room_switch_hint), color = colors.onSurfaceVariant)
                    Button(onClick = { onJoin(incomingCode) }) { Text(text(R.string.room_switch)) }
                }
            }
        }
        if (!room.active) {
            if (short.waiting) {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text(R.string.room_confirmation),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            short.code,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            if (short.relayCount > 0) {
                                text(R.string.room_request_sent)
                            } else {
                                short.message
                            },
                            color = colors.onSurfaceVariant,
                        )
                        TextButton(onClick = onCancelShort) { Text(text(R.string.room_cancel)) }
                    }
                }
            }
            if (!short.waiting && recoverable) {
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text(R.string.room_resume_room),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(text(R.string.room_previous_session), color = colors.onSurfaceVariant)
                        Button(onClick = { resumeError = !onResumeRoom() }, modifier = Modifier.fillMaxWidth()) {
                            Text(text(R.string.room_rejoin))
                        }
                        TextButton(onClick = onDiscardRoom) { Text(text(R.string.room_discard_session)) }
                        if (resumeError) {
                            Text(text(R.string.room_rejoin_failed), color = colors.error)
                        }
                    }
                }
            }
            if (!short.waiting) {
                if (room.message.isNotBlank()) {
                    Text(
                        room.message,
                        color = if (room.phase ==
                            WatchPhase.Failed
                        ) {
                            colors.error
                        } else {
                            colors.onSurfaceVariant
                        },
                    )
                }
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            if (joining) text(R.string.room_join_title) else text(R.string.room_create_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (joining) {
                                text(R.string.room_enter_code)
                            } else {
                                text(R.string.room_create_hint)
                            },
                            color = colors.onSurfaceVariant,
                        )
                        AnimatedVisibility(
                            visible = joining,
                            enter = fadeIn(tween(motionDuration)),
                            exit = fadeOut(tween(motionDuration)),
                        ) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = WatchShortRooms.normalizeInput(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        if (code.length >
                                            8
                                        ) {
                                            text(R.string.room_received_invite)
                                        } else {
                                            text(R.string.room_eight_digit_code)
                                        },
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (code.length > 8) KeyboardType.Text else KeyboardType.Number,
                                ),
                                shape = RoundedCornerShape(14.dp),
                            )
                        }
                        val canJoin = code.matches(Regex("[0-9]{8}")) ||
                            runCatching { WatchInvite.parse(code, System.currentTimeMillis()) }.isSuccess
                        Button(
                            onClick = { if (joining) onJoin(code) else onCreate() },
                            enabled = !joining || canJoin,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(if (joining) text(R.string.room_request_join) else text(R.string.room_create)) }
                        TextButton(onClick = { joining = !joining }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (joining) text(R.string.room_create_instead) else text(R.string.room_have_code))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text(R.string.room_name_label, name.ifBlank { text(R.string.room_viewer) }),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(onClick = { editingName = !editingName }) {
                        Text(if (editingName) text(R.string.room_done) else text(R.string.room_edit))
                    }
                }
                AnimatedVisibility(
                    editingName,
                    enter = fadeIn(tween(motionDuration)),
                    exit = fadeOut(tween(motionDuration)),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = onName,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text(R.string.room_your_name)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }
        } else {
            if (room.host && short.code.isNotBlank()) {
                val invitationsReady = short.relayCount > 0 && room.relayCount > 0
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text(R.string.room_invite_friend),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(onClick = onQr, enabled = invitationsReady) { Text("QR") }
                        }
                        SelectionContainer {
                            Text(
                                short.code,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            when {
                                invitationsReady -> text(R.string.room_temporary_code)
                                room.relayCount == 0 -> room.message
                                else -> short.message
                            },
                            color = colors.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = onCopy,
                                enabled = invitationsReady,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                                    null,
                                    Modifier.size(18.dp),
                                )
                                Text(if (copied) text(R.string.room_copied) else text(R.string.room_copy))
                            }
                            Button(onClick = onShare, enabled = invitationsReady, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                                Text(text(R.string.room_share))
                            }
                        }
                    }
                }
            }
            if (room.host && short.requests.isNotEmpty()) {
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text(R.string.room_join_requests),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        short.requests.forEach { request ->
                            Text("${request.name} · ${request.id.takeLast(6)}")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { onApproveShort(request.id) }) { Text(text(R.string.room_accept)) }
                                OutlinedButton(onClick = {
                                    onRejectShort(request.id)
                                }) { Text(text(R.string.room_reject)) }
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                Text(text(R.string.room_read))
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        liveChannel ?: room.media?.title
                            ?: if (room.host) text(R.string.room_choose_watch) else text(R.string.room_friend_choosing),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    room.media?.episode?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.onSurfaceVariant) }
                    if (opening.loading ||
                        room.preparingPlayback ||
                        room.phase in listOf(WatchPhase.Connecting, WatchPhase.Reconnecting, WatchPhase.Buffering)
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                    Text(
                        opening.error ?: when {
                            opening.loading -> text(R.string.room_opening_episode)
                            room.preparingPlayback -> room.playbackPreparationMessage(text)
                            room.host && room.relayCount > 0 && short.relayCount == 0 -> text(
                                R.string.room_preparing_invites,
                            )
                            else -> room.message
                        },
                        color = if (opening.error != null) colors.error else colors.onSurfaceVariant,
                    )
                    if (opening.error != null) {
                        TextButton(
                            onClick = if (opening.problem ==
                                WatchProblem.MissingSource
                            ) {
                                onExtensions
                            } else {
                                onResync
                            },
                        ) {
                            Text(
                                if (opening.problem ==
                                    WatchProblem.MissingSource
                                ) {
                                    text(R.string.room_open_extensions)
                                } else {
                                    text(R.string.room_retry)
                                },
                            )
                        }
                    }
                    if (room.host &&
                        room.media == null &&
                        !room.liveMode
                    ) {
                        Button(onClick = onChooseVideo) { Text(text(R.string.room_choose_video)) }
                    }
                    if ((room.media != null || room.liveMode) && onOpenPlayer != null) {
                        OutlinedButton(onClick = onOpenPlayer, enabled = !opening.loading) {
                            Text(text(R.string.room_open_player))
                        }
                    }
                }
            }
            if (room.members.isNotEmpty()) {
                Text(text(R.string.room_member_count, room.members.size), style = MaterialTheme.typography.titleSmall)
                room.members.forEachIndexed { index, member ->
                    val participant: @Composable (Modifier) -> Unit = { nameModifier ->
                        Text(member.name + if (index == 0) " · Host" else "", nameModifier)
                    }
                    val status: @Composable () -> Unit = {
                        Text(
                            if (member.observer) {
                                text(R.string.playback_observing_short)
                            } else if (member.problem != WatchProblem.None) {
                                member.problem.description(text)
                            } else if (member.buffering) {
                                text(R.string.room_loading)
                            } else if (room.prebuffering && member.ready) {
                                member.bufferedAheadSeconds?.let {
                                    text(R.string.room_buffered, it.coerceAtMost(15))
                                } ?: text(R.string.room_prebuffering)
                            } else if (member.reading) {
                                text(R.string.room_reading)
                            } else if (member.ready && member.positionSeconds != null) {
                                val seconds = member.positionSeconds.toInt().coerceAtLeast(0)
                                text(
                                    R.string.room_watch_position,
                                    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                                )
                            } else if (member.ready) {
                                text(R.string.room_ready)
                            } else {
                                text(R.string.room_waiting)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (member.ready && !member.buffering) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                    if (member.problem != WatchProblem.None || LocalDensity.current.fontScale > 1.3f) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            participant(Modifier)
                            status()
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            participant(Modifier.weight(1f).padding(end = 12.dp))
                            status()
                        }
                    }
                }
            }
            WatchRoomCues(room, onSkip, onCancelSkip, onNext, onCancelNext)
            if (preparation.error != null) {
                Text(text(R.string.room_next_error, preparation.error), color = colors.error)
                TextButton(
                    onClick = if (preparation.problem ==
                        WatchProblem.MissingSource
                    ) {
                        onExtensions
                    } else {
                        onResync
                    },
                ) {
                    Text(
                        if (preparation.problem ==
                            WatchProblem.MissingSource
                        ) {
                            text(R.string.room_open_extensions)
                        } else {
                            text(R.string.room_retry_preparation)
                        },
                    )
                }
            }
            if (room.media != null && (room.sharedControls || room.host || room.localHold)) {
                Button(
                    onClick = onTogglePlayback,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled =
                    room.phase !in listOf(
                        WatchPhase.Connecting,
                        WatchPhase.Reconnecting,
                        WatchPhase.DifferentVideo,
                    ),
                    shape = RoundedCornerShape(14.dp),
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
                    )
                    Text(
                        when {
                            !room.sharedControls && !room.host -> text(R.string.room_return_watch)
                            room.wantsPlayback && !room.localHold -> text(R.string.room_pause_all)
                            else -> text(R.string.room_resume_together)
                        },
                    )
                }
            }
            if (!room.liveMode) {
                TextButton(onClick = {
                    options = !options
                }) { Text(if (options) text(R.string.room_hide_options) else text(R.string.room_options)) }
            }
            AnimatedVisibility(
                options && !room.liveMode,
                enter = fadeIn(tween(motionDuration)),
                exit = fadeOut(tween(motionDuration)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (room.host) {
                        WatchOption(text(R.string.room_shared_controls), room.sharedControls, onSharedControls)
                        WatchOption(text(R.string.room_wait_for_everyone), room.waitForEveryone, onWaitForEveryone)
                        WatchOption(text(R.string.room_prebuffer), room.prebufferOnStart, onPrebufferOnStart)
                        if (room.prebufferOnStart) {
                            Text(
                                text(R.string.room_prebuffer_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (!room.waitForEveryone) {
                            Text(
                                text(R.string.room_buffer_grace_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text(R.string.room_connections, room.relayCount) +
                            (room.latencyMs?.let { text(R.string.room_latency, it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(onClick = onResync) { Text(text(R.string.room_resync)) }
                }
            }
            HorizontalDivider()
            TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Text(if (room.host) text(R.string.room_close) else text(R.string.room_leave), color = colors.error)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun WatchOption(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun WatchRoomChip(room: WatchRoomState, onClick: () -> Unit) {
    val text = rememberRoomText()
    if (room.active) {
        AssistChip(
            onClick = onClick,
            label = {
                Text(
                    when {
                        room.resumeSeconds != null -> text(R.string.room_resume_countdown, room.resumeSeconds)
                        room.preparingPlayback -> text(R.string.room_chip_preparing)
                        room.phase == WatchPhase.Playing -> text(R.string.room_together_count, room.members.size)
                        room.phase in listOf(
                            WatchPhase.Connecting,
                            WatchPhase.Reconnecting,
                        ) -> text(R.string.room_chip_connecting)
                        room.phase == WatchPhase.Buffering -> text(R.string.room_chip_loading)
                        else -> text(R.string.room_chip_paused)
                    },
                )
            },
            leadingIcon = { Icon(Icons.Default.Group, null, Modifier.size(18.dp)) },
        )
    }
}
