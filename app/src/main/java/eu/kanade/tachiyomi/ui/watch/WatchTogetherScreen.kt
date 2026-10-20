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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
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
                val room by WatchTogetherManager.get(this@WatchTogetherActivity).controller.state.collectAsState()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(text(if (room.active) R.string.room_lounge_title else R.string.room_watch))
                            },
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
                            showHeading = false,
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
    showHeading: Boolean = true,
    onInviteConsumed: () -> Unit = {},
) {
    val text = rememberRoomText()
    val context = LocalContext.current
    val manager = remember { WatchTogetherManager.get(context) }
    val room by manager.controller.state.collectAsState()
    val syncplayActive by it.lorelore.nyanime.syncplay.SyncplayManager.active.collectAsState()
    var showSyncplay by rememberSaveable { mutableStateOf(false) }
    if (!room.active && incomingCode.isBlank() && (showSyncplay || syncplayActive)) {
        it.lorelore.nyanime.syncplay.SyncplayPanel(onBack = { showSyncplay = false }, onChooseVideo = onChooseVideo)
        return
    }
    val live by manager.tv.state.collectAsState()
    val short by manager.shortRooms.state.collectAsState()
    val opening by manager.opening.collectAsState()
    val preparation by manager.preparation.collectAsState()
    val recoverable by manager.recoverable.collectAsState()
    val saveFailed by manager.roomSaveFailed.collectAsState()
    var showQr by remember { mutableStateOf(false) }
    val shortReady = short.code.isNotBlank() && short.relayCount > 0 && room.relayCount > 0
    val invitationLink = remember(short.code, shortReady, room.invite) {
        runCatching {
            if (shortReady) {
                WatchShortRooms.link(
                    short.code,
                )
            } else {
                WatchInvite.parse(room.invite, System.currentTimeMillis()).link()
            }
        }.getOrNull()
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
    val incomingRoom = incomingCode.isNotBlank() && incomingCode != room.invite && incomingCode != short.code
    LaunchedEffect(room.sharedReadingMode, incomingCode, inviteError) {
        readingPanel = room.sharedReadingMode && !incomingRoom && inviteError == null
    }
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
        onSyncplay = { showSyncplay = true },
        room = room, liveChannel = live.selection?.name, short = short, opening = opening, name = name,
        showHeading = showHeading,
        onName = {
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
            if (invitationLink != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        if (shortReady) {
                            WatchShortRooms.shareText(
                                short.code,
                                text,
                            )
                        } else {
                            text(R.string.room_share_full_invite, invitationLink, room.invite)
                        },
                    )
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
        socialContent = { WatchRoomSocialBar(includeLounge = true) },
        onChooseVideo = onChooseVideo,
        onOpenPlayer = if (context is eu.kanade.tachiyomi.ui.player.PlayerActivity) {
            null
        } else {
            manager::openSelectedVideo
        },
    )
}

/** One room surface, shared by the lobby and player; transport details live in its options. */
@OptIn(ExperimentalMaterial3Api::class)
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
    showHeading: Boolean = true,
    socialContent: @Composable () -> Unit = { WatchLounge(room.members) },
    onSyncplay: (() -> Unit)? = null,
) {
    val text = rememberRoomText()
    val colors = MaterialTheme.colorScheme
    val motion = modernMotionEnabled()
    var joining by rememberSaveable { mutableStateOf(false) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf(false) }
    var invite by rememberSaveable { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    var resumeError by remember { mutableStateOf(false) }
    LaunchedEffect(incomingCode) {
        if (incomingCode.isNotBlank()) {
            code = incomingCode
            joining = true
        }
    }
    LaunchedEffect(room.active) {
        if (!room.active) {
            options = false
            invite = false
            leaving = false
        }
    }
    if (room.active && invite) {
        RoomLoungeSheet(onDismiss = { invite = false }, title = text(R.string.room_invite_friend)) {
            WatchLoungeInvite(
                short.code,
                short.relayCount > 0 && room.relayCount > 0,
                copied,
                onCopy,
                onShare,
                onQr,
                inviteReady = room.invite.isNotBlank(),
            )
        }
    }
    if (room.active && options) {
        RoomLoungeSheet(onDismiss = { options = false }, title = text(R.string.room_options)) {
            if (room.host && !room.liveMode) {
                RoomLoungeOption(
                    text(R.string.room_shared_controls),
                    text(R.string.room_lounge_shared_hint),
                    room.sharedControls,
                    onSharedControls,
                )
                RoomLoungeOption(
                    text(R.string.room_lounge_wait_title),
                    text(R.string.room_lounge_wait_hint),
                    room.waitForEveryone,
                    onWaitForEveryone,
                )
                RoomLoungeOption(
                    text(R.string.room_prebuffer),
                    text(R.string.room_prebuffer_hint),
                    room.prebufferOnStart,
                    onPrebufferOnStart,
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            Text(
                text(R.string.room_lounge_connection),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text(R.string.room_connections, room.relayCount) +
                    (room.latencyMs?.let { text(R.string.room_latency, it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            TextButton(onClick = onResync, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null, Modifier.padding(end = 8.dp).size(18.dp))
                Text(text(R.string.room_resync))
            }
            TextButton(onClick = { leaving = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (room.host) text(R.string.room_close) else text(R.string.room_leave), color = colors.error)
            }
        }
    }
    if (leaving && room.active) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(if (room.host) text(R.string.room_close) else text(R.string.room_leave)) },
            text = {
                Text(text(if (room.host) R.string.room_lounge_close_hint else R.string.room_lounge_leave_hint))
            },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    onLeave()
                }) {
                    Text(if (room.host) text(R.string.room_close) else text(R.string.room_leave), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) { Text(text(R.string.room_cancel)) }
            },
        )
    }
    Column(
        Modifier.widthIn(max = 600.dp).fillMaxWidth().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (inviteError != null) RoomLoungeNotice(inviteError, error = true)
        if (room.active && saveFailed) RoomLoungeNotice(text(R.string.room_save_pending), error = true)
        if (room.active && incomingCode.isNotBlank() && incomingCode != room.invite && incomingCode != short.code) {
            RoomLoungeSurface {
                Text(text(R.string.room_another_invite), style = MaterialTheme.typography.titleMedium)
                Text(text(R.string.room_switch_hint), color = colors.onSurfaceVariant)
                Button(onClick = { onJoin(incomingCode) }, modifier = Modifier.fillMaxWidth()) {
                    Text(text(R.string.room_switch))
                }
            }
        }
        if (!room.active) {
            RoomLoungeWelcome(showHeading)
            if (onSyncplay != null) {
                TextButton(onSyncplay, Modifier.fillMaxWidth()) {
                    Text(androidx.compose.ui.res.stringResource(R.string.syncplay_open))
                }
            }
            if (short.waiting) {
                RoomLoungeSurface {
                    Text(
                        text(R.string.room_confirmation),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        short.code.chunked(4).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    Text(
                        if (short.relayCount > 0) text(R.string.room_request_sent) else short.message,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(onClick = onCancelShort, modifier = Modifier.fillMaxWidth()) {
                        Text(text(R.string.room_cancel))
                    }
                }
            } else {
                if (recoverable) {
                    RoomLoungeSurface {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Default.History, null, tint = colors.primary)
                            Column(Modifier.weight(1f)) {
                                Text(text(R.string.room_resume_room), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text(R.string.room_previous_session),
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Button(onClick = { resumeError = !onResumeRoom() }, modifier = Modifier.fillMaxWidth()) {
                            Text(text(R.string.room_rejoin))
                        }
                        TextButton(onClick = onDiscardRoom, modifier = Modifier.fillMaxWidth()) {
                            Text(text(R.string.room_discard_session))
                        }
                        if (resumeError) Text(text(R.string.room_rejoin_failed), color = colors.error)
                    }
                }
                if (room.message.isNotBlank()) {
                    RoomLoungeNotice(room.message, error = room.phase == WatchPhase.Failed)
                }
                RoomLoungeSurface {
                    RoomLoungeEntrySelector(joining, onSelect = { joining = it })
                    Text(
                        if (joining) text(R.string.room_enter_code) else text(R.string.room_create_hint),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AnimatedVisibility(
                        joining,
                        enter = ModernMotion.enter(if (motion) 180 else 0),
                        exit = fadeOut(tween(if (motion) 120 else 0)),
                    ) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = WatchShortRooms.normalizeInput(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    text(
                                        if (code.length >
                                            8
                                        ) {
                                            R.string.room_received_invite
                                        } else {
                                            R.string.room_eight_digit_code
                                        },
                                    ),
                                )
                            },
                            singleLine = true,
                            textStyle = if (code.length <=
                                8
                            ) {
                                MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace)
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (code.length >
                                    8
                                ) {
                                    KeyboardType.Text
                                } else {
                                    KeyboardType.Number
                                },
                            ),
                            shape = RoundedCornerShape(18.dp),
                        )
                    }
                    val canJoin = code.matches(Regex("[0-9]{8}")) ||
                        runCatching { WatchInvite.parse(code, System.currentTimeMillis()) }.isSuccess
                    Button(
                        onClick = { if (joining) onJoin(code) else onCreate() },
                        enabled = !joining || canJoin,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(
                            if (joining) Icons.AutoMirrored.Filled.Login else Icons.Default.Add,
                            null,
                            Modifier.padding(end = 8.dp).size(20.dp),
                        )
                        Text(if (joining) text(R.string.room_request_join) else text(R.string.room_create))
                    }
                }
                Surface(
                    onClick = { editingName = !editingName },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = colors.surfaceContainerLow,
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.PersonOutline, null, tint = colors.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text(R.string.room_your_name),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                            )
                            Text(
                                name.ifBlank {
                                    text(R.string.room_viewer)
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Icon(
                            Icons.Default.Edit,
                            text(R.string.room_edit),
                            Modifier.size(18.dp),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
                AnimatedVisibility(
                    editingName,
                    enter = fadeIn(tween(if (motion) 180 else 0)),
                    exit = fadeOut(tween(if (motion) 120 else 0)),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = onName,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text(R.string.room_your_name)) },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                editingName = false
                            }) { Icon(Icons.Default.Check, text(R.string.room_done)) }
                        },
                        shape = RoundedCornerShape(18.dp),
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    if (showHeading) {
                        Text(
                            text(R.string.room_lounge_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text(R.string.room_lounge_people, room.members.size),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (room.host) {
                    FilledTonalButton(onClick = {
                        invite = true
                    }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.PersonAddAlt1, text(R.string.room_invite_friend), Modifier.size(20.dp))
                        if (LocalDensity.current.fontScale <= 1.3f) {
                            Text(text(R.string.room_lounge_invite), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                IconButton(onClick = { options = true }) { Icon(Icons.Default.Tune, text(R.string.room_options)) }
            }
            if (room.host && short.requests.isNotEmpty()) {
                RoomLoungeSurface(accent = true) {
                    Text(
                        text(R.string.room_join_requests),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    short.requests.forEach { request ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(request.name, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    onApproveShort(request.id)
                                }, modifier = Modifier.weight(1f)) { Text(text(R.string.room_accept)) }
                                OutlinedButton(onClick = {
                                    onRejectShort(request.id)
                                }, modifier = Modifier.weight(1f)) { Text(text(R.string.room_reject)) }
                            }
                        }
                    }
                }
            }
            RoomLoungeNowPlaying(
                room = room,
                opening = opening,
                liveChannel = liveChannel,
                onChooseVideo = onChooseVideo,
                onOpenPlayer = onOpenPlayer,
                onTogglePlayback = onTogglePlayback,
                onRetry = if (opening.problem == WatchProblem.MissingSource) onExtensions else onResync,
            )
            socialContent()
            WatchRoomCues(room, onSkip, onCancelSkip, onNext, onCancelNext)
            if (preparation.error != null) {
                RoomLoungeSurface {
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
                            text(
                                if (preparation.problem ==
                                    WatchProblem.MissingSource
                                ) {
                                    R.string.room_open_extensions
                                } else {
                                    R.string.room_retry_preparation
                                },
                            ),
                        )
                    }
                }
            }
            RoomLoungeMediaActions(onChooseVideo = onChooseVideo, onRead = onRead, host = room.host)
            if (!room.host) {
                TextButton(onClick = { leaving = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(text(R.string.room_leave), color = colors.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
