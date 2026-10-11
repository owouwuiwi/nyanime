package it.lorelore.nyanime.studio

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.studio.document.StudioDocumentComponents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class SourceMembershipCleanup(
    val owner: SourceAccessOwner,
    val memberships: Set<TelegramManagedMembership>,
)

internal object StudioSourceRevocation {
    suspend fun revoke(context: Context, record: StudioRecord): List<SourceMembershipCleanup> {
        val snapshots = listOfNotNull(record.draft, record.active, record.previous)
        if (snapshots.any { it.documentResolvers.isNotEmpty() }) {
            val registry = StudioDocumentComponents.get(context)
            registry.revoke(registry.scope(record.draft))
        }
        if (snapshots.none { it.transport == StudioTransport.TELEGRAM }) return emptyList()
        val access = StudioTelegramAccess.get(context)
        val owners = listOf(access.owner(record.draft), access.owner(record.draft, true))
        return owners.mapNotNull { owner ->
            val unused = access.policy.revoke(owner)
            unused.takeIf { it.isNotEmpty() }?.let { SourceMembershipCleanup(owner, it) }
        }
    }
}

/** Only broker-owned, unshared memberships are offered. Closing the dialog keeps subscriptions. */
@Composable
internal fun SourceMembershipCleanupDialog(cleanup: SourceMembershipCleanup, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var remaining by remember(cleanup) { mutableStateOf(cleanup.memberships) }
    var busy by remember(cleanup) { mutableStateOf(false) }
    var failed by remember(cleanup) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.studio_access_leave_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.studio_access_leave_message, remaining.size))
                if (failed) Text(stringResource(R.string.studio_access_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton({
                if (!busy) {
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            for (membership in remaining.toList()) {
                                StudioTelegramAccess.get(context).policy.leave(cleanup.owner, membership)
                                remaining = remaining - membership
                            }
                            dismiss()
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                }
            }, enabled = !busy) { Text(stringResource(R.string.studio_access_leave)) }
        },
        dismissButton = {
            TextButton(dismiss, enabled = !busy) { Text(stringResource(R.string.studio_access_keep)) }
        },
    )
}
