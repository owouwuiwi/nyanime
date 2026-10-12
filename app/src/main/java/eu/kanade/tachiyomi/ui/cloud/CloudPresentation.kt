package eu.kanade.tachiyomi.ui.cloud

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.NyanimeMark
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cloud.CloudRepository
import nyanime.telegram.CloudCopy
import nyanime.telegram.CloudPhase
import nyanime.telegram.CloudProgress
import nyanime.telegram.TelegramState
import tachiyomi.presentation.core.util.collectAsState
import eu.kanade.tachiyomi.ui.cloud.CloudAdaptiveSheet as AdaptiveSheet
import eu.kanade.tachiyomi.ui.cloud.CloudMotion as ModernMotion
import eu.kanade.tachiyomi.ui.cloud.cloudMotionEnabled as appMotionEnabled

@Composable
internal fun CloudWelcome() {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.12f), colors.surfaceContainer)),
            RoundedCornerShape(28.dp),
        ).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NyanimeMark(Modifier.size(44.dp))
            CloudBadge(stringResource(R.string.cloud_private_archive), Icons.Outlined.Lock)
        }
        Text(
            stringResource(R.string.cloud_hero_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.cloud_hero_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudFeature(Icons.Outlined.CollectionsBookmark, stringResource(R.string.cloud_feature_library))
            CloudFeature(Icons.Outlined.History, stringResource(R.string.cloud_feature_progress))
            CloudFeature(Icons.Outlined.Settings, stringResource(R.string.cloud_feature_settings))
        }
    }
}

@Composable
private fun CloudFeature(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CloudBadge(label: String, icon: ImageVector? = null) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp)) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            icon?.let { Icon(it, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun CloudActionButton(label: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun CloudInlineError(error: String?) {
    val motion = appMotionEnabled()
    var lastError by remember { mutableStateOf(error.orEmpty()) }
    SideEffect { if (error != null) lastError = error }
    AnimatedVisibility(
        visible = error != null,
        enter = ModernMotion.enter(ModernMotion.duration(motion, ModernMotion.SURFACE_ENTER_MILLIS)),
        exit = ModernMotion.exit(ModernMotion.duration(motion, ModernMotion.SURFACE_EXIT_MILLIS)),
    ) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
            Text(
                error ?: lastError,
                Modifier.fillMaxWidth().padding(14.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun CloudOverview(
    session: TelegramState,
    latest: CloudCopy?,
    progress: CloudProgress,
    busy: Boolean,
    error: String?,
    onSave: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val transferring = progress.phase in CLOUD_TRANSFERRING
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = colors.surfaceContainerHigh, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(session.account?.name?.take(1).orEmpty(), style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(session.account?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    stringResource(R.string.cloud_account_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = colors.primary)
        }
        Column(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.10f), colors.surfaceContainer)),
                RoundedCornerShape(26.dp),
            ).animateContentSize(ModernMotion.resize(appMotionEnabled())).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (latest != null) Icons.Outlined.CloudDone else Icons.Outlined.CloudSync,
                    null,
                    Modifier.size(30.dp),
                    tint = colors.primary,
                )
                Text(
                    stringResource(R.string.cloud_backup_overview),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                latest?.let { formattedCloudDate(it.serverDate) }
                    ?: stringResource(R.string.cloud_first_backup),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                latest?.let {
                    "${it.manifest.deviceName} · ${Formatter.formatFileSize(LocalContext.current, it.manifest.bytes)}"
                } ?: stringResource(R.string.cloud_no_backup_this_device),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CloudActionButton(stringResource(R.string.cloud_save_now), !busy && !transferring, onClick = onSave)
            if (busy || transferring || progress.phase != CloudPhase.IDLE) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(
                                if (busy && progress.phase == CloudPhase.IDLE) {
                                    R.string.cloud_connecting
                                } else {
                                    cloudPhaseLabel(progress.phase)
                                },
                            ),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (progress.phase == CloudPhase.FAILED) colors.error else colors.onSurfaceVariant,
                        )
                        progress.fraction?.takeIf { transferring }?.let {
                            Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (busy || transferring) {
                        progress.fraction?.let { value ->
                            LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth())
                        } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            CloudInlineError(error)
        }
    }
}

@Composable
internal fun CloudSchedule(repository: CloudRepository, onSettings: () -> Unit) {
    val enabled by repository.preferences.enabled().collectAsState()
    val interval by repository.preferences.intervalHours().collectAsState()
    val wifi by repository.preferences.wifiOnly().collectAsState()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.cloud_automatic), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (enabled) {
                        stringResource(
                            R.string.cloud_schedule_summary,
                            interval,
                            stringResource(if (wifi) R.string.cloud_wifi_only else R.string.cloud_any_network),
                        )
                    } else {
                        stringResource(R.string.cloud_automatic_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
        }
    }
}

@Composable
internal fun CloudEmptyHistory() {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(22.dp))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.CloudSync, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.cloud_empty_history), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun CloudPrivacy() {
    var details by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { details = true }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.cloud_privacy_short),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp))
    }
    if (details) {
        AdaptiveSheet(onDismissRequest = { details = false }) {
            Column(
                Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f)
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.cloud_privacy_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.cloud_privacy), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.cloud_included), style = MaterialTheme.typography.bodyMedium)
                CloudActionButton(stringResource(R.string.cloud_understood), true, onClick = { details = false })
            }
        }
    }
}
