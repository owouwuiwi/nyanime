package eu.kanade.presentation.more.settings.screen.data

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.work.WorkInfo
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupExportStore
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.ui.cloud.CloudRestoreScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold
import java.text.DateFormat
import java.util.Date

class BackupBrowserScreen(private val onboarding: Boolean = false) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        fun openBackup(uri: String) {
            if (onboarding) {
                navigator.push(
                    CloudRestoreScreen(
                        uri,
                        context.getString(R.string.welcome_restore_file),
                        0,
                        0,
                        localBackup = true,
                    ),
                )
            } else {
                navigator.push(RestoreBackupScreen(uri))
            }
        }
        var backups by remember { mutableStateOf<List<BackupExportStore.Entry>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var loadError by remember { mutableStateOf<String?>(null) }
        val manualWorks by remember(context) { BackupCreateJob.observeManual(context) }
            .collectAsState(initial = emptyList())
        val completedWorkId = manualWorks.lastOrNull()
            ?.takeIf { it.state == WorkInfo.State.SUCCEEDED }?.id
        var canReadLegacy by remember {
            mutableStateOf(
                !BackupExportStore.requiresLegacyStoragePermission() ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
        val legacyPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            canReadLegacy = granted
            if (!granted) {
                loadError = context.getString(R.string.quick_backup_permission)
                loading = false
            }
        }
        LaunchedEffect(context, canReadLegacy, completedWorkId) {
            if (!canReadLegacy) {
                legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                val result = withContext(Dispatchers.IO) { runCatching { BackupExportStore.list(context) } }
                backups = result.getOrDefault(emptyList())
                loadError = result.exceptionOrNull()?.localizedMessage
                loading = false
            }
        }

        val chooseOther = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    // Some providers only support a temporary grant; restoration still starts immediately.
                }
                openBackup(uri.toString())
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(R.string.quick_backup_restore),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.quick_backup_browser_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(R.string.quick_backup_browser_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (loading) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(R.string.quick_backup_searching))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else if (loadError != null || backups.isEmpty()) {
                    item {
                        Text(
                            loadError ?: stringResource(R.string.quick_backup_empty),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (backups.isNotEmpty()) {
                    item(key = "latest") {
                        BackupEntryCard(entry = backups.first(), featured = true) {
                            openBackup(backups.first().uri.toString())
                        }
                    }
                }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.quick_backup_external_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        OutlinedButton(onClick = { chooseOther.launch(arrayOf("*/*")) }) {
                            Text(stringResource(R.string.quick_backup_other_file))
                        }
                    }
                }
                if (backups.size > 1) {
                    item {
                        Text(
                            stringResource(R.string.quick_backup_previous_title),
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(backups.drop(1), key = { it.uri.toString() }) { entry ->
                        BackupEntryCard(entry = entry, featured = false) {
                            openBackup(entry.uri.toString())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupEntryCard(
    entry: BackupExportStore.Entry,
    featured: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.modifiedAt))
    val size = android.text.format.Formatter.formatShortFileSize(context, entry.size)
    val location = stringResource(
        if (entry.automatic) R.string.quick_backup_automatic else R.string.quick_backup_manual,
    )
    val dateStyle = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (featured) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (featured) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (featured) {
                Text(
                    stringResource(R.string.quick_backup_latest_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        date,
                        style = dateStyle,
                    )
                    Text(
                        "$location · $size",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
            if (featured) {
                Text(
                    stringResource(R.string.quick_backup_inspect),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 38.dp),
                )
            }
        }
    }
}
