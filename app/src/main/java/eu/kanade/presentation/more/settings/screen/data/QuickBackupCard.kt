package eu.kanade.presentation.more.settings.screen.data

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.WorkInfo
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupExportStore
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.util.storage.getUriCompat
import java.io.File

@Composable
fun QuickBackupCard(
    onViewBackups: () -> Unit,
    onRestoreBackup: (Uri) -> Unit,
    onCustomize: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val works by remember(context) { BackupCreateJob.observeManual(context) }.collectAsState(initial = emptyList())
    val work = works.lastOrNull()
    val active = work?.state == WorkInfo.State.ENQUEUED ||
        work?.state == WorkInfo.State.BLOCKED ||
        work?.state == WorkInfo.State.RUNNING
    val completedUri = work?.takeIf { it.state == WorkInfo.State.SUCCEEDED }
        ?.outputData?.getString(BackupCreateJob.RESULT_URI)?.toUri()
    var starting by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    LaunchedEffect(work?.id) {
        if (work != null) starting = false
    }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionDenied = false
            starting = true
            BackupCreateJob.startNow(context)
        } else {
            starting = false
            permissionDenied = true
        }
    }
    val startBackup = {
        if (!active && !starting) {
            permissionDenied = false
            if (BackupExportStore.requiresLegacyStoragePermission() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionRequest.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                starting = true
                BackupCreateJob.startNow(context)
            }
        }
    }

    val status = when {
        permissionDenied -> stringResource(R.string.quick_backup_permission)
        starting || work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED ->
            stringResource(R.string.quick_backup_waiting)
        work?.state == WorkInfo.State.RUNNING -> when (work.progress.getString(BackupCreateJob.PROGRESS_PHASE)) {
            "saving" -> stringResource(R.string.quick_backup_saving)
            "verifying" -> stringResource(R.string.quick_backup_verifying)
            else -> stringResource(R.string.quick_backup_preparing)
        }
        completedUri != null -> stringResource(R.string.quick_backup_done)
        work?.state == WorkInfo.State.FAILED || work?.state == WorkInfo.State.CANCELLED ->
            work.outputData.getString(BackupCreateJob.RESULT_ERROR)?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.quick_backup_failed)
        else -> stringResource(R.string.quick_backup_subtitle)
    }

    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !active && !starting, onClick = startBackup)
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.quick_backup_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (active || starting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            Text(
                stringResource(R.string.quick_backup_warning),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
                if (completedUri != null) {
                    TextButton(
                        onClick = { shareBackup(context, completedUri) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.quick_backup_share))
                    }
                    TextButton(
                        onClick = { onRestoreBackup(completedUri) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.quick_backup_restore_action))
                    }
                }
                TextButton(onClick = onViewBackups, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.quick_backup_view))
                }
            }
            if (onCustomize != null) {
                TextButton(onClick = onCustomize, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                    Text(stringResource(R.string.quick_backup_customize))
                }
            }
        }
    }
}

private fun shareBackup(context: android.content.Context, uri: Uri) {
    val shareUri = if (uri.scheme == "file") {
        File(requireNotNull(uri.path)).getUriCompat(context)
    } else {
        uri
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.quick_backup_share)))
    } catch (_: ActivityNotFoundException) {
        // The exported file remains available in Download/Nyanime.
    }
}
