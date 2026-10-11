package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.releases.ReleaseReminderSettingsScreen
import eu.kanade.presentation.components.releases.ReleaseStatusScreen
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.ReleaseMonitor
import eu.kanade.tachiyomi.data.releases.ReleaseNotifications
import eu.kanade.tachiyomi.data.releases.ReleasePreferences
import eu.kanade.tachiyomi.data.releases.ReleaseReminderKind
import eu.kanade.tachiyomi.data.releases.ReleaseReminders
import eu.kanade.tachiyomi.data.releases.ReleaseStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.combine
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

/** One discoverable entry for release alerts; preferences and delivery remain shared. */
object SettingsReleaseNotificationsScreen : SearchableSettings {
    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = AYMR.strings.pref_release_notifications

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { ReleasePreferences() }
        val reminders by preferences.reminders.changes().collectAsState(preferences.reminders.get())
        val monitoring by preferences.enabled.changes().collectAsState(preferences.enabled.get())
        val selectedCount by remember(preferences) {
            combine(ReleaseReminderKind.entries.map { preferences.reminder(it).changes() }) { choices ->
                choices.count { it }
            }
        }.collectAsState(preferences.selectedReminders().size)
        var canNotify by remember { mutableStateOf(ReleaseNotifications.canPost(context)) }
        var canRemind by remember { mutableStateOf(ReleaseNotifications.canPost(context, ReleaseReminders.CHANNEL)) }
        var exact by remember { mutableStateOf(ReleaseReminders.exactAllowed(context)) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, context) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    canNotify = ReleaseNotifications.canPost(context)
                    canRemind = ReleaseNotifications.canPost(context, ReleaseReminders.CHANNEL)
                    exact = ReleaseReminders.exactAllowed(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        return listOf(
            Preference.PreferenceGroup(
                title = context.getString(R.string.release_reminder),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.reminders,
                        title = context.getString(R.string.release_reminder),
                        subtitle = context.getString(R.string.release_reminder_description),
                        onValueChanged = {
                            // The widget saves first; the application scope then schedules the new choice.
                            ContextCompat.getMainExecutor(context).execute { ReleaseReminders.reschedule(context) }
                            true
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = context.getString(R.string.release_reminder_times),
                        subtitle = if (reminders) {
                            context.resources.getQuantityString(
                                R.plurals.release_reminder_times_count,
                                selectedCount,
                                selectedCount,
                            ) +
                                " · " +
                                context.getString(R.string.release_reminder_times_settings_summary)
                        } else {
                            context.getString(R.string.release_reminder_times_disabled)
                        },
                        onClick = { navigator.push(ReleaseReminderSettingsScreen()) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = context.getString(R.string.release_available),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.availability,
                        title = context.getString(R.string.release_available),
                        subtitle = context.getString(R.string.release_available_description),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(AYMR.strings.pref_release_delivery),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.enabled,
                        title = context.getString(R.string.release_monitor),
                        subtitle = context.getString(
                            if (monitoring) R.string.release_monitor_description else R.string.release_disabled,
                        ),
                        onValueChanged = {
                            ContextCompat.getMainExecutor(context).execute {
                                ReleaseMonitor.setup(context)
                                ReleaseReminders.reschedule(context)
                            }
                            true
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = context.getString(R.string.release_open_notification_settings),
                        subtitle = context.getString(
                            if (canNotify && canRemind) {
                                R.string.release_notification_allowed
                            } else {
                                R.string.release_notification_blocked
                            },
                        ),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = context.getString(R.string.release_exact_reminders),
                        subtitle = context.getString(R.string.release_approximate),
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exact,
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    "package:${context.packageName}".toUri(),
                                ),
                            )
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = context.getString(R.string.release_test_notification),
                        subtitle = context.getString(R.string.release_test_description),
                        onClick = { ReleaseStatus.showTestNotification(context) },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = context.getString(R.string.release_test_reminder),
                        onClick = { ReleaseStatus.showTestNotification(context, ReleaseReminders.CHANNEL) },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = context.getString(R.string.release_status),
                        onClick = { navigator.push(ReleaseStatusScreen()) },
                    ),
                ),
            ),
        )
    }
}
