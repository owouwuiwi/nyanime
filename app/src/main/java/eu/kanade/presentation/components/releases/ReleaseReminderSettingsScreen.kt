package eu.kanade.presentation.components.releases

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.ReleaseMonitor
import eu.kanade.tachiyomi.data.releases.ReleaseNotifications
import eu.kanade.tachiyomi.data.releases.ReleasePreferences
import eu.kanade.tachiyomi.data.releases.ReleaseReminderKind
import eu.kanade.tachiyomi.data.releases.ReleaseReminders
import tachiyomi.presentation.core.components.material.Scaffold

/** Independent choices share the existing reminder alarm and its delivery receipts. */
class ReleaseReminderSettingsScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { ReleasePreferences() }
        val reminders by preferences.reminders.changes().collectAsState(preferences.reminders.get())
        val monitoring by preferences.enabled.changes().collectAsState(preferences.enabled.get())
        val lifecycleOwner = LocalLifecycleOwner.current
        var exact by remember { mutableStateOf(ReleaseReminders.exactAllowed(context)) }
        var canPost by remember { mutableStateOf(ReleaseNotifications.canPost(context, ReleaseReminders.CHANNEL)) }
        DisposableEffect(lifecycleOwner, context) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    exact = ReleaseReminders.exactAllowed(context)
                    canPost = ReleaseNotifications.canPost(context, ReleaseReminders.CHANNEL)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val selected = remember {
            mutableStateMapOf<ReleaseReminderKind, Boolean>().apply {
                ReleaseReminderKind.entries.forEach { this[it] = preferences.reminder(it).get() }
            }
        }
        val motion = appMotionEnabled()

        fun toggle(kind: ReleaseReminderKind) {
            val enabled = selected[kind] != true
            selected[kind] = enabled
            preferences.reminder(kind).set(enabled)
            ReleaseReminders.reschedule(context)
        }

        Scaffold(topBar = {
            AppBar(title = stringResource(R.string.release_reminder_times), navigateUp = navigator::pop)
        }) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(top = 12.dp),
            ) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val selectedCount = selected.values.count { it }
                            Text(
                                stringResource(R.string.release_reminder_hero_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.release_reminder_times_count,
                                    selectedCount,
                                    selectedCount,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                stringResource(R.string.release_reminder_times_intro),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (selectedCount == 0) {
                                Text(
                                    stringResource(R.string.release_reminder_none),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            SwitchPreferenceWidget(
                                title = stringResource(R.string.release_reminder),
                                checked = reminders,
                                onCheckedChanged = { enabled ->
                                    preferences.reminders.set(enabled)
                                    ReleaseReminders.reschedule(context)
                                },
                            )
                            if (!reminders) {
                                Text(
                                    stringResource(R.string.release_reminder_times_disabled),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            if (!monitoring) {
                                Text(
                                    stringResource(R.string.release_disabled),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = {
                                    preferences.enabled.set(true)
                                    ReleaseMonitor.setup(context)
                                    ReleaseReminders.reschedule(context)
                                }) { Text(stringResource(R.string.release_enable_monitor)) }
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31 && !exact) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(R.string.release_reminder_exact_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                            "package:${context.packageName}".toUri(),
                                        ),
                                    )
                                }) { Text(stringResource(R.string.release_exact_reminders)) }
                            }
                        }
                    }
                }
                if (!canPost) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(R.string.release_notification_blocked),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                    )
                                }) { Text(stringResource(R.string.release_open_notification_settings)) }
                            }
                        }
                    }
                }
                item {
                    ReminderSectionTitle(
                        stringResource(R.string.release_reminder_day_before),
                        stringResource(R.string.release_reminder_day_before_note),
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dayBefore.forEach { kind ->
                            ReminderChoice(kind, selected[kind] == true, motion) { toggle(kind) }
                        }
                    }
                }
                item {
                    ReminderSectionTitle(
                        stringResource(R.string.release_reminder_same_day),
                        stringResource(R.string.release_reminder_countdown_note),
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        countdown.forEach { kind ->
                            ReminderChoice(kind, selected[kind] == true, motion) { toggle(kind) }
                        }
                    }
                }
                item {
                    ReminderSectionTitle(
                        stringResource(R.string.release_reminder_dayparts),
                        stringResource(R.string.release_reminder_same_day_note),
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sameDayFixed.forEach { kind ->
                            ReminderChoice(kind, selected[kind] == true, motion) { toggle(kind) }
                        }
                    }
                }
                item {
                    Column(
                        Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.release_reminder_times_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val dayBefore = listOf(
            ReleaseReminderKind.ADVANCE,
            ReleaseReminderKind.DAY_BEFORE_MORNING,
            ReleaseReminderKind.DAY_BEFORE_AFTERNOON,
            ReleaseReminderKind.DAY_BEFORE_EVENING,
        )
        val countdown = listOf(
            ReleaseReminderKind.AIRING,
            ReleaseReminderKind.HOUR_BEFORE,
            ReleaseReminderKind.TEN_MINUTES_BEFORE,
            ReleaseReminderKind.FIVE_MINUTES_BEFORE,
            ReleaseReminderKind.TWO_MINUTES_BEFORE,
        )
        val sameDayFixed = listOf(
            ReleaseReminderKind.SAME_DAY_MORNING,
            ReleaseReminderKind.SAME_DAY_AFTERNOON,
            ReleaseReminderKind.SAME_DAY_EVENING,
        )
    }
}

@Composable
private fun ReminderSectionTitle(title: String, description: String) {
    Column(
        Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReminderChoice(kind: ReleaseReminderKind, selected: Boolean, motion: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
        label = "reminder choice",
    )
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
        label = "reminder outline",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = color,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(kind.label()),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = selected, onCheckedChange = null)
        }
    }
}

private fun ReleaseReminderKind.label(): Int = when (this) {
    ReleaseReminderKind.ADVANCE -> R.string.release_reminder_24h
    ReleaseReminderKind.DAY_BEFORE_MORNING,
    ReleaseReminderKind.SAME_DAY_MORNING,
    -> R.string.release_reminder_morning
    ReleaseReminderKind.DAY_BEFORE_AFTERNOON,
    ReleaseReminderKind.SAME_DAY_AFTERNOON,
    -> R.string.release_reminder_afternoon
    ReleaseReminderKind.DAY_BEFORE_EVENING,
    ReleaseReminderKind.SAME_DAY_EVENING,
    -> R.string.release_reminder_evening
    ReleaseReminderKind.HOUR_BEFORE -> R.string.release_reminder_one_hour
    ReleaseReminderKind.TEN_MINUTES_BEFORE -> R.string.release_reminder_ten_minutes
    ReleaseReminderKind.FIVE_MINUTES_BEFORE -> R.string.release_reminder_five_minutes
    ReleaseReminderKind.TWO_MINUTES_BEFORE -> R.string.release_reminder_two_minutes
    ReleaseReminderKind.AIRING -> R.string.release_reminder_airing
}
