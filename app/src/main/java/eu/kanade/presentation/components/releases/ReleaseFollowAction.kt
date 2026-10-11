package eu.kanade.presentation.components.releases

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import eu.kanade.presentation.entries.details.DetailQuickAction
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.FollowMode
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseMonitor
import eu.kanade.tachiyomi.data.releases.ReleaseNotifications
import eu.kanade.tachiyomi.data.releases.ReleasePreferences
import eu.kanade.tachiyomi.data.releases.ReleaseReminders
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import eu.kanade.tachiyomi.data.releases.ReleaseSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.util.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowScope.ReleaseFollowAction(
    medium: ReleaseMedium,
    entryId: Long,
    automaticFollowed: Boolean,
    detailsStyle: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ReleaseStore() }
    val preferences = remember { ReleasePreferences() }
    val monitoring by preferences.enabled.collectAsState()
    val subscription by remember(medium, entryId) {
        store.subscriptionFlow(medium, entryId).map { it ?: ReleaseSubscription() }
    }.collectAsState(initial = ReleaseSubscription())
    val followed = subscription.mode == FollowMode.FOLLOW || subscription.mode == FollowMode.AUTO && automaticFollowed
    var open by rememberSaveable(entryId, medium) { mutableStateOf(false) }
    var saving by remember(entryId, medium) { mutableStateOf(false) }
    val color by animateColorAsState(
        if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(ModernMotion.RESIZE_MILLIS),
    )
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ReleaseNotifications.afterCommit(context)
        scope.launch { ReleaseReminders.schedule(context) }
    }
    val save: (ReleaseSubscription) -> Unit = { next ->
        scope.launch {
            saving = true
            try {
                store.setSubscription(medium, entryId, next)
                ReleaseMonitor.enqueue(context)
                ReleaseReminders.schedule(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    context,
                    R.string.release_save_failed,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } finally {
                saving = false
            }
        }
    }
    val onClick = {
        if (followed) {
            open = true
        } else {
            save(subscription.copy(mode = FollowMode.FOLLOW))
            if (!ReleaseNotifications.canPost(context)) open = true
        }
    }
    if (detailsStyle) {
        DetailQuickAction(
            stringResource(if (followed) R.string.detail_following else R.string.detail_follow),
            eu.kanade.presentation.entries.details.TitleDetailsIcons.Bell,
            onClick,
            active = followed,
            enabled = !saving,
        )
    } else {
        TextButton(
            modifier = Modifier.weight(1f),
            enabled = !saving,
            onClick = onClick,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (followed) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone,
                    null,
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (followed) R.string.release_following else R.string.release_follow),
                    color = color,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 24.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.release_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.release_follow_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!monitoring) {
                    Text(stringResource(R.string.release_disabled), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        preferences.enabled.set(true)
                        ReleaseMonitor.setup(context)
                        ReleaseMonitor.enqueue(context)
                    }) {
                        Text(stringResource(R.string.release_enable_monitor))
                    }
                }
                ReleaseOption(
                    stringResource(R.string.release_available),
                    stringResource(R.string.release_available_description),
                    subscription.availability,
                    !saving,
                ) {
                    save(subscription.copy(availability = it))
                }
                if (medium == ReleaseMedium.ANIME) {
                    ReleaseOption(
                        stringResource(R.string.release_reminder),
                        stringResource(R.string.release_reminder_follow_description),
                        subscription.reminder,
                        !saving,
                    ) {
                        save(subscription.copy(reminder = it))
                    }
                }
                if (!ReleaseNotifications.canPost(context)) {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                                ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }) { Text(stringResource(R.string.release_enable_notifications)) }
                }
                if (medium == ReleaseMedium.ANIME &&
                    !ReleaseReminders.exactAllowed(context) &&
                    Build.VERSION.SDK_INT >= 31
                ) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                "package:${context.packageName}".toUri(),
                            ),
                        )
                    }) { Text(stringResource(R.string.release_exact_reminders)) }
                    Text(
                        stringResource(R.string.release_approximate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                TextButton(enabled = !saving, onClick = {
                    save(subscription.copy(mode = FollowMode.IGNORE))
                    open =
                        false
                }) {
                    Text(stringResource(R.string.release_unfollow))
                }
            }
        }
    }
}

@Composable
private fun ReleaseOption(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
