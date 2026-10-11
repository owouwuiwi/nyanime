package eu.kanade.tachiyomi.ui.cloud

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncArchive
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SetupPage { CHECK, EXISTING, CODE, QR, RECOVERY, MERGE }

@Composable
internal fun CloudSyncSetupSheet(manager: CloudSyncManager, onDismiss: () -> Unit, unlockSpace: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(SetupPage.CHECK) }
    var archives by remember { mutableStateOf<List<CloudSyncArchive>>(emptyList()) }
    var selected by remember { mutableStateOf<CloudSyncArchive?>(null) }
    var code by remember { mutableStateOf("") } // Never save secrets in savedInstanceState.
    var qr by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("create") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            failed = false
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    fun inspect() = perform {
        preview = manager.setup.localPreview()
        val pending = if (unlockSpace == null) manager.setup.pendingCreationCode() else null
        if (manager.setup.hasPendingApproval(unlockSpace)) {
            mode = "pairing"
            page = SetupPage.MERGE
        } else if (pending != null) {
            code = pending
            page = SetupPage.CODE
        } else {
            archives = manager.setup.discover().filter { unlockSpace == null || it.descriptor.space == unlockSpace }
            check(unlockSpace == null || archives.isNotEmpty())
            if (archives.isEmpty()) {
                code = manager.setup.prepareCreation()
                page = SetupPage.CODE
            } else {
                selected = archives.first()
                page = SetupPage.EXISTING
            }
        }
    }
    LaunchedEffect(Unit) { inspect() }
    LaunchedEffect(page, qr) {
        if (page != SetupPage.QR || qr.isBlank()) return@LaunchedEffect
        while (page == SetupPage.QR) {
            delay(2000)
            if (busy) continue
            try {
                if (manager.setup.receiveApproval(requireNotNull(selected))) {
                    mode = "pairing"
                    page = SetupPage.MERGE
                    failed = false
                    break
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
                break
            }
        }
    }
    // QR/recovery material is visible only in this explicit setup surface.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val secure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!secure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    SyncSheetFrame(R.string.cloud_sync_setup_title, onDismiss, busy) {
        when (page) {
            SetupPage.CHECK -> {
                Text(stringResource(R.string.cloud_sync_discovering))
                if (failed) TextButton(onClick = ::inspect) { Text(stringResource(R.string.cloud_sync_continue)) }
            }
            SetupPage.EXISTING -> {
                Text(
                    stringResource(R.string.cloud_sync_existing),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.cloud_sync_existing_summary))
                if (archives.size > 1) {
                    archives.forEachIndexed { index, archive ->
                        FilterChip(
                            selected = selected == archive,
                            onClick = { selected = archive },
                            label = { Text("Nyanime Sync ${index + 1}") },
                        )
                    }
                }
                CloudActionButton(stringResource(R.string.cloud_sync_show_qr), !busy, onClick = {
                    perform {
                        qr = manager.setup.pairingRequest()
                        page = SetupPage.QR
                    }
                })
                TextButton(onClick = { page = SetupPage.RECOVERY }, enabled = !busy) {
                    Text(stringResource(R.string.cloud_sync_recover))
                }
            }
            SetupPage.CODE -> {
                CloudBadge(stringResource(R.string.cloud_sync_private), Icons.Outlined.Lock)
                Text(
                    stringResource(R.string.cloud_sync_recovery_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.cloud_sync_recovery_summary))
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    SelectionContainer {
                        Text(
                            code,
                            Modifier.padding(18.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                TextButton(onClick = {
                    val clip = ClipData.newPlainText("Nyanime", code)
                    clip.description.extras =
                        PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cloud_sync_copy_code))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(saved, { saved = it })
                    Text(stringResource(R.string.cloud_sync_recovery_saved), Modifier.weight(1f))
                }
                CloudActionButton(
                    stringResource(R.string.cloud_sync_continue),
                    saved && !busy,
                    onClick = {
                        mode = "create"
                        page = SetupPage.MERGE
                    },
                )
            }
            SetupPage.QR -> {
                Text(stringResource(R.string.cloud_sync_qr_summary))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CloudLoginQr(qr, stringResource(R.string.cloud_sync_show_qr))
                }
                Text(
                    stringResource(R.string.cloud_sync_qr_expiry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { perform { qr = manager.setup.pairingRequest(renew = true) } }, enabled = !busy) {
                    Text(stringResource(R.string.cloud_sync_qr_renew))
                }
            }
            SetupPage.RECOVERY -> {
                Text(stringResource(R.string.cloud_sync_existing_summary))
                OutlinedTextField(
                    recovery,
                    { recovery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.cloud_sync_recovery_input)) },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy,
                )
                CloudActionButton(
                    stringResource(R.string.cloud_sync_continue),
                    recovery.isNotBlank() && !busy,
                    onClick = {
                        mode = "recovery"
                        page = SetupPage.MERGE
                    },
                )
            }
            SetupPage.MERGE -> {
                Text(
                    stringResource(
                        if (unlockSpace == null) R.string.cloud_sync_merge_title else R.string.cloud_sync_unlock,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                preview?.takeIf { unlockSpace == null }?.let {
                    Text(
                        stringResource(R.string.cloud_sync_merge_local, it.first, it.second),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    stringResource(
                        if (unlockSpace == null) {
                            R.string.cloud_sync_merge_summary
                        } else {
                            R.string.cloud_sync_unlock_summary
                        },
                    ),
                )
                Text(
                    stringResource(R.string.cloud_sync_merge_independent),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CloudActionButton(
                    stringResource(
                        if (unlockSpace == null) R.string.cloud_sync_merge_confirm else R.string.cloud_sync_unlock,
                    ),
                    !busy,
                    busy,
                    onClick = {
                        perform {
                            when (mode) {
                                "pairing" -> manager.setup.enableApproved(activate = unlockSpace == null)
                                "recovery" -> manager.setup.recoverAndEnable(
                                    requireNotNull(selected),
                                    recovery.trim(),
                                    activate = unlockSpace == null,
                                )
                                else -> manager.setup.createAndEnable()
                            }
                            onDismiss()
                        }
                    },
                )
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        CloudInlineError(if (failed) stringResource(R.string.cloud_sync_setup_failed) else null)
    }
}

@Composable
internal fun CloudSyncApprovalSheet(manager: CloudSyncManager, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var candidate by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var approved by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            scope.launch {
                busy = true
                failed = false
                try {
                    manager.setup.inspectRequest(text)
                    candidate = text
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        }
    }
    SyncSheetFrame(R.string.cloud_sync_approve, onDismiss, busy) {
        if (approved) {
            Text(stringResource(R.string.cloud_sync_approved), style = MaterialTheme.typography.headlineSmall)
            CloudActionButton(stringResource(R.string.cloud_sync_continue), true, onClick = onDismiss)
        } else if (candidate != null) {
            Text(stringResource(R.string.cloud_sync_approve_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.cloud_sync_approve_summary))
            CloudActionButton(stringResource(R.string.cloud_sync_approve_confirm), !busy, busy, onClick = {
                scope.launch {
                    busy = true
                    failed = false
                    try {
                        manager.setup.approve(requireNotNull(candidate))
                        candidate = null
                        approved = true
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            })
        } else {
            Text(stringResource(R.string.cloud_sync_scan_summary))
            val prompt = stringResource(R.string.cloud_sync_scan)
            CloudActionButton(prompt, !busy, onClick = {
                launcher.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setBeepEnabled(false).setPrompt(prompt).setOrientationLocked(false),
                )
            })
        }
        CloudInlineError(if (failed) stringResource(R.string.cloud_sync_setup_failed) else null)
    }
}

@Composable
private fun SyncSheetFrame(
    title: Int,
    onDismiss: () -> Unit,
    busy: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    CloudAdaptiveSheet(onDismissRequest = onDismiss, enableSwipeDismiss = !busy) {
        Column(Modifier.fillMaxWidth().imePadding().heightIn(max = maxHeight)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.cloud_cancel)) }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }
}
