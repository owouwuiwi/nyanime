package eu.kanade.presentation.more.settings.screen.browse.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.CatalogueUpgradeReset
import eu.kanade.tachiyomi.data.extensionstore.ExtensionResetInventory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

@Composable
internal fun CatalogueResetGate(reset: CatalogueUpgradeReset, onComplete: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val inventory = remember(context) { ExtensionResetInventory(context.applicationContext) }
    var extensions by remember { mutableStateOf(emptyList<ExtensionResetInventory.Extension>()) }
    var attempt by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var removeAll by rememberSaveable { mutableStateOf(false) }
    var waitingFor by rememberSaveable { mutableStateOf<String?>(null) }
    var returnedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }

    val uninstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        returnedPackage = waitingFor
        waitingFor = null
        attempt++
    }

    LaunchedEffect(attempt) {
        working = true
        failed = false
        cancelled = false
        try {
            var current = withContext(Dispatchers.IO) {
                reset.resetCatalogues(inventory::clearCatalogues)
                inventory.read()
            }
            val returned = returnedPackage
            returnedPackage = null
            if (returned != null && current.any { it.androidPackage && it.packageName == returned }) {
                removeAll = false
                cancelled = true
            }
            if (removeAll && waitingFor == null) {
                val next = current.firstOrNull { it.androidPackage }
                if (next == null) {
                    current = withContext(Dispatchers.IO) {
                        inventory.removePrivate()
                        inventory.read()
                    }
                    removeAll = false
                } else {
                    waitingFor = next.packageName
                    uninstall.launch(
                        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", next.packageName, null))
                            .putExtra(Intent.EXTRA_RETURN_RESULT, true),
                    )
                }
            }
            extensions = current
            if (finishing) {
                val complete = withContext(Dispatchers.IO) {
                    reset.finish {
                        inventory.read().also { current = it }.isNotEmpty()
                    }
                }
                if (complete) onComplete() else extensions = current
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            inventory.logcat(LogPriority.ERROR, error) { "Unable to complete the extension reset" }
            waitingFor = null
            removeAll = false
            failed = true
        } finally {
            finishing = false
            working = false
        }
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.catalogue_reset_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.catalogue_reset_description))
                Text(stringResource(R.string.catalogue_reset_preserved))
                if (working || waitingFor != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (failed) {
                    Text(stringResource(R.string.catalogue_reset_failed), color = MaterialTheme.colorScheme.error)
                } else if (extensions.isNotEmpty()) {
                    Text(stringResource(R.string.catalogue_reset_remaining, extensions.size))
                    LazyColumn(Modifier.heightIn(max = 168.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(extensions) { extension ->
                            Text(extension.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (cancelled) Text(stringResource(R.string.catalogue_reset_cancelled))
                } else {
                    Text(stringResource(R.string.catalogue_reset_ready))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onExit, enabled = !working && waitingFor == null) {
                Text(stringResource(R.string.catalogue_reset_exit))
            }
        },
        confirmButton = {
            val label = when {
                failed -> R.string.catalogue_reset_retry
                extensions.isEmpty() -> R.string.catalogue_reset_continue
                else -> R.string.catalogue_reset_remove_all
            }
            TextButton(
                onClick = {
                    if (!failed) {
                        finishing = extensions.isEmpty()
                        removeAll = extensions.isNotEmpty()
                    }
                    attempt++
                },
                enabled = !working && waitingFor == null,
            ) { Text(stringResource(label)) }
        },
    )
}
