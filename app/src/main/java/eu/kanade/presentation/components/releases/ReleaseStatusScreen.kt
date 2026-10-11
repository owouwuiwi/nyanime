package eu.kanade.presentation.components.releases

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.ReleaseMonitor
import eu.kanade.tachiyomi.data.releases.ReleaseNotifications
import eu.kanade.tachiyomi.data.releases.ReleasePreferences
import eu.kanade.tachiyomi.data.releases.ReleaseReminders
import eu.kanade.tachiyomi.data.releases.ReleaseStatus
import eu.kanade.tachiyomi.data.releases.ReleaseStatusSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold
import java.text.DateFormat
import java.util.Date

class ReleaseStatusScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        var state by remember { mutableStateOf(ReleaseStatusSnapshot()) }
        val preferences = remember { ReleasePreferences() }
        val reminders by preferences.reminders.changes().collectAsState(preferences.reminders.get())
        var canRemind by remember { mutableStateOf(ReleaseNotifications.canPost(context, ReleaseReminders.CHANNEL)) }
        var exact by remember { mutableStateOf(ReleaseReminders.exactAllowed(context)) }
        LaunchedEffect(Unit) {
            while (true) {
                state = withContext(Dispatchers.IO) { ReleaseStatus.snapshot() }
                canRemind = ReleaseNotifications.canPost(context, ReleaseReminders.CHANNEL)
                exact = ReleaseReminders.exactAllowed(context)
                kotlinx.coroutines.delay(3_000)
            }
        }
        Scaffold(topBar = {
            AppBar(title = stringResource(R.string.release_status), navigateUp = navigator::pop)
        }) { padding ->
            LazyColumn(
                Modifier.padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val title = if (ReleasePreferences().enabled.get()) {
                                R.string.release_monitor
                            } else {
                                R.string.release_disabled
                            }
                            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(
                                    R.string.release_status_counts,
                                    state.followed,
                                    state.checked,
                                    state.retrying,
                                ),
                            )
                            val checkedAt = if (state.lastSuccess > 1) {
                                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                stringResource(R.string.release_last_check, format.format(Date(state.lastSuccess)))
                            } else {
                                stringResource(R.string.release_status_waiting)
                            }
                            Text(
                                checkedAt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                ReleaseMonitor.enqueue(context)
                            }) { Text(stringResource(R.string.release_check_now)) }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.release_available),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    if (ReleaseNotifications.canPost(
                                            context,
                                        )
                                    ) {
                                        R.string.release_test_description
                                    } else {
                                        R.string.release_notification_blocked
                                    },
                                ),
                            )
                            TextButton(onClick = {
                                ReleaseStatus.showTestNotification(context)
                            }) { Text(stringResource(R.string.release_test_notification)) }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.release_reminder),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(stringResource(R.string.release_reminder_description))
                            Text(
                                if (reminders) {
                                    context.resources.getQuantityString(
                                        R.plurals.release_reminder_times_count,
                                        preferences.selectedReminders().size,
                                        preferences.selectedReminders().size,
                                    )
                                } else {
                                    stringResource(R.string.release_reminder_times_disabled)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { navigator.push(ReleaseReminderSettingsScreen()) }) {
                                Text(stringResource(R.string.release_reminder_times))
                            }
                            if (!canRemind) Text(stringResource(R.string.release_notification_blocked))
                            TextButton(onClick = {
                                ReleaseStatus.showTestNotification(context, ReleaseReminders.CHANNEL)
                            }) { Text(stringResource(R.string.release_test_reminder)) }
                            if (!exact && Build.VERSION.SDK_INT >= 31) {
                                Text(
                                    stringResource(R.string.release_approximate),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                            "package:${context.packageName}".toUri(),
                                        ),
                                    )
                                }) {
                                    Text(stringResource(R.string.release_exact_reminders))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
