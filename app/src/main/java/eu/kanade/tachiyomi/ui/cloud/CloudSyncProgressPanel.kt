package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.cloud.sync.SyncMaintenancePhase
import it.lorelore.nyanime.cloud.sync.SyncMaintenanceStatus
import it.lorelore.nyanime.cloud.sync.SyncMaintenanceWait
import it.lorelore.nyanime.cloud.sync.SyncWorkProgress
import it.lorelore.nyanime.cloud.sync.SyncWorkStage
import it.lorelore.nyanime.cloud.sync.SyncWorkUnit
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CloudWorkProgress(progress: SyncWorkProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                when (progress.stage) {
                    SyncWorkStage.PREPARING -> R.string.cloud_work_preparing
                    SyncWorkStage.RECEIVING -> R.string.cloud_work_receiving
                    SyncWorkStage.SENDING -> R.string.cloud_work_sending
                    SyncWorkStage.APPLYING -> R.string.cloud_work_applying
                    SyncWorkStage.CHECKPOINT -> R.string.cloud_work_checkpoint
                    SyncWorkStage.RESTORING -> R.string.cloud_work_restoring
                },
            ),
            style = MaterialTheme.typography.labelLarge,
        )
        val unit = stringResource(
            when (progress.unit) {
                SyncWorkUnit.PAGES -> R.string.cloud_unit_pages
                SyncWorkUnit.PACKETS -> R.string.cloud_unit_packets
                SyncWorkUnit.PARTS -> R.string.cloud_unit_parts
                SyncWorkUnit.ITEMS -> R.string.cloud_unit_items
            },
        )
        Text(
            if (progress.total != null) {
                stringResource(R.string.cloud_work_count_total, progress.completed, progress.total, unit)
            } else {
                stringResource(R.string.cloud_work_count, progress.completed, unit)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WorkBar(progress.completed, progress.total, progress.active)
    }
}

@Composable
internal fun CloudMaintenanceProgress(status: SyncMaintenanceStatus) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.cloud_maintenance_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val message = when {
                status.wait == SyncMaintenanceWait.PAUSED -> R.string.cloud_maintenance_paused
                status.wait == SyncMaintenanceWait.COPIES -> R.string.cloud_maintenance_copies
                status.wait == SyncMaintenanceWait.RATE_LIMIT -> R.string.cloud_maintenance_rate
                status.wait == SyncMaintenanceWait.NETWORK -> R.string.cloud_maintenance_network
                status.wait == SyncMaintenanceWait.PROOF -> R.string.cloud_maintenance_proof
                status.phase == SyncMaintenancePhase.VERIFYING -> R.string.cloud_sync_maintenance_verifying
                status.phase == SyncMaintenancePhase.CLEANING -> R.string.cloud_sync_maintenance_cleaning
                status.phase == SyncMaintenancePhase.COMPATIBILITY -> R.string.cloud_sync_maintenance_compatibility
                status.phase == SyncMaintenancePhase.CURRENT && status.removedInPass == 0L ->
                    R.string.cloud_maintenance_none
                status.phase == SyncMaintenancePhase.CURRENT -> R.string.cloud_sync_maintenance_current
                else -> R.string.cloud_sync_maintenance_waiting
            }
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            if (status.totalParts > 0) {
                ProgressLine(R.string.cloud_maintenance_parts, status.verifiedParts, status.totalParts)
                if (status.phase == SyncMaintenancePhase.VERIFYING) {
                    WorkBar(status.verifiedParts.toLong(), status.totalParts.toLong(), status.active)
                }
            }
            if (status.totalAnchors > 0) {
                ProgressLine(R.string.cloud_maintenance_anchors, status.checkedAnchors, status.totalAnchors)
            }
            if (status.totalDevices > 0) {
                ProgressLine(R.string.cloud_maintenance_devices, status.compatibleDevices, status.totalDevices)
            }
            if (status.indexedChanges > 0) {
                ProgressLine(R.string.cloud_maintenance_changes, status.indexedChanges)
            }
            if (status.examined > 0) {
                ProgressLine(R.string.cloud_maintenance_examined, status.examined)
                ProgressLine(R.string.cloud_maintenance_removed_pass, status.removedInPass)
            }
            if (status.protected + status.young + status.uncovered > 0) {
                ProgressLine(R.string.cloud_maintenance_protected, status.protected + status.uncovered, status.young)
            }
            if (status.removed > 0) {
                ProgressLine(R.string.cloud_sync_maintenance_removed, status.removed)
            }
            if (status.phase == SyncMaintenancePhase.CLEANING) {
                WorkBar(0, null, status.active)
            }
            if (status.retryAt > 0 &&
                status.wait in setOf(
                    SyncMaintenanceWait.RATE_LIMIT,
                    SyncMaintenanceWait.NETWORK,
                    SyncMaintenanceWait.PROOF,
                )
            ) {
                ProgressLine(R.string.cloud_sync_retry_at, clockTime(status.retryAt))
            }
            if (status.lastActivity > 0) {
                ProgressLine(R.string.cloud_maintenance_activity, clockTime(status.lastActivity))
            }
        }
    }
}

@Composable
private fun ProgressLine(@androidx.annotation.StringRes label: Int, vararg arguments: Any) {
    Text(
        stringResource(label, *arguments),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WorkBar(completed: Long, total: Long?, active: Boolean) {
    val motion = cloudMotionEnabled()
    if (total != null && total > 0) {
        val target = (completed.toFloat() / total).coerceIn(0f, 1f)
        val fraction by animateFloatAsState(
            target,
            tween(CloudMotion.duration(motion, CloudMotion.SURFACE_EXIT_MILLIS)),
            label = "cloud-work",
        )
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else if (active && motion) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private fun clockTime(seconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    .format(Date(seconds * 1000))
