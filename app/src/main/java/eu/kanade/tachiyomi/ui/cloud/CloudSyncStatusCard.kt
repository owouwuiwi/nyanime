package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncManager
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncPhase
import it.lorelore.nyanime.cloud.sync.SyncMaintenancePhase
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.text.DateFormat
import java.util.Date

/** Only this card observes frequent transport state; preferences and device lists remain still. */
@Composable
internal fun CloudSyncStatusCard(
    manager: CloudSyncManager,
    enabled: Boolean,
    busy: Boolean,
    onSync: () -> Unit,
    onRecover: () -> Unit,
) {
    val status by remember(manager) {
        manager.state.map {
            it.copy(lastVerified = it.lastVerified / 60 * 60, lastConfirmed = it.lastConfirmed / 60 * 60)
        }
            .distinctUntilChanged()
    }.collectAsState(initial = manager.state.value)
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val checked = remember(status.lastVerified, locale) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
            .format(Date(status.lastVerified * 1000))
    }
    val confirmed = remember(status.lastConfirmed, locale) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
            .format(Date(status.lastConfirmed * 1000))
    }
    val retry = remember(status.retryAt, locale) {
        DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(status.retryAt))
    }
    val motion = cloudMotionEnabled()
    val title = when (status.phase) {
        CloudSyncPhase.CURRENT -> R.string.cloud_sync_heading_current
        CloudSyncPhase.OFF, CloudSyncPhase.PAUSED -> R.string.cloud_sync_heading_paused
        CloudSyncPhase.NETWORK -> R.string.cloud_sync_heading_network
        CloudSyncPhase.RATE_LIMIT -> R.string.cloud_sync_heading_rate_limit
        CloudSyncPhase.WAITING_IDLE -> R.string.cloud_sync_heading_idle
        CloudSyncPhase.WAITING_CONTENT -> R.string.cloud_sync_heading_content
        CloudSyncPhase.LOCKED, CloudSyncPhase.REMOVED -> R.string.cloud_sync_heading_locked
        CloudSyncPhase.OTHER_ACCOUNT -> R.string.cloud_sync_heading_account
        CloudSyncPhase.ERROR -> R.string.cloud_sync_heading_error
        else -> R.string.cloud_sync_heading_active
    }
    Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.08f), colors.surfaceContainerLow)),
            ).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = colors.primary.copy(alpha = 0.12f)) {
                    Icon(
                        if (status.phase ==
                            CloudSyncPhase.CURRENT
                        ) {
                            Icons.Outlined.CloudDone
                        } else {
                            Icons.Outlined.CloudSync
                        },
                        null,
                        Modifier.padding(14.dp).size(28.dp),
                        tint = colors.primary,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.cloud_sync_tab),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                    // Group short-lived preparation/sending phases under one stable heading.
                    Crossfade(
                        targetState = title,
                        animationSpec = tween(CloudMotion.duration(motion, CloudMotion.SURFACE_EXIT_MILLIS)),
                        label = "sync-heading",
                    ) { label ->
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                stringResource(syncStatusLabel(status.phase)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                minLines = 2,
            )
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (status.pending > 0) {
                            stringResource(R.string.cloud_sync_pending, status.pending)
                        } else {
                            stringResource(R.string.cloud_sync_no_pending)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        if (status.lastVerified > 0) {
                            stringResource(R.string.cloud_sync_checked, checked)
                        } else {
                            stringResource(R.string.cloud_sync_not_checked)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    if (status.lastConfirmed > 0) {
                        Text(
                            stringResource(R.string.cloud_sync_confirmed, confirmed),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(
                            when (status.maintenance.phase) {
                                SyncMaintenancePhase.WAITING -> R.string.cloud_sync_maintenance_waiting
                                SyncMaintenancePhase.VERIFYING -> R.string.cloud_sync_maintenance_verifying
                                SyncMaintenancePhase.CLEANING -> R.string.cloud_sync_maintenance_cleaning
                                SyncMaintenancePhase.CURRENT -> R.string.cloud_sync_maintenance_current
                                SyncMaintenancePhase.COMPATIBILITY -> R.string.cloud_sync_maintenance_compatibility
                                SyncMaintenancePhase.RETRY -> R.string.cloud_sync_maintenance_retry
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    if (status.maintenance.removed > 0) {
                        Text(
                            stringResource(R.string.cloud_sync_maintenance_removed, status.maintenance.removed),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            if (status.retryAt > 0 &&
                status.phase in setOf(
                    CloudSyncPhase.NETWORK,
                    CloudSyncPhase.RATE_LIMIT,
                    CloudSyncPhase.ERROR,
                )
            ) {
                Text(
                    stringResource(R.string.cloud_sync_retry_at, retry),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            CloudActionButton(
                label = stringResource(R.string.cloud_sync_now),
                enabled = enabled && !busy,
                busy = busy,
                onClick = onSync,
            )
            if (status.phase in setOf(CloudSyncPhase.LOCKED, CloudSyncPhase.REMOVED)) {
                TextButton(onClick = onRecover, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.cloud_sync_recover), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
