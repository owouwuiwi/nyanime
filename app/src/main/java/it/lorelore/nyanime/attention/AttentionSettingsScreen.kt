package it.lorelore.nyanime.attention

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import it.lorelore.nyanime.attention.core.AttentionMode
import it.lorelore.nyanime.attention.core.R
import it.lorelore.nyanime.attention.core.RecoveryMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference

class AttentionSettingsScreen : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.attention_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.attention_done))
                    }
                },
            )
        }) { padding -> AttentionSettingsContent(Modifier.padding(padding)) }
    }
}

@Composable
internal fun AttentionSettingsContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember { AttentionPreferences() }
    val enabled by preferences.enabled.changes().collectAsState(preferences.enabled.get())
    val mode by preferences.mode.changes().collectAsState(preferences.mode.get())
    val selectedMode = AttentionMode.entries.getOrElse(mode) { AttentionMode.Automatic }
    val observeOnly = selectedMode == AttentionMode.ObserveOnly
    val traceStorageError by AttentionUi.traceStorageError.collectAsState()
    val traceChanges by AttentionUi.traceChanges.collectAsState()
    val savedTraceCount by produceState<Int?>(null, observeOnly, traceChanges) {
        value = if (observeOnly) {
            try {
                withContext(Dispatchers.IO) { preferences.traceCount() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AttentionUi.traceStorageError.value = true
                null
            }
        } else {
            null
        }
    }
    val uiScope = rememberCoroutineScope()
    var deleteTraces by remember { mutableStateOf(false) }
    var deletingTraces by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val suspended by AttentionUi.suspended.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) preferences.enabled.set(true)
    }
    val setEnabled: (Boolean) -> Unit = { value ->
        if (!value) {
            verify = false
            AttentionUi.verifying.value = false
            preferences.enabled.set(false)
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            preferences.enabled.set(true)
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    DisposableEffect(Unit) {
        AttentionUi.configuring.value = true
        onDispose {
            AttentionUi.configuring.value = false
            AttentionUi.verifying.value = false
        }
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(if (observeOnly) R.string.attention_observe_intro else R.string.attention_intro),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.attention_beta),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AttentionRecoveryEntry()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.attention_enable)) },
                leadingContent = { Icon(Icons.Outlined.Visibility, null) },
                modifier = Modifier.toggleable(enabled, role = Role.Switch, onValueChange = setEnabled),
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = null)
                },
            )
            Text(
                stringResource(R.string.attention_consent),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (denied ||
            enabled &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Text(stringResource(R.string.attention_permission), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                )
            }) { Text(stringResource(R.string.attention_open_permissions)) }
        }
        PreferenceGroup(stringResource(R.string.attention_mode)) {
            Column(Modifier.selectableGroup()) {
                AttentionMode.entries.forEach { choice ->
                    val automatic = choice == AttentionMode.Automatic
                    val title = if (automatic) R.string.attention_mode_automatic else R.string.attention_mode_observe
                    val hint = if (automatic) {
                        R.string.attention_mode_automatic_hint
                    } else {
                        R.string.attention_mode_observe_hint
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(title)) },
                        supportingContent = { Text(stringResource(hint)) },
                        modifier = Modifier.selectable(
                            selected = selectedMode == choice,
                            role = Role.RadioButton,
                            onClick = { preferences.mode.set(choice.ordinal) },
                        ),
                        trailingContent = { androidx.compose.material3.RadioButton(selectedMode == choice, null) },
                    )
                }
            }
        }
        AttentionReveal(observeOnly) {
            PreferenceGroup(stringResource(R.string.attention_trace_data)) {
                Text(
                    stringResource(R.string.attention_trace_hint),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                savedTraceCount?.let {
                    Text(
                        stringResource(R.string.attention_trace_count, it),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (traceStorageError) {
                    Text(
                        stringResource(R.string.attention_trace_error),
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        AttentionReveal(!observeOnly) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PreferenceGroup(stringResource(R.string.attention_away)) {
                    AttentionSwitch(preferences.away, R.string.attention_away_pause)
                    AttentionSwitch(preferences.resumeAway, R.string.attention_away_resume)
                }
                PreferenceGroup(stringResource(R.string.attention_closed)) {
                    AttentionSwitch(preferences.closed, R.string.attention_closed_pause)
                    AttentionSwitch(preferences.resumeClosed, R.string.attention_closed_resume)
                    Text(
                        stringResource(R.string.attention_closed_hint),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PreferenceGroup(stringResource(R.string.attention_recovery)) {
                    val recovery by preferences.recovery.changes().collectAsState(preferences.recovery.get())
                    val selected = RecoveryMode.entries.getOrElse(recovery) { RecoveryMode.Ask }.ordinal
                    Column(Modifier.selectableGroup()) {
                        listOf(
                            R.string.attention_recovery_off,
                            R.string.attention_recovery_ask,
                            R.string.attention_recovery_auto,
                        ).forEachIndexed {
                                index,
                                title,
                            ->
                            ListItem(
                                headlineContent = { Text(stringResource(title)) },
                                modifier = Modifier.selectable(
                                    selected = selected == index,
                                    role = Role.RadioButton,
                                    onClick = { preferences.recovery.set(index) },
                                ),
                                trailingContent = { androidx.compose.material3.RadioButton(selected == index, null) },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.attention_recovery_hint),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        TextButton(onClick = {
            advanced = !advanced
        }) {
            Text(stringResource(if (observeOnly) R.string.attention_detection_options else R.string.attention_advanced))
        }
        AttentionReveal(advanced) {
            PreferenceGroup(
                stringResource(if (observeOnly) R.string.attention_detection_options else R.string.attention_advanced),
            ) {
                if (!observeOnly) {
                    AttentionTiming(preferences.awaySeconds, R.string.attention_away_delay, 2f..30f)
                    AttentionTiming(preferences.closedSeconds, R.string.attention_closed_delay, 3f..60f)
                    AttentionTiming(preferences.returnMillis, R.string.attention_return_delay, 1_000f..10_000f, 1_000f)
                    AttentionTiming(preferences.marginSeconds, R.string.attention_margin, 0f..15f)
                    AttentionTiming(preferences.waitSeconds, R.string.attention_camera_wait, 10f..180f)
                    Text(
                        stringResource(R.string.attention_camera_wait_hint),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val sensitivity by preferences.sensitivity.changes().collectAsState(preferences.sensitivity.get())
                var sensitivityValue by remember(sensitivity) {
                    mutableStateOf(sensitivity.takeIf { it.isFinite() }?.coerceIn(0.7f, 1.3f) ?: 1f)
                }
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.attention_sensitivity))
                    Slider(
                        sensitivityValue,
                        { sensitivityValue = it },
                        onValueChangeFinished = { preferences.sensitivity.set(sensitivityValue) },
                        valueRange =
                        0.7f..1.3f,
                        steps = 5,
                    )
                }
            }
        }
        TextButton(enabled = enabled, onClick = {
            verify = !verify
            AttentionUi.verifying.value = verify
        }) { Text(stringResource(R.string.attention_verify)) }
        AttentionReveal(verify && enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.attention_verify_hint), style = MaterialTheme.typography.bodyMedium)
                AttentionVerification(Modifier.fillMaxWidth().height(200.dp))
                val status by AttentionUi.status.collectAsState()
                Text(stringResource(status.label), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { AttentionUi.calibrationRequests.value++ }) {
                    Text(stringResource(R.string.attention_calibrate))
                }
                if (status.calibrating) {
                    Text(stringResource(R.string.attention_calibrating))
                } else if (status.calibrated) {
                    Text(stringResource(R.string.attention_calibrated))
                }
            }
        }
        TextButton(enabled = enabled, onClick = { AttentionUi.suspended.value = !suspended }) {
            Text(stringResource(if (suspended) R.string.attention_unsuspend else R.string.attention_suspend))
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                verify = false
                AttentionUi.verifying.value = false
                preferences.defaults()
            }) { Text(stringResource(R.string.attention_reset)) }
            TextButton(onClick = { delete = true }) { Text(stringResource(R.string.attention_clear)) }
            TextButton(onClick = { deleteTraces = true }) { Text(stringResource(R.string.attention_trace_clear)) }
        }
    }
    if (deleteTraces) {
        AlertDialog(
            onDismissRequest = { if (!deletingTraces) deleteTraces = false },
            title = { Text(stringResource(R.string.attention_trace_clear_confirm)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.attention_trace_clear_hint))
                    if (traceStorageError) {
                        Text(
                            stringResource(R.string.attention_trace_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !deletingTraces, onClick = {
                    deletingTraces = true
                    uiScope.launch {
                        try {
                            withContext(Dispatchers.IO) { preferences.clearTraces() }
                            AttentionUi.traceStorageError.value = false
                            AttentionUi.traceChanges.value++
                            deleteTraces = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            AttentionUi.traceStorageError.value = true
                        } finally {
                            deletingTraces = false
                        }
                    }
                }) {
                    Text(
                        stringResource(
                            if (deletingTraces) R.string.attention_trace_deleting else R.string.attention_trace_clear,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !deletingTraces, onClick = {
                    deleteTraces = false
                }) { Text(stringResource(R.string.attention_cancel)) }
            },
        )
    }
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text(stringResource(R.string.attention_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    preferences.clear()
                    AttentionUi.clearRequests.value++
                    delete = false
                }) { Text(stringResource(R.string.attention_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { delete = false }) { Text(stringResource(R.string.attention_cancel)) }
            },
        )
    }
}

@Composable
private fun PreferenceGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Card(shape = RoundedCornerShape(20.dp)) { Column { content() } }
    }
}

@Composable
private fun AttentionSwitch(preference: Preference<Boolean>, title: Int) {
    val value by preference.changes().collectAsState(preference.get())
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        modifier = Modifier.toggleable(value, role = Role.Switch, onValueChange = preference::set),
        trailingContent = { Switch(value, null) },
    )
}

@Composable
private fun AttentionTiming(
    preference: Preference<Int>,
    title: Int,
    range: ClosedFloatingPointRange<Float>,
    divisor: Float = 1f,
) {
    val stored by preference.changes().collectAsState(preference.get())
    var value by remember(stored) { mutableStateOf(stored.toFloat().coerceIn(range)) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.attention_seconds, value / divisor),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(value, { value = it }, onValueChangeFinished = { preference.set(value.toInt()) }, valueRange = range)
    }
}

@Composable
fun AttentionPlayerEntry() {
    val active by it.lorelore.nyanime.attention.core.AttentionControl.active.collectAsState()
    if (!active) return
    ListItem(
        headlineContent = { Text(stringResource(R.string.attention_title)) },
        leadingContent = { Icon(Icons.Outlined.Visibility, null) },
        modifier = Modifier.clickable(onClick = AttentionUi::openSettings),
    )
    AttentionRecoveryEntry()
}

@Composable
private fun AttentionRecoveryEntry() {
    val status by AttentionUi.status.collectAsState()
    val position = status.pointMs ?: return
    val totalSeconds = position / 1_000
    val time = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    ListItem(
        headlineContent = { Text(stringResource(R.string.attention_recover)) },
        supportingContent = { Text(stringResource(R.string.attention_point_time, time)) },
        modifier = Modifier.clickable { AttentionUi.recover() },
    )
}
