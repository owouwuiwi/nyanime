package eu.kanade.presentation.more

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.theme.NyanimeMark
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.UpdateScreenPhase
import tachiyomi.domain.release.model.ReleaseHistoryEntry

@Composable
fun NewUpdateScreen(
    versionName: String,
    installedVersion: String,
    changelogInfo: String,
    phase: UpdateScreenPhase = UpdateScreenPhase.AVAILABLE,
    downloadProgress: Int? = null,
    installError: String? = null,
    openingInstaller: Boolean = false,
    onCancelDownload: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onRejectUpdate: () -> Unit,
    onAcceptUpdate: () -> Unit,
    releaseHistory: List<ReleaseHistoryEntry> = emptyList(),
    historyLoading: Boolean = false,
    historyUnavailable: Boolean = false,
    onOpenHistory: (() -> Unit)? = null,
) {
    val timeline = remember(changelogInfo, installedVersion, versionName, releaseHistory) {
        mergeUpdateTimeline(installedVersion, versionName, changelogInfo, releaseHistory)
    }
    val motion = appMotionEnabled()
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { UpdateTopBar(versionName, phase.cancellable, onRejectUpdate, onOpenInBrowser, onCancelDownload) },
        bottomBar = {
            UpdateActions(
                phase,
                downloadProgress,
                installError,
                openingInstaller,
                motion,
                onAcceptUpdate,
                onOpenHistory,
                onOpenInBrowser,
            )
        },
    ) { padding ->
        // Version and all notes share one scroll: short windows and large text still reach every item.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "version") {
                UpdateOverview(installedVersion, versionName, timeline)
            }
            if (historyLoading || historyUnavailable) {
                item(key = "history-status") {
                    UpdateHistoryStatus(historyLoading, onOpenInBrowser)
                }
            }
            if (timeline.all { it.groups.isEmpty() }) {
                item(key = "empty-notes") {
                    Text(
                        stringResource(R.string.app_update_no_notes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(timeline, key = { _, notes -> "notes-${notes.version}" }) { index, notes ->
                UpdateVersionCard(notes, initiallyExpanded = index == 0, last = index == timeline.lastIndex)
            }
        }
    }
}

@Composable
private fun UpdateTopBar(
    version: String,
    canCancel: Boolean,
    onBack: () -> Unit,
    onDetails: () -> Unit,
    onCancel: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)) {
        Row(
            Modifier.fillMaxWidth().padding(WindowInsets.statusBars.asPaddingValues())
                .padding(horizontal = 8.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.app_update_close))
            }
            NyanimeMark(Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.app_update_available_heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Nyanime · ${version.removePrefix("v")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.app_update_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.app_update_full_release)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                        onClick = {
                            menuOpen = false
                            onDetails()
                        },
                    )
                    if (canCancel) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.app_update_cancel_download)) },
                            onClick = {
                                menuOpen = false
                                onCancel()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateActions(
    phase: UpdateScreenPhase,
    progress: Int?,
    error: String?,
    openingInstaller: Boolean,
    motion: Boolean,
    onAccept: () -> Unit,
    onHistory: (() -> Unit)?,
    onDetails: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progressStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val percentWidth = with(LocalDensity.current) {
        remember(textMeasurer, progressStyle) {
            textMeasurer.measure(AnnotatedString("888%"), progressStyle).size.width
        }.toDp()
    }
    val fraction by animateFloatAsState(
        targetValue = (progress ?: 0).coerceIn(0, 100) / 100f,
        animationSpec = tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
        label = "updateProgress",
    )
    Surface(color = colors.background, shadowElevation = 8.dp) {
        Column(
            Modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let {
                Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                onHistory?.let {
                    OutlinedButton(
                        onClick = it,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Outlined.History, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.app_update_history_short), Modifier.padding(start = 6.dp))
                    }
                }
                OutlinedButton(
                    onClick = onDetails,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.app_update_github), Modifier.padding(start = 6.dp))
                }
            }
            Button(
                onClick = onAccept,
                enabled = !phase.busy && !openingInstaller,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                    disabledContainerColor = colors.surfaceContainerHigh,
                    disabledContentColor = colors.onSurface,
                ),
            ) {
                AnimatedContent(
                    targetState = phase to openingInstaller,
                    transitionSpec = { ModernMotion.transform(motion) },
                    label = "updateAction",
                ) { (state, opening) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            when {
                                state == UpdateScreenPhase.READY || opening -> Icons.Outlined.SystemUpdate
                                state == UpdateScreenPhase.FAILED || state == UpdateScreenPhase.UNAVAILABLE ->
                                    Icons.Outlined.RestartAlt
                                else -> Icons.Outlined.Download
                            },
                            null,
                            Modifier.size(20.dp),
                        )
                        Text(
                            stringResource(if (opening) R.string.app_update_opening_installer else state.actionLabel()),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state == UpdateScreenPhase.DOWNLOADING) {
                            // Reserve the percentage before the first progress tick, including 100%.
                            Box(Modifier.width(percentWidth), contentAlignment = Alignment.CenterEnd) {
                                progress?.let { Text("$it%", style = progressStyle, maxLines = 1) }
                            }
                        }
                    }
                }
            }
            // The same small slot is reserved throughout; progress never displaces the changelog.
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (phase.busy) {
                    if (phase == UpdateScreenPhase.DOWNLOADING && progress != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else if (motion) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { 0.25f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (phase == UpdateScreenPhase.FAILED || phase == UpdateScreenPhase.UNAVAILABLE) {
                Text(
                    stringResource(
                        if (phase == UpdateScreenPhase.FAILED) {
                            R.string.app_update_failed_description
                        } else {
                            R.string.app_update_file_unavailable_description
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

private fun UpdateScreenPhase.actionLabel() = when (this) {
    UpdateScreenPhase.READY -> R.string.app_update_install_now
    UpdateScreenPhase.QUEUED -> R.string.app_update_waiting
    UpdateScreenPhase.DOWNLOADING -> R.string.app_update_downloading
    UpdateScreenPhase.VERIFYING -> R.string.app_update_verifying
    UpdateScreenPhase.FAILED, UpdateScreenPhase.UNAVAILABLE -> R.string.app_update_retry
    else -> R.string.app_update_update
}
