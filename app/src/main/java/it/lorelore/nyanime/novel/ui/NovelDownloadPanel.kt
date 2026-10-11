package it.lorelore.nyanime.novel.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.novel.NovelDownloadProblem
import it.lorelore.nyanime.novel.NovelDownloadProgress
import it.lorelore.nyanime.novel.NovelDownloadState
import it.lorelore.nyanime.novel.NovelOnlineDocument
import it.lorelore.nyanime.reader.ReaderPanel

/** Same controls and actual state in the book details and the download list. */
@Composable
internal fun NovelDownloadPanel(
    progress: NovelDownloadProgress?,
    busy: Boolean,
    failed: Boolean,
    onChoose: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val active =
        progress?.state == NovelDownloadState.RUNNING ||
            progress?.state == NovelDownloadState.QUEUED
    val fraction =
        progress?.let { it.completedChapters.toFloat() / it.selectedChapters.coerceAtLeast(1) }
            ?: 0f
    val motion = appMotionEnabled()
    val animated by
        animateFloatAsState(
            fraction,
            animationSpec = ModernMotion.controlResponse(motion),
            label = "novel-download-progress",
        )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (progress?.state == NovelDownloadState.READY) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.Download
                    },
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.novel_download_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (progress == null) {
                            stringResource(R.string.novel_download_description)
                        } else {
                            stringResource(
                                R.string.novel_download_progress,
                                progress.completedChapters,
                                progress.selectedChapters,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active) {
                    IconButton(onPause, enabled = !busy) {
                        Icon(Icons.Outlined.Pause, stringResource(R.string.novel_download_pause))
                    }
                } else if (progress != null && progress.state != NovelDownloadState.READY) {
                    IconButton(onRetry, enabled = !busy) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.novel_download_retry))
                    }
                }
            }
            if (active) {
                LinearProgressIndicator(
                    progress = { animated.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val chapter = progress?.activeChapter
                Text(
                    if (
                        progress == null ||
                        progress.state == NovelDownloadState.QUEUED ||
                        chapter == null
                    ) {
                        stringResource(R.string.novel_download_queued)
                    } else {
                        stringResource(
                            R.string.novel_download_resources,
                            progress.activeResources,
                            progress.activeTotalResources,
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress?.state == NovelDownloadState.PAUSED) {
                Text(
                    stringResource(R.string.novel_download_paused),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (progress != null && progress.savedBytes > 0) {
                Text(
                    stringResource(
                        R.string.novel_download_saved_size,
                        android.text.format.Formatter.formatShortFileSize(
                            LocalContext.current,
                            progress.savedBytes,
                        ),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val problem = progress?.problem
            if (failed || problem != null) {
                Text(
                    stringResource(
                        when (problem) {
                            NovelDownloadProblem.SPACE -> R.string.novel_download_space
                            NovelDownloadProblem.LIMIT -> R.string.novel_download_limit
                            NovelDownloadProblem.DAMAGED -> R.string.novel_download_damaged
                            else -> R.string.novel_download_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onChoose, enabled = !busy) {
                    Text(
                        stringResource(
                            if (progress == null) {
                                R.string.novel_download_choose
                            } else {
                                R.string.novel_download_manage
                            },
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (progress != null && !active) {
                    TextButton({ confirmRemove = true }, enabled = !busy) {
                        Text(stringResource(R.string.novel_download_remove))
                    }
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.novel_download_remove)) },
            text = { Text(stringResource(R.string.novel_download_remove_detail)) },
            confirmButton = {
                TextButton({
                    confirmRemove = false
                    onRemove()
                }) {
                    Text(stringResource(R.string.novel_download_remove))
                }
            },
            dismissButton = {
                TextButton({ confirmRemove = false }) {
                    Text(stringResource(R.string.novel_cancel))
                }
            },
        )
    }
}

@Composable
internal fun NovelDownloadSelection(
    document: NovelOnlineDocument,
    currentHref: String?,
    onDismiss: () -> Unit,
    onDownload: (List<String>) -> Unit,
) {
    var mode by remember { mutableStateOf(0) }
    var selected by
        remember(document) {
            mutableStateOf<Set<String>>(document.chapters.mapTo(linkedSetOf()) { it.href })
        }
    val current = document.chapters.indexOfFirst { it.href == currentHref }.coerceAtLeast(0)
    var confirmed by remember { mutableStateOf<List<String>?>(null) }
    ReaderPanel(
        stringResource(R.string.novel_download_choose),
        onDismiss = {
            val choice = confirmed
            if (choice == null) onDismiss() else onDownload(choice)
        },
        dismissRequested = confirmed != null,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, fill = false)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.novel_download_selection_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    R.string.novel_download_all,
                    R.string.novel_download_from_here,
                    R.string.novel_download_custom,
                )
                    .forEachIndexed { index, label ->
                        FilterChip(
                            selected = mode == index,
                            onClick = {
                                mode = index
                                if (index < 2) {
                                    selected =
                                        document.chapters
                                            .drop(if (index == 0) 0 else current)
                                            .mapTo(linkedSetOf()) { it.href }
                                }
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
            }
            if (mode == 2) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(document.chapters, key = { it.href }) { chapter ->
                        Row(
                            Modifier.fillMaxWidth()
                                .toggleable(
                                    value = chapter.href in selected,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        selected =
                                            if (it) {
                                                selected + chapter.href
                                            } else {
                                                selected - chapter.href
                                            }
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(chapter.href in selected, onCheckedChange = null)
                            Text(
                                chapter.title.ifBlank {
                                    stringResource(R.string.novel_untitled_chapter)
                                },
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                            )
                        }
                    }
                }
            }
            Button(
                onClick = {
                    confirmed = document.chapters.map { it.href }.filter { it in selected }
                },
                enabled = selected.isNotEmpty() && confirmed == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.novel_download_selected_count,
                        selected.size,
                        selected.size,
                    ),
                )
            }
        }
    }
}
