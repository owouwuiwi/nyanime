package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncDevice
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncManager
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.util.collectAsState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CloudSyncTabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(R.string.cloud_sync_tab, R.string.cloud_sync_devices_tab, R.string.cloud_sync_backups_tab)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    color = if (selected ==
                        index
                    ) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CloudSyncContent(manager: CloudSyncManager, devices: Boolean) {
    val context = LocalContext.current
    val navigator = cafe.adriel.voyager.navigator.LocalNavigator.current
    val status by manager.state.collectAsState()
    val space by manager.preferences.space().collectAsState()
    val enabled by manager.preferences.enabled().collectAsState()
    val scope = rememberCoroutineScope()
    var setup by remember { mutableStateOf(false) }
    var approve by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<CloudSyncDevice?>(null) }
    val motion = cloudMotionEnabled()
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = false
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = true
            } finally {
                busy = false
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().animateContentSize(CloudMotion.resize(motion)),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (space.isBlank()) {
            SyncHero()
            SyncFeature(
                Icons.Outlined.CollectionsBookmark,
                R.string.cloud_sync_library,
                R.string.cloud_sync_library_summary,
            )
            SyncFeature(Icons.Outlined.Tune, R.string.cloud_sync_settings, R.string.cloud_sync_settings_summary)
            SyncFeature(Icons.Outlined.Lock, R.string.cloud_sync_private, R.string.cloud_sync_private_summary)
            CloudActionButton(stringResource(R.string.cloud_sync_enable), !busy, onClick = { setup = true })
            Text(
                stringResource(R.string.cloud_sync_optional),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (devices) {
            Text(
                stringResource(R.string.cloud_sync_devices_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.cloud_sync_devices_summary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.devices.forEach { device ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Outlined.Smartphone, null, Modifier.size(28.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                device.name.ifBlank { stringResource(R.string.cloud_sync_other_device) },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (device.thisDevice) {
                                Text(
                                    stringResource(R.string.cloud_sync_this_device),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (!device.thisDevice) {
                            IconButton(onClick = { removal = device }, enabled = !busy) {
                                Icon(Icons.Outlined.LinkOff, stringResource(R.string.cloud_sync_remove_device))
                            }
                        }
                    }
                }
            }
            CloudActionButton(stringResource(R.string.cloud_sync_approve), !busy, onClick = { approve = true })
        } else {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Crossfade(
                        targetState = status.phase,
                        animationSpec = tween(CloudMotion.duration(motion, CloudMotion.SURFACE_EXIT_MILLIS)),
                        label = "cloud-sync-status",
                    ) { phase ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 40.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                if (phase ==
                                    CloudSyncPhase.CURRENT
                                ) {
                                    Icons.Outlined.CloudDone
                                } else {
                                    Icons.Outlined.CloudSync
                                },
                                null,
                                Modifier.size(30.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                stringResource(syncStatusLabel(phase)),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (status.pending > 0) {
                        Text(
                            stringResource(R.string.cloud_sync_pending, status.pending),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        if (status.lastVerified > 0) {
                            stringResource(
                                R.string.cloud_sync_checked,
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(status.lastVerified * 1000)),
                            )
                        } else {
                            stringResource(R.string.cloud_sync_not_checked)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status.lastConfirmed > 0) {
                        Text(
                            stringResource(
                                R.string.cloud_sync_confirmed,
                                DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT,
                                ).format(Date(status.lastConfirmed * 1000)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (status.retryAt > System.currentTimeMillis() &&
                        status.phase in setOf(CloudSyncPhase.NETWORK, CloudSyncPhase.RATE_LIMIT, CloudSyncPhase.ERROR)
                    ) {
                        Text(
                            stringResource(
                                R.string.cloud_sync_retry_at,
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.retryAt)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cloud_sync_switch), Modifier.weight(1f))
                        Switch(enabled, { value -> perform { manager.setEnabled(value) } }, enabled = !busy)
                    }
                    OutlinedButton(
                        onClick = { perform { manager.syncNow() } },
                        enabled = enabled && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.cloud_sync_now))
                    }
                    if (status.phase in setOf(CloudSyncPhase.LOCKED, CloudSyncPhase.REMOVED)) {
                        TextButton(onClick = { setup = true }) { Text(stringResource(R.string.cloud_sync_recover)) }
                    }
                }
            }
            Text(
                stringResource(R.string.cloud_sync_local_choices),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.cloud_sync_overrides_summary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SyncPreference(R.string.cloud_sync_appearance, manager.preferences.sharedAppearance())
                    SyncPreference(R.string.cloud_sync_playback, manager.preferences.sharedPlayback())
                    SyncPreference(R.string.cloud_sync_reader, manager.preferences.sharedReader())
                    SyncPreference(R.string.cloud_sync_catalogues, manager.preferences.sharedCatalogues())
                    SyncPreference(R.string.cloud_sync_general, manager.preferences.sharedPreferences())
                    SyncPreference(R.string.cloud_sync_credentials, manager.preferences.sharedCredentials())
                    Text(
                        stringResource(R.string.cloud_sync_credentials_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.cloud_sync_background),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    perform {
                        val copy = manager.safetyCopy()
                        navigator?.push(
                            CloudRestoreScreen(
                                android.net.Uri.fromFile(copy).toString(),
                                context.getString(R.string.cloud_sync_safety_copy),
                                copy.lastModified() / 1000,
                                copy.length(),
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !busy && navigator != null,
            ) {
                Icon(Icons.Outlined.Restore, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cloud_sync_safety_copy))
            }
        }
        CloudInlineError(if (error) stringResource(R.string.cloud_sync_error) else null)
    }
    if (setup) CloudSyncSetupSheet(manager, onDismiss = { setup = false })
    if (approve) CloudSyncApprovalSheet(manager, onDismiss = { approve = false })
    removal?.let { device ->
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text(stringResource(R.string.cloud_sync_remove_device)) },
            text = {
                Text(
                    stringResource(
                        R.string.cloud_sync_remove_summary,
                        device.name.ifBlank { stringResource(R.string.cloud_sync_other_device) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removal = null
                    perform { manager.removeDevice(device.id) }
                }) { Text(stringResource(R.string.cloud_sync_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { removal = null }) { Text(stringResource(R.string.cloud_cancel)) }
            },
        )
    }
}

@Composable
private fun SyncHero() {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.13f), colors.surfaceContainerLow)),
            RoundedCornerShape(28.dp),
        ).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(Icons.Outlined.Devices, null, Modifier.size(40.dp), tint = colors.primary)
        Text(
            stringResource(R.string.cloud_sync_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.cloud_sync_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun SyncFeature(icon: ImageVector, title: Int, summary: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.padding(top = 3.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncPreference(label: Int, preference: Preference<Boolean>) {
    val value by preference.collectAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f).padding(end = 12.dp))
        Switch(value, preference::set)
    }
}

private fun syncStatusLabel(phase: CloudSyncPhase): Int = when (phase) {
    CloudSyncPhase.OFF, CloudSyncPhase.PAUSED -> R.string.cloud_sync_paused
    CloudSyncPhase.CONNECTING -> R.string.cloud_sync_connecting
    CloudSyncPhase.PREPARING -> R.string.cloud_sync_preparing
    CloudSyncPhase.RECEIVING -> R.string.cloud_sync_receiving
    CloudSyncPhase.SENDING -> R.string.cloud_sync_sending
    CloudSyncPhase.CURRENT -> R.string.cloud_sync_current
    CloudSyncPhase.NETWORK -> R.string.cloud_sync_network
    CloudSyncPhase.RATE_LIMIT -> R.string.cloud_sync_rate_limit
    CloudSyncPhase.WAITING_IDLE -> R.string.cloud_sync_wait_idle
    CloudSyncPhase.APPLYING -> R.string.cloud_sync_applying
    CloudSyncPhase.WAITING_CONTENT -> R.string.cloud_sync_wait_content
    CloudSyncPhase.LOCKED -> R.string.cloud_sync_locked
    CloudSyncPhase.OTHER_ACCOUNT -> R.string.cloud_sync_other_account
    CloudSyncPhase.REMOVED -> R.string.cloud_sync_removed
    CloudSyncPhase.ERROR -> R.string.cloud_sync_error
}
