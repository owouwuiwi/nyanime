package eu.kanade.tachiyomi.ui.cloud

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupExportStore
import eu.kanade.tachiyomi.data.cloud.CloudBackupWorker
import eu.kanade.tachiyomi.data.cloud.CloudRepository
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncManager
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncUnlockRequired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nyanime.telegram.CloudCopy
import nyanime.telegram.CloudPhase
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import nyanime.telegram.TelegramPhoneNumbers
import nyanime.telegram.TelegramState
import nyanime.telegram.tdRequest
import java.text.DateFormat
import java.util.Date
import eu.kanade.tachiyomi.ui.cloud.CloudMotion as ModernMotion
import eu.kanade.tachiyomi.ui.cloud.cloudMotionEnabled as appMotionEnabled

class CloudScreen(private val initialSetup: Boolean = false) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val exitSetup = LocalCloudSetupExit.current
        val repository = remember { CloudRepository.get(context) }
        val sync = remember { CloudSyncManager.get(context) }
        var cloudTab by rememberSaveable { mutableStateOf(if (initialSetup) 2 else 0) }
        val session by repository.client.state.collectAsState()
        val progress by repository.progress.collectAsState()
        val copies by repository.copies.collectAsState()
        val archives by repository.archives.collectAsState()
        val configured by repository.configuredState.collectAsState()
        val hasArchive by repository.selectedArchiveState.collectAsState()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var unlockSpace by remember { mutableStateOf<String?>(null) }
        var settings by remember { mutableStateOf(false) }
        var onlyThisDevice by remember { mutableStateOf(false) }
        var deleteCopy by remember { mutableStateOf<CloudCopy?>(null) }
        var disconnect by remember { mutableStateOf(false) }
        var offerRestore by remember { mutableStateOf(false) }
        var cursor by remember { mutableStateOf(0L) }
        var more by remember { mutableStateOf(false) }
        var showSetupHistory by remember { mutableStateOf(false) }
        val actions = remember { Mutex() }
        var exportRequested by remember { mutableStateOf<CloudCopy?>(null) }

        fun perform(action: suspend () -> Unit) {
            scope.launch {
                actions.withLock {
                    busy = true
                    error = null
                    try {
                        action()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        if (failure is CloudSyncUnlockRequired) unlockSpace = failure.space
                        if (repository.progress.value.phase == CloudPhase.DOWNLOADING) {
                            repository.progress.value = nyanime.telegram.CloudProgress(CloudPhase.FAILED)
                        }
                        error = context.getString(
                            if (failure is CloudSyncUnlockRequired) {
                                R.string.cloud_sync_backup_unlock
                            } else {
                                when ((failure as? TelegramException)?.failure) {
                                    TelegramFailure.ARCHIVE_UNSAFE -> R.string.cloud_unsafe_archive
                                    TelegramFailure.CONFIGURATION -> R.string.cloud_configuration_missing
                                    TelegramFailure.NATIVE_UNAVAILABLE -> R.string.cloud_engine_unavailable
                                    TelegramFailure.RATE_LIMIT -> R.string.cloud_rate_limit
                                    TelegramFailure.PHONE_INVALID -> R.string.cloud_phone_invalid
                                    TelegramFailure.CODE_INVALID -> R.string.cloud_code_invalid
                                    TelegramFailure.CODE_EXPIRED -> R.string.cloud_code_expired
                                    TelegramFailure.PASSWORD_INVALID -> R.string.cloud_password_invalid
                                    TelegramFailure.EMAIL_INVALID -> R.string.cloud_email_invalid
                                    TelegramFailure.API_REJECTED -> R.string.cloud_api_rejected
                                    TelegramFailure.CLIENT_OUTDATED -> R.string.cloud_client_outdated
                                    TelegramFailure.PHONE_REJECTED -> R.string.cloud_phone_rejected
                                    TelegramFailure.AUTH_UNAVAILABLE -> R.string.cloud_auth_unavailable
                                    else -> if (session.ready) {
                                        R.string.cloud_action_failed
                                    } else {
                                        R.string.cloud_login_failed
                                    }
                                }
                            },
                        )
                    } finally {
                        busy = false
                    }
                }
            }
        }
        fun loadPage() = perform {
            val page = repository.page(cursor)
            cursor = page.cursor
            more = !page.complete
        }
        fun finishSetup() {
            perform {
                repository.completeSetup(repository.ready())
                offerRestore = false
            }
        }
        fun restore(copy: CloudCopy) = perform {
            val file = repository.download(copy)
            navigator.push(
                CloudRestoreScreen(
                    uri = Uri.fromFile(file).toString(),
                    deviceName = copy.manifest.deviceName,
                    serverDate = copy.serverDate,
                    bytes = copy.manifest.bytes,
                    setupAccount = if (!configured) copy.accountId else 0,
                ),
            )
        }
        fun export(copy: CloudCopy) = perform {
            repository.export(copy)
            snackbar.showSnackbar(context.getString(R.string.cloud_exported))
        }
        val storagePermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val copy = exportRequested
                exportRequested = null
                if (granted && copy != null) {
                    export(copy)
                } else {
                    error = context.getString(R.string.cloud_storage_permission)
                }
            }

        DisposableEffect(repository) {
            val lease = repository.client.retain()
            onDispose { repository.client.release(lease) }
        }
        LaunchedEffect(repository) { perform { repository.client.connect() } }
        LaunchedEffect(initialSetup, configured) {
            if (initialSetup && configured) repository.preferences.introductionComplete().set(true)
        }
        LaunchedEffect(session.account?.id) {
            if (session.ready && session.account != null) {
                perform {
                    repository.ready()
                    if (!repository.configured) {
                        val found = repository.discover()
                        if (found.size == 1) repository.selectArchive(found.single().chatId)
                        offerRestore = repository.copies.value.isNotEmpty()
                    } else {
                        repository.refresh()
                    }
                    val page = if (repository.selectedArchiveState.value) {
                        if (repository.configured) repository.page() else repository.restoreSuggestions()
                    } else {
                        null
                    }
                    if (!repository.configured) offerRestore = repository.copies.value.isNotEmpty()
                    cursor = page?.cursor ?: 0
                    more = page != null && !page.complete
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(R.string.cloud_title),
                    navigateUp = { if (initialSetup) exitSetup() else (context as? Activity)?.finish() },
                    actions = {
                        if (session.ready && configured) {
                            IconButton(onClick = { settings = true }) {
                                Icon(Icons.Outlined.Settings, stringResource(R.string.cloud_settings))
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (initialSetup) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        TextButton(
                            onClick = exitSetup,
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        ) {
                            Text(stringResource(R.string.welcome_return))
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (session.ready) {
                        item(key = "cloud-tabs") { CloudSyncTabs(cloudTab) { cloudTab = it } }
                    }
                    if (session.ready && cloudTab != 2) {
                        item(key = "sync-content") { CloudSyncContent(sync, devices = cloudTab == 1) }
                    } else {
                        if (!session.ready) {
                            item(key = "welcome") { CloudWelcome() }
                        } else if (configured) {
                            item(key = "overview") {
                                CloudOverview(
                                    session = session,
                                    latest = copies.firstOrNull { it.manifest.deviceId == repository.deviceId },
                                    progress = progress,
                                    busy = busy,
                                    error = error,
                                    onSave = {
                                        repository.progress.value = nyanime.telegram.CloudProgress(CloudPhase.WAITING)
                                        CloudBackupWorker.saveNow(context)
                                    },
                                )
                            }
                            item(key = "schedule") { CloudSchedule(repository) { settings = true } }
                        }

                        if (!session.ready) {
                            item(key = "account") {
                                LoginCard(
                                    session,
                                    busy,
                                    error,
                                    onStart = {
                                        perform {
                                            if (session.failure != null) repository.client.close()
                                            repository.client.connect()
                                        }
                                    },
                                    onSubmit = { value ->
                                        perform { repository.client.submit(value) }
                                    },
                                    onQr = { perform { repository.client.requestQr() } },
                                    onRestart = {
                                        perform {
                                            repository.disconnect()
                                            repository.client.connect()
                                        }
                                    },
                                    onResend = {
                                        perform {
                                            repository.client.request(tdRequest("resendAuthenticationCode"))
                                        }
                                    },
                                )
                            }
                        }

                        if (session.ready && !configured) {
                            item(key = "setup") {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(24.dp),
                                ) {
                                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        CloudBadge(stringResource(R.string.cloud_private_archive), Icons.Outlined.Lock)
                                        Text(
                                            stringResource(R.string.cloud_archive_title),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(stringResource(R.string.cloud_archive_summary))
                                        if (archives.size > 1) {
                                            archives.forEach { archive ->
                                                OutlinedButton(
                                                    onClick = {
                                                        perform {
                                                            repository.selectArchive(archive.chatId)
                                                            val page = repository.restoreSuggestions()
                                                            cursor = page.cursor
                                                            more = !page.complete
                                                            offerRestore =
                                                                repository.copies.value.isNotEmpty()
                                                        }
                                                    },
                                                    enabled = !busy,
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) { Text(archive.title) }
                                            }
                                        }
                                        if (offerRestore && copies.isNotEmpty()) {
                                            val latest = copies.maxBy { it.serverDate }
                                            Text(
                                                stringResource(R.string.cloud_restore_found),
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                "${latest.manifest.deviceName} · ${formattedCloudDate(
                                                    latest.serverDate,
                                                )}",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            CloudActionButton(
                                                stringResource(R.string.cloud_preview_restore),
                                                !busy,
                                                busy = busy,
                                                onClick = { restore(latest) },
                                            )
                                            TextButton(onClick = {
                                                showSetupHistory = !showSetupHistory
                                            }, enabled = !busy) {
                                                Text(stringResource(R.string.cloud_choose_another))
                                            }
                                            TextButton(onClick = {
                                                finishSetup()
                                            }, enabled = !busy) {
                                                Text(stringResource(R.string.cloud_continue_without_restore))
                                            }
                                        } else {
                                            Button(
                                                onClick = {
                                                    perform {
                                                        if (!repository.selectedArchiveState.value) {
                                                            repository.createArchive()
                                                        }
                                                        repository.completeSetup(repository.ready())
                                                        offerRestore = false
                                                    }
                                                },
                                                enabled = !busy && (hasArchive || archives.isEmpty()),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(stringResource(R.string.cloud_enable_backups))
                                            }
                                        }
                                        CloudInlineError(error)
                                    }
                                }
                            }
                        }

                        if (session.ready && hasArchive && (configured || showSetupHistory)) {
                            if (configured && error != null) {
                                item(key = "archive-error") { CloudInlineError(error) }
                            }
                            item(key = "history-heading") {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.cloud_history),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    TextButton(onClick = {
                                        cursor = 0
                                        loadPage()
                                    }, enabled = !busy) { Text(stringResource(R.string.cloud_refresh)) }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = !onlyThisDevice, onClick = {
                                        onlyThisDevice = false
                                    }, label = { Text(stringResource(R.string.cloud_all_devices)) })
                                    FilterChip(selected = onlyThisDevice, onClick = {
                                        onlyThisDevice = true
                                    }, label = { Text(stringResource(R.string.cloud_this_device)) })
                                }
                            }
                            val visible = copies.filter { copy ->
                                !onlyThisDevice || copy.manifest.deviceId == repository.deviceId
                            }
                            if (visible.isEmpty()) {
                                item(key = "empty") {
                                    CloudEmptyHistory()
                                }
                            }
                            items(visible, key = { it.manifest.backupId }) { copy ->
                                CopyCard(
                                    copy,
                                    busy || progress.phase in CLOUD_TRANSFERRING,
                                    onRestore = {
                                        restore(copy)
                                    },
                                    onDelete = {
                                        deleteCopy =
                                            copy
                                    },
                                    onPin = { perform { repository.pin(copy) } },
                                    onExport = {
                                        if (BackupExportStore.requiresLegacyStoragePermission() &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                            ) !=
                                            PackageManager.PERMISSION_GRANTED
                                        ) {
                                            exportRequested = copy
                                            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        } else {
                                            export(copy)
                                        }
                                    },
                                )
                            }
                            if (more) {
                                item(key = "more") {
                                    OutlinedButton(onClick = {
                                        loadPage()
                                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.cloud_load_more))
                                    }
                                }
                            }
                        }
                        item(key = "privacy") { CloudPrivacy() }
                    }
                }
            }
        }

        unlockSpace?.let { space ->
            CloudSyncSetupSheet(sync, onDismiss = { unlockSpace = null }, unlockSpace = space)
        }
        deleteCopy?.let { copy ->
            AlertDialog(
                onDismissRequest = {
                    deleteCopy = null
                },
                title = { Text(stringResource(R.string.cloud_delete_title)) },
                text = { Text(stringResource(R.string.cloud_delete_summary)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteCopy = null
                        perform { repository.delete(copy) }
                    }) { Text(stringResource(R.string.cloud_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteCopy = null }) { Text(stringResource(R.string.cloud_cancel)) }
                },
            )
        }
        if (settings) {
            CloudSettings(repository, onDismiss = { settings = false }, onDisconnect = {
                settings = false
                disconnect =
                    true
            })
        }
        if (disconnect) {
            AlertDialog(
                onDismissRequest = {
                    disconnect = false
                },
                title = { Text(stringResource(R.string.cloud_disconnect)) },
                text = { Text(stringResource(R.string.cloud_disconnect_summary)) },
                confirmButton = {
                    TextButton(onClick = {
                        disconnect = false
                        perform {
                            sync.setEnabled(false)
                            repository.disconnect()
                        }
                    }) { Text(stringResource(R.string.cloud_disconnect)) }
                },
                dismissButton = {
                    TextButton(onClick = { disconnect = false }) { Text(stringResource(R.string.cloud_cancel)) }
                },
            )
        }
    }
}

@Composable
internal fun LoginCard(
    state: TelegramState,
    busy: Boolean,
    error: String?,
    onStart: () -> Unit,
    onSubmit: (String) -> Unit,
    onQr: () -> Unit,
    onRestart: () -> Unit,
    onResend: () -> Unit,
) {
    var input by remember(state.authorization) { mutableStateOf("") }
    var phoneRegion by rememberSaveable { mutableStateOf("IT") }
    var inputError by remember(state.authorization) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember(state.authorization) { mutableStateOf(false) }
    var canResend by remember(state.resendAt) { mutableStateOf(false) }
    LaunchedEffect(state.resendAt) {
        if (state.resendAt > 0) {
            delay((state.resendAt - System.currentTimeMillis()).coerceAtLeast(0))
            canResend = true
        }
    }
    val type = state.authorization
    val phone = type == "authorizationStateWaitPhoneNumber"
    val password = type == "authorizationStateWaitPassword"
    val inputState = type in setOf(
        "authorizationStateWaitPhoneNumber",
        "authorizationStateWaitCode",
        "authorizationStateWaitPassword",
        "authorizationStateWaitEmailAddress",
        "authorizationStateWaitEmailCode",
    )
    val label = when (type) {
        "authorizationStateWaitPhoneNumber" -> R.string.cloud_phone
        "authorizationStateWaitPassword" -> R.string.cloud_password
        "authorizationStateWaitEmailAddress" -> R.string.cloud_email
        else -> R.string.cloud_code
    }
    val summary = when (type) {
        "authorizationStateWaitCode" -> R.string.cloud_code_summary
        "authorizationStateWaitEmailCode" -> R.string.cloud_email_code_summary
        "authorizationStateWaitPassword" -> R.string.cloud_password_summary
        else -> R.string.cloud_login_summary
    }
    fun submit() {
        if (!busy && input.isNotBlank()) {
            val value = if (phone) {
                try {
                    TelegramPhoneNumbers.normalize(input, phoneRegion)
                } catch (_: TelegramException) {
                    inputError = context.getString(R.string.cloud_phone_invalid)
                    return
                }
            } else {
                input
            }
            inputError = null
            if (phone) focusManager.clearFocus()
            onSubmit(value)
            if (!phone) input = ""
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(
            Modifier.fillMaxWidth().animateContentSize(ModernMotion.resize(appMotionEnabled())).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(
                    when {
                        phone -> R.string.cloud_phone_heading
                        inputState -> label
                        else -> R.string.cloud_connect
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inputState) {
                if (phone) {
                    CloudPhoneInput(
                        value = input,
                        region = phoneRegion,
                        enabled = !busy,
                        isError = inputError != null || error != null,
                        onChange = {
                            if (it.length <= 256) {
                                inputError = null
                                val pasted = TelegramPhoneNumbers.splitInternational(it)
                                if (pasted != null) {
                                    phoneRegion = pasted.region
                                    input = pasted.nationalNumber
                                } else {
                                    input = it
                                }
                            }
                        },
                        onRegionChange = {
                            phoneRegion = it
                            inputError = null
                        },
                        onSubmit = { submit() },
                    )
                } else {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { if (it.length <= 256) input = it },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        enabled = !busy,
                        singleLine = true,
                        visualTransformation = if (password && !passwordVisible) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        trailingIcon = if (password) {
                            {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) {
                                            Icons.Outlined.VisibilityOff
                                        } else {
                                            Icons.Outlined.Visibility
                                        },
                                        stringResource(
                                            if (passwordVisible) {
                                                R.string.cloud_hide_password
                                            } else {
                                                R.string.cloud_show_password
                                            },
                                        ),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (type) {
                                "authorizationStateWaitPassword" -> KeyboardType.Password
                                "authorizationStateWaitEmailAddress" -> KeyboardType.Email
                                else -> KeyboardType.NumberPassword
                            },
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
                if (state.hint.isNotEmpty()) Text(state.hint, style = MaterialTheme.typography.bodySmall)
                CloudInlineError(inputError ?: error)
                CloudActionButton(
                    stringResource(if (busy) R.string.cloud_connecting else R.string.cloud_continue),
                    enabled = !busy && input.isNotBlank(),
                    busy = busy,
                    onClick = { submit() },
                )
                if (state.resendAt > 0) {
                    TextButton(onClick = onResend, enabled = !busy && canResend, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cloud_resend_code))
                    }
                }
                if (phone) {
                    OutlinedButton(
                        onClick = onQr,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Outlined.QrCode2, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cloud_qr_login))
                    }
                }
                if (!phone) {
                    TextButton(onClick = onRestart, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cloud_change_phone))
                    }
                }
            } else if (type == "authorizationStateWaitOtherDeviceConfirmation") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CloudLoginQr(state.qrLink) }
                Text(stringResource(R.string.cloud_qr_summary), style = MaterialTheme.typography.bodyMedium)
                CloudInlineError(error)
                TextButton(onClick = onRestart, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cloud_use_phone))
                }
            } else if (type in setOf("disconnected", "unavailable")) {
                CloudInlineError(
                    error ?: state.failure?.let {
                        stringResource(
                            if (it == TelegramFailure.CONFIGURATION) {
                                R.string.cloud_configuration_missing
                            } else {
                                R.string.cloud_engine_unavailable
                            },
                        )
                    },
                )
                CloudActionButton(
                    stringResource(if (busy) R.string.cloud_connecting else R.string.cloud_connect),
                    !busy,
                    busy,
                    onStart,
                )
            } else if (type in setOf("authorizationStateWaitRegistration", "authorizationStateWaitPremiumPurchase")) {
                Text(stringResource(R.string.cloud_finish_in_telegram))
                TextButton(onClick = onRestart, enabled = !busy) { Text(stringResource(R.string.cloud_restart_login)) }
            } else {
                Text(stringResource(R.string.cloud_connecting), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                CloudInlineError(error)
            }
        }
    }
}

@Composable
private fun CopyCard(
    copy: CloudCopy,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(
                        Icons.Outlined.Smartphone,
                        null,
                        Modifier.padding(12.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        copy.manifest.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formattedCloudDate(copy.serverDate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.cloud_copy_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = {
                            Text(stringResource(if (copy.pinned) R.string.cloud_unkeep else R.string.cloud_keep))
                        }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = {
                            menu = false
                            onPin()
                        })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cloud_export)) },
                            leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            onClick = {
                                menu = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cloud_delete)) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${Formatter.formatFileSize(context, copy.manifest.bytes)} · ${copy.manifest.appVersion}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (copy.pinned) CloudBadge(stringResource(R.string.cloud_protected), Icons.Outlined.PushPin)
                CloudBadge(
                    stringResource(
                        if (copy.manifest.protected) {
                            R.string.cloud_sync_backup_encrypted
                        } else {
                            R.string.cloud_sync_backup_legacy
                        },
                    ),
                    if (copy.manifest.protected) Icons.Outlined.Lock else null,
                )
            }
            OutlinedButton(
                onClick = onRestore,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.cloud_preview_restore))
            }
        }
    }
}

internal fun formattedCloudDate(seconds: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT,
    DateFormat.SHORT,
).format(Date(seconds * 1000))
internal val CLOUD_TRANSFERRING =
    setOf(CloudPhase.PREPARING, CloudPhase.UPLOADING, CloudPhase.CHECKING, CloudPhase.DOWNLOADING)
internal fun cloudPhaseLabel(phase: CloudPhase): Int = when (phase) {
    CloudPhase.WAITING -> R.string.cloud_waiting
    CloudPhase.SAVED -> R.string.cloud_saved
    CloudPhase.UNCHANGED -> R.string.cloud_unchanged
    CloudPhase.FAILED -> R.string.cloud_action_failed
    CloudPhase.UPLOADING -> R.string.cloud_uploading
    CloudPhase.DOWNLOADING -> R.string.cloud_downloading
    CloudPhase.CHECKING -> R.string.cloud_verifying
    else -> R.string.cloud_preparing
}
