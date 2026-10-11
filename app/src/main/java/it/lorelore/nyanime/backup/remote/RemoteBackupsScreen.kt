package it.lorelore.nyanime.backup.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.cloud.CloudRestoreScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class RemoteBackupsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val motion = appMotionEnabled()
        val compactTyping = LocalConfiguration.current.screenHeightDp < 400 &&
            WindowInsets.ime.getBottom(LocalDensity.current) > 0
        var provider by rememberSaveable { mutableStateOf(RemoteBackupProvider.WebDAV) }
        Scaffold(topBar = {
            if (!compactTyping) {
                AppBar(title = stringResource(R.string.remote_backup_title), navigateUp = navigator::pop)
            }
        }) { padding ->
            AnimatedContent(
                provider,
                transitionSpec = { ModernMotion.transform(motion) },
                label = "remote-provider",
                modifier =
                Modifier.fillMaxSize().padding(padding).imePadding()
                    .padding(horizontal = 20.dp, vertical = if (compactTyping) 4.dp else 16.dp),
            ) { selected ->
                ProviderPanel(selected, onProvider = { provider = it })
            }
        }
    }
}

@Composable
private fun Screen.ProviderPanel(provider: RemoteBackupProvider, onProvider: (RemoteBackupProvider) -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val repository = remember { RemoteBackupRepository(context.applicationContext) }
    val workManager = remember { WorkManager.getInstance(context) }
    var config by remember { mutableStateOf<RemoteBackupConfiguration?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var backups by remember { mutableStateOf<List<RemoteBackupEntry>>(emptyList()) }
    var checked by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(false) }
    val manualAll by remember(provider) { workManager.getWorkInfosForUniqueWorkFlow(RemoteBackupWorker.name(provider)) }
        .collectAsState(initial = emptyList())
    val periodicAll by remember(provider) {
        workManager.getWorkInfosForUniqueWorkFlow(RemoteBackupWorker.name(provider) + "-periodic")
    }
        .collectAsState(initial = emptyList())
    val manual = manualAll.filter { "remote-generation-${config?.generation}" in it.tags }
    val periodic = periodicAll.filter { "remote-generation-${config?.generation}" in it.tags }
    val running = (manual + periodic).firstOrNull { it.state == WorkInfo.State.RUNNING }
    val queued = manual.firstOrNull { !it.state.isFinished }
    val working = running != null || queued != null
    var completed by remember { mutableStateOf(0L) }
    val failure = latestRemoteBackupError(
        completed,
        (manual + periodic).mapNotNull { work ->
            work.outputData.getString("error")?.let { RemoteBackupFailure(work.outputData.getLong("error_at", 0), it) }
        },
    )

    suspend fun loadBackups(value: RemoteBackupConfiguration) {
        busy = true
        error = null
        try {
            backups = repository.backups(value)
            checked = true
        } catch (
            cancel: CancellationException,
        ) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.remoteReason()
        } finally {
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        try {
            config = withContext(Dispatchers.IO) { repository.vault.get(provider) }
            setup = repository.vault.setupAcknowledged()
            completed = config?.let { value -> withContext(Dispatchers.IO) { repository.lastSuccess(value) } } ?: 0
        } catch (_: Exception) {
            error = "configuration"
        } finally {
            loaded = true
        }
    }
    LaunchedEffect(manual, periodic, config?.generation) {
        completed = config?.let { value -> withContext(Dispatchers.IO) { repository.lastSuccess(value) } } ?: 0
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("introduction") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.remote_backup_intro), style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RemoteBackupProvider.entries.forEach { item ->
                        FilterChip(provider == item, { onProvider(item) }, label = { Text(item.label()) })
                    }
                }
            }
        }
        if (!loaded) {
            item("loading") { CircularProgressIndicator() }
            return@LazyColumn
        }
        item("configuration") {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (provider == RemoteBackupProvider.Drive && setup) {
                    TextButton({ setup = false }, enabled = !busy && !working) {
                        Text(stringResource(R.string.remote_drive_review_setup))
                    }
                }

                if (provider == RemoteBackupProvider.Drive && !setup) {
                    DriveSetupGuide(onReady = {
                        scope.launch {
                            withContext(Dispatchers.IO) { repository.vault.acknowledgeSetup() }
                            setup = true
                        }
                    })
                } else if (config == null) {
                    RemoteConfigurationForm(provider, onSave = { value ->
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                repository.check(value)
                                withContext(Dispatchers.IO) { repository.vault.set(value) }
                                config = value
                                loadBackups(value)
                            } catch (
                                cancel: CancellationException,
                            ) {
                                throw cancel
                            } catch (
                                failure: Exception,
                            ) {
                                error = failure.remoteReason()
                            } finally {
                                busy = false
                            }
                        }
                    }, busy = busy, onError = { error = it })
                } else {
                    val current = config!!
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.CloudDone, null)
                                Text(provider.label(), style = MaterialTheme.typography.titleLarge)
                            }
                            Text(
                                if (provider == RemoteBackupProvider.Drive) current.account else current.endpoint,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (completed >
                                0
                            ) {
                                Text(
                                    stringResource(
                                        R.string.remote_last_success,
                                        DateUtils.getRelativeTimeSpanString(completed).toString(),
                                    ),
                                )
                            } else {
                                Text(stringResource(R.string.remote_no_backup_yet))
                            }
                            TextButton({ context.copyRemoteRecoveryKey(current.recoveryKey) }, enabled = !busy) {
                                Text(stringResource(R.string.remote_copy_key))
                            }
                            Button(
                                { RemoteBackupWorker.start(context, current) },
                                Modifier.fillMaxWidth(),
                                enabled =
                                !working && !busy,
                            ) {
                                Text(stringResource(R.string.remote_backup_now))
                            }
                            if (working) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(stringResource(phaseText(running?.progress?.getString("phase") ?: "queued")))
                                TextButton({
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                RemoteBackupWorker.cancelAttempt(context, provider, current.generation)
                                            }
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            error = "configuration"
                                        }
                                    }
                                }) {
                                    Text(stringResource(R.string.remote_cancel))
                                }
                            }
                        }
                    }
                    RemoteSchedule(current, enabled = !working && !busy, onChange = { updated ->
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { repository.vault.set(updated) }
                                config = updated
                                RemoteBackupWorker.schedule(context, updated)
                            } catch (
                                cancel: CancellationException,
                            ) {
                                throw cancel
                            } catch (_: Exception) {
                                error = "configuration"
                            } finally {
                                busy = false
                            }
                        }
                    })
                    TextButton({ scope.launch { loadBackups(current) } }, enabled = !busy && !working) {
                        Text(stringResource(R.string.remote_show_backups))
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (checked && backups.isEmpty()) Text(stringResource(R.string.remote_empty))
                }
                if (error != null ||
                    failure != null
                ) {
                    Text(stringResource(errorText(error ?: failure!!)), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (config != null && (provider != RemoteBackupProvider.Drive || setup)) {
            items(backups, key = { it.objectId }) { entry ->
                val current = config!!
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            DateUtils.formatDateTime(
                                context,
                                entry.commit.created,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${entry.commit.label} · ${Formatter.formatFileSize(context, entry.commit.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton({
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    val file = repository.prepareRestore(current, entry)
                                    navigator.push(
                                        CloudRestoreScreen(
                                            Uri.fromFile(file).toString(),
                                            entry.commit.label,
                                            entry.commit.created,
                                            file.length(),
                                        ),
                                    )
                                } catch (
                                    cancel: CancellationException,
                                ) {
                                    throw cancel
                                } catch (
                                    failure: Exception,
                                ) {
                                    error = failure.remoteReason()
                                } finally {
                                    busy = false
                                }
                            }
                        }, enabled = !busy && !working) { Text(stringResource(R.string.remote_restore_preview)) }
                    }
                }
            }
        }
        item("footer") {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (config != null && (provider != RemoteBackupProvider.Drive || setup)) {
                    val current = config!!
                    TextButton({
                        scope.launch {
                            busy = true
                            try {
                                RemoteBackupWorker.cancel(context, provider)
                                if (provider == RemoteBackupProvider.Drive) {
                                    try {
                                        DriveBackupAuthorization(context).revoke(current.account)
                                    } catch (
                                        cancel: CancellationException,
                                    ) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        error = "authorization"
                                    }
                                }
                                withContext(Dispatchers.IO) { repository.vault.remove(provider) }
                                config = null
                                backups = emptyList()
                                checked = false
                            } catch (failure: Exception) {
                                error = failure.remoteReason()
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy && !working) { Text(stringResource(R.string.remote_disconnect)) }
                }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.remote_privacy), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Screen.RemoteConfigurationForm(
    provider: RemoteBackupProvider,
    onSave: (RemoteBackupConfiguration) -> Unit,
    busy: Boolean,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = rememberScreenModel(tag = "remote-setup-${provider.name}") { RemoteSetupModel() }
    var endpoint by model.endpoint
    var username by model.username
    var password by model.password
    var recovery by model.recovery
    var acknowledged by model.acknowledged
    var authorizing by model.authorizing
    val auth = remember { DriveBackupAuthorization(context) }

    fun authorized(result: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        // Process death must not replace the acknowledged key with a newly generated one.
        if (!model.authStarted) {
            authorizing = false
            onError("configuration")
            return
        }
        if (result.accessToken.isNullOrBlank() ||
            !result.grantedScopes.contains(DriveBackupAuthorization.SCOPE)
        ) {
            authorizing = false
            onError("authorization")
            return
        }
        authorizing = true
        scope.launch {
            try {
                val account = auth.account(result)
                onSave(RemoteBackupConfiguration(provider, recoveryKey = recovery.trim(), account = account))
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                onError("authorization")
            } finally {
                authorizing = false
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { response ->
        authorizing = false
        if (response.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        try {
            authorized(auth.fromIntent(response.data))
        } catch (_: Exception) {
            onError("authorization")
        }
    }
    val enabled = !busy && !authorizing
    if (provider == RemoteBackupProvider.WebDAV) {
        OutlinedTextField(
            endpoint,
            { endpoint = it.take(2048) },
            Modifier.fillMaxWidth(),
            enabled = enabled,
            label = {
                Text(stringResource(R.string.remote_endpoint))
            },
            placeholder = { Text("https://…") },
            singleLine = true,
        )
        OutlinedTextField(
            username,
            { username = it.take(512) },
            Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(stringResource(R.string.remote_username)) },
            singleLine = true,
        )
        OutlinedTextField(
            password,
            { password = it.take(4096) },
            Modifier.fillMaxWidth(),
            enabled = enabled,
            label = {
                Text(stringResource(R.string.remote_password))
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
    } else {
        Text(stringResource(R.string.remote_drive_scope), style = MaterialTheme.typography.bodyMedium)
    }
    Text(stringResource(R.string.remote_recovery_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.remote_recovery_help), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        recovery,
        {
            recovery = it.take(100)
            acknowledged = false
        },
        Modifier.fillMaxWidth(),
        enabled = enabled,
        label = {
            Text(stringResource(R.string.remote_recovery_key))
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    TextButton(
        {
            context.copyRemoteRecoveryKey(recovery)
        },
        enabled = enabled,
    ) { Text(stringResource(R.string.remote_copy_key)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(acknowledged, { acknowledged = it }, enabled = enabled)
        Text(stringResource(R.string.remote_key_saved), style = MaterialTheme.typography.bodyMedium)
    }
    Button(
        {
            try {
                RemoteRecoveryKey.decode(recovery).fill(0)
            } catch (_: Exception) {
                onError("key")
                return@Button
            }
            if (provider == RemoteBackupProvider.WebDAV) {
                try {
                    val configuration = RemoteBackupConfiguration(
                        provider,
                        endpoint.trim(),
                        username,
                        password,
                        recovery.trim(),
                    ).also { it.validate() }
                    onSave(configuration)
                } catch (
                    _: Exception,
                ) {
                    onError("endpoint")
                }
            } else {
                authorizing = true
                model.authStarted = true
                scope.launch {
                    try {
                        val result = auth.request()
                        if (result.hasResolution()) {
                            launcher.launch(
                                IntentSenderRequest.Builder(requireNotNull(result.pendingIntent).intentSender).build(),
                            )
                        } else {
                            authorized(result)
                        }
                    } catch (
                        cancel: CancellationException,
                    ) {
                        throw cancel
                    } catch (_: Exception) {
                        authorizing = false
                        onError("authorization")
                    }
                }
            }
        },
        Modifier.fillMaxWidth(),
        enabled =
        enabled && acknowledged && (provider == RemoteBackupProvider.Drive || endpoint.isNotBlank()),
    ) {
        Text(
            stringResource(
                if (provider ==
                    RemoteBackupProvider.Drive
                ) {
                    R.string.remote_connect_google
                } else {
                    R.string.remote_connect
                },
            ),
        )
    }
    if (busy || authorizing) LinearProgressIndicator(Modifier.fillMaxWidth())
}

/** Form secrets survive rotation in memory, never in Activity saved state or a portable backup. */
private class RemoteSetupModel : ScreenModel {
    val endpoint = mutableStateOf("")
    val username = mutableStateOf("")
    val password = mutableStateOf("")
    val recovery = mutableStateOf(RemoteRecoveryKey.create())
    val acknowledged = mutableStateOf(false)
    val authorizing = mutableStateOf(false)
    var authStarted = false
    override fun onDispose() {
        password.value = ""
        recovery.value = ""
    }
}

@Composable
private fun RemoteSchedule(
    config: RemoteBackupConfiguration,
    enabled: Boolean,
    onChange: (RemoteBackupConfiguration) -> Unit,
) {
    Text(stringResource(R.string.remote_schedule), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 6, 12, 24, 48, 168).forEach { hours ->
            FilterChip(
                config.intervalHours == hours,
                {
                    onChange(config.copy(intervalHours = hours))
                },
                enabled = enabled,
                label = {
                    Text(
                        if (hours ==
                            0
                        ) {
                            stringResource(R.string.remote_manual_only)
                        } else {
                            stringResource(R.string.remote_every_hours, hours)
                        },
                    )
                },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.remote_unmetered), Modifier.weight(1f))
        Switch(config.wifiOnly, { onChange(config.copy(wifiOnly = it)) }, enabled = enabled)
    }
    Text(stringResource(R.string.remote_retention), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(3, 7, 14, 30).forEach { count ->
            FilterChip(
                config.retention == count,
                { onChange(config.copy(retention = count)) },
                enabled = enabled,
                label = { Text(count.toString()) },
            )
        }
    }
    Text(
        stringResource(R.string.remote_schedule_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DriveSetupGuide(onReady: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var acknowledged by remember { mutableStateOf(false) }
    val certificate = remember {
        @Suppress("DEPRECATION")
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ).signingInfo?.apkContentsSigners
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString(":") { "%02X".format(it) }
        }
            .joinToString("\n")
    }
    Text(stringResource(R.string.remote_drive_setup_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.remote_drive_setup_intro))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.remote_drive_steps))
            Text("${context.packageName}\nSHA-1: $certificate", style = MaterialTheme.typography.bodySmall)
            TextButton({
                context.getSystemService(
                    ClipboardManager::class.java,
                ).setPrimaryClip(ClipData.newPlainText("Nyanime Android OAuth", "${context.packageName}\n$certificate"))
            }) { Text(stringResource(R.string.remote_copy_configuration)) }
            OutlinedButton({
                uri.openUri("https://console.cloud.google.com/apis/dashboard")
            }) { Text(stringResource(R.string.remote_open_google_cloud)) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(acknowledged, { acknowledged = it })
        Text(stringResource(R.string.remote_drive_configured))
    }
    Button(onReady, Modifier.fillMaxWidth(), enabled = acknowledged) { Text(stringResource(R.string.remote_continue)) }
}

private fun android.content.Context.copyRemoteRecoveryKey(value: String) {
    val clip = ClipData.newPlainText("Nyanime recovery key", value)
    clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private fun RemoteBackupProvider.label() = if (this == RemoteBackupProvider.Drive) "Google Drive" else "WebDAV"
private fun Exception.remoteReason() = (this as? RemoteStorageException)?.reason ?: "invalid"
private fun phaseText(phase: String) = when (phase) {
    "preparing" -> R.string.remote_phase_preparing
    "encrypting" -> R.string.remote_phase_encrypting
    "uploading" -> R.string.remote_phase_uploading
    "verifying", "retention" -> R.string.remote_phase_verifying
    else -> R.string.remote_phase_queued
}
private fun errorText(reason: String) = when (reason) {
    "endpoint" -> R.string.remote_error_endpoint
    "backup" -> R.string.remote_error_backup
    "authorization" -> R.string.remote_error_authorization
    "configuration" -> R.string.remote_error_configuration
    "space" -> R.string.remote_error_space
    "key" -> R.string.remote_error_key
    "network", "server", "rate", "missing" -> R.string.remote_error_network
    else -> R.string.remote_error_invalid
}
