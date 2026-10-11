package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cloud.CloudBackupWorker
import eu.kanade.tachiyomi.data.cloud.CloudRepository
import eu.kanade.tachiyomi.ui.cloud.CloudAdaptiveSheet as AdaptiveSheet

@Composable
internal fun CloudSettings(repository: CloudRepository, onDismiss: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val preferences = repository.preferences
    var enabled by remember { mutableStateOf(preferences.enabled().get()) }
    var wifi by remember { mutableStateOf(preferences.wifiOnly().get()) }
    var hours by remember { mutableStateOf(preferences.intervalHours().get()) }
    var ageMode by remember { mutableStateOf(preferences.maxAgeDays().get() > 0) }
    var count by remember { mutableStateOf(preferences.keepCopies().get().toString()) }
    var days by remember { mutableStateOf(preferences.maxAgeDays().get().takeIf { it > 0 }?.toString() ?: "30") }
    var deviceName by remember { mutableStateOf(preferences.deviceName().get()) }
    val validRetention = if (ageMode) days.toIntOrNull() in 1..3650 else count.toIntOrNull() in 1..100
    val valid = deviceName.isNotBlank() && validRetention
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.86f
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().heightIn(max = maxHeight)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.cloud_settings),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.cloud_cancel)) }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingSwitch(stringResource(R.string.cloud_automatic), enabled) { enabled = it }
                Text(stringResource(R.string.cloud_frequency), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24).forEach { interval ->
                        FilterChip(
                            selected = hours == interval,
                            enabled = enabled,
                            onClick = { hours = interval },
                            label = { Text("$interval h") },
                        )
                    }
                }
                SettingSwitch(stringResource(R.string.cloud_wifi_only), wifi) { wifi = it }
                HorizontalDivider()
                Text(stringResource(R.string.cloud_retention), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !ageMode,
                        onClick = { ageMode = false },
                        label = { Text(stringResource(R.string.cloud_retention_count)) },
                    )
                    FilterChip(
                        selected = ageMode,
                        onClick = { ageMode = true },
                        label = { Text(stringResource(R.string.cloud_retention_age)) },
                    )
                }
                OutlinedTextField(
                    value = if (ageMode) days else count,
                    onValueChange = { text ->
                        if (text.length <= 4 && text.all(Char::isDigit)) {
                            if (ageMode) days = text else count = text
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    shape = RoundedCornerShape(14.dp), isError = !validRetention,
                    supportingText = {
                        Text(stringResource(if (ageMode) R.string.cloud_days_range else R.string.cloud_copies_range))
                    },
                    label = {
                        Text(stringResource(if (ageMode) R.string.cloud_days else R.string.cloud_copies_per_device))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.cloud_retention_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it.take(80).replace("\n", " ") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    isError = deviceName.isBlank(),
                    label = { Text(stringResource(R.string.cloud_device_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cloud_disconnect), color = MaterialTheme.colorScheme.error)
                }
            }
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                CloudActionButton(stringResource(R.string.cloud_apply), valid) {
                    preferences.enabled().set(enabled)
                    preferences.wifiOnly().set(wifi)
                    preferences.intervalHours().set(hours)
                    preferences.deviceName().set(deviceName.trim())
                    preferences.keepCopies().set(count.toIntOrNull()?.coerceIn(1, 100) ?: 10)
                    preferences.maxAgeDays().set(if (ageMode) days.toInt() else 0)
                    CloudBackupWorker.schedule(context)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = value, onCheckedChange = null)
    }
}
