package eu.kanade.presentation.browse.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.extension.ExtensionHomeSupport
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.domain.extension.ExtensionUpdateRepository
import eu.kanade.domain.extension.ExtensionUpdateStatus
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.InstallStep

/** Only generic capabilities and runtime labels reach the presentation layer. */
data class ExtensionEntry(
    val id: String,
    val name: String,
    val version: String,
    val installed: Boolean,
    val languages: Set<String>,
    val repository: String?,
    val metadata: ExtensionPackageMetadata = ExtensionPackageMetadata(),
    val status: ExtensionUpdateStatus = ExtensionUpdateStatus.UNVERIFIED,
    val needsTrust: Boolean = false,
    val nsfw: Boolean = false,
    val step: InstallStep = InstallStep.Idle,
    val sources: List<String> = emptyList(),
    val icon: @Composable () -> Unit,
    val onOpen: () -> Unit,
    val onAction: () -> Unit,
    val onCancel: () -> Unit,
    val onLongClick: () -> Unit,
)

@Composable
fun ExtensionManagerContent(
    entries: List<ExtensionEntry>,
    contentPadding: PaddingValues,
    updates: Int,
    checkFailed: Boolean,
    onUpdateAll: () -> Unit,
    initialCatalogue: Boolean = false,
    permissionWarning: @Composable () -> Unit = {},
) {
    var catalogue by rememberSaveable { mutableStateOf(initialCatalogue) }
    var homeFilter by rememberSaveable { mutableIntStateOf(0) }
    var repository by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var information by remember { mutableStateOf<ExtensionEntry?>(null) }
    val installedScroll = rememberLazyListState()
    val catalogueScroll = rememberLazyListState()
    val modern = LocalNyanimeStyle.current
    val motion = modern && modernMotionEnabled()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        permissionWarning()
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(R.string.extensions_installed, R.string.extensions_catalogue).forEachIndexed { index, title ->
                    val selected = catalogue == (index == 1)
                    val color by animateColorAsState(
                        if (selected) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        tween(if (motion) ModernMotion.EXIT_MILLIS else 0),
                        label = "extensionView",
                    )
                    Surface(
                        onClick = { catalogue = index == 1 },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = color,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                stringResource(title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = homeFilter == 0, onClick = {
                homeFilter = 0
                repository = null
                language = null
            }, label = { Text(stringResource(R.string.extensions_all)) })
            FilterChip(selected = homeFilter == 1, onClick = {
                homeFilter = 1
            }, label = { Text(stringResource(R.string.extensions_home_ready)) })
            FilterChip(selected = homeFilter == 2, onClick = {
                homeFilter = 2
            }, label = { Text(stringResource(R.string.extensions_home_none)) })
            ExtensionChoiceFilter(
                stringResource(R.string.extensions_repository),
                repository,
                entries.mapNotNull { it.repository }.distinct().sorted(),
                { repository = it },
            )
            ExtensionChoiceFilter(
                stringResource(R.string.extensions_language),
                language,
                entries.flatMap { it.languages }.distinct().sorted(),
                { language = it },
            )
        }
        if (checkFailed) {
            Text(
                stringResource(R.string.extensions_check_error),
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!catalogue && updates > 0) {
            TextButton(onClick = onUpdateAll, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
                Text(stringResource(R.string.extensions_update_all, updates))
            }
        }
        AnimatedContent(
            targetState = catalogue,
            modifier = Modifier.weight(1f),
            transitionSpec = { ModernMotion.transform(motion) },
            label = "extensionCatalogue",
        ) { showCatalogue ->
            val visible = entries.filter {
                it.installed != showCatalogue &&
                    (repository == null || repository == it.repository) &&
                    (language == null || language in it.languages) &&
                    when (homeFilter) {
                        1 -> it.metadata.home in setOf(ExtensionHomeSupport.READY, ExtensionHomeSupport.PARTIAL)
                        2 -> it.metadata.home == ExtensionHomeSupport.NONE
                        else -> true
                    }
            }.sortedWith(
                compareBy<ExtensionEntry> { it.status != ExtensionUpdateStatus.AVAILABLE }
                    .thenBy { it.name.lowercase() },
            )
            LazyColumn(
                state = if (showCatalogue) catalogueScroll else installedScroll,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (modern) 10.dp else 2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (visible.isEmpty()) {
                    item("empty") {
                        val emptyLabel = if (showCatalogue) {
                            R.string.extensions_empty_catalogue
                        } else {
                            R.string.extensions_empty_installed
                        }
                        Text(
                            stringResource(emptyLabel),
                            Modifier.fillMaxWidth().padding(24.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                items(visible, key = { it.id }, contentType = { "extension" }) { entry ->
                    ExtensionCard(
                        entry,
                        Modifier.then(if (motion) Modifier.animateItem() else Modifier),
                        onOpen = { if (entry.installed) entry.onOpen() else information = entry },
                    )
                }
            }
        }
    }
    information?.let { entry ->
        AlertDialog(
            onDismissRequest = { information = null },
            title = { Text(entry.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(entry.repository.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.extensions_unknown_hint))
                    if (entry.sources.isNotEmpty()) {
                        Text(stringResource(R.string.extensions_sources), fontWeight = FontWeight.SemiBold)
                        Text(entry.sources.joinToString("\n"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    information = null
                    entry.onAction()
                }) { Text(stringResource(R.string.extensions_install)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    information = null
                    entry.onOpen()
                }) { Text(stringResource(R.string.extensions_open_site)) }
            },
        )
    }
}

@Composable
private fun ExtensionChoiceFilter(
    label: String,
    selected: String?,
    choices: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = {
                Text(
                    selected ?: label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            },
            trailingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.extensions_all)) }, onClick = {
                expanded = false
                onSelect(null)
            })
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) }, onClick = {
                    expanded = false
                    onSelect(choice)
                })
            }
        }
    }
}

@Composable
private fun ExtensionCard(entry: ExtensionEntry, modifier: Modifier, onOpen: () -> Unit) {
    val modern = LocalNyanimeStyle.current
    val running = !entry.step.isCompleted()
    val actionLabel = when {
        running -> R.string.extensions_cancel
        entry.step == InstallStep.Error -> R.string.extensions_retry
        entry.needsTrust -> R.string.extensions_trust
        !entry.installed -> R.string.extensions_install
        entry.status == ExtensionUpdateStatus.AVAILABLE -> R.string.extensions_update
        else -> R.string.extensions_manage
    }
    val actionIcon = when {
        running -> Icons.Outlined.Close
        entry.needsTrust -> Icons.Outlined.Security
        !entry.installed -> Icons.Outlined.Download
        entry.status == ExtensionUpdateStatus.AVAILABLE -> Icons.Outlined.SystemUpdateAlt
        else -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
    }
    Surface(
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = entry.onLongClick),
        shape = RoundedCornerShape(if (modern) 14.dp else 4.dp),
        color = if (modern) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        border = if (modern) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .28f)) else null,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { entry.icon() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val version = listOf(entry.version, entry.languages.joinToString(" · ").uppercase())
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    val origin = entry.metadata.distribution?.label ?: entry.repository
                        ?: stringResource(R.string.extensions_local_origin)
                    Text(
                        listOf(version, origin).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = if (running) entry.onCancel else entry.onAction) {
                    Icon(
                        actionIcon,
                        stringResource(actionLabel),
                        Modifier.size(22.dp),
                        tint = if (entry.status == ExtensionUpdateStatus.AVAILABLE ||
                            !entry.installed ||
                            entry.needsTrust
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val homeIcon = Icons.Outlined.Home.takeIf { entry.metadata.home == ExtensionHomeSupport.READY }
                ExtensionBadge(stringResource(homeLabel(entry.metadata.home)), homeIcon)
                if (entry.metadata.distribution != null) ExtensionBadge(stringResource(R.string.extensions_adapted))
                if (entry.nsfw) ExtensionBadge(stringResource(R.string.extensions_nsfw))
            }
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        when (entry.step) {
                            InstallStep.Installing -> R.string.extensions_installing
                            InstallStep.Downloading -> R.string.extensions_download
                            else -> R.string.extensions_pending
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (entry.step == InstallStep.Error) {
                Text(
                    stringResource(R.string.extensions_error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (entry.installed && entry.status != ExtensionUpdateStatus.CURRENT) {
                Text(
                    stringResource(
                        if (entry.needsTrust) R.string.extensions_authorization else statusLabel(entry.status),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExtensionBadge(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun homeLabel(home: ExtensionHomeSupport) = when (home) {
    ExtensionHomeSupport.READY -> R.string.extensions_home_ready
    ExtensionHomeSupport.PARTIAL -> R.string.extensions_home_partial
    ExtensionHomeSupport.NONE -> R.string.extensions_home_none
    ExtensionHomeSupport.INCOMPATIBLE -> R.string.extensions_home_invalid
    ExtensionHomeSupport.UNKNOWN -> R.string.extensions_home_unknown
}
internal fun statusLabel(status: ExtensionUpdateStatus) = when (status) {
    ExtensionUpdateStatus.AVAILABLE -> R.string.extensions_update
    ExtensionUpdateStatus.CURRENT -> R.string.extensions_current
    ExtensionUpdateStatus.MANUAL -> R.string.extensions_manual
    ExtensionUpdateStatus.PROTECTED -> R.string.extensions_protected
    ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION -> R.string.extensions_different
    ExtensionUpdateStatus.AMBIGUOUS -> R.string.extensions_ambiguous
    ExtensionUpdateStatus.REPOSITORY_UNAVAILABLE -> R.string.extensions_unavailable
    ExtensionUpdateStatus.NOT_IN_CATALOGUE -> R.string.extensions_not_catalogued
    ExtensionUpdateStatus.UNVERIFIED -> R.string.extensions_home_unknown
}

@Composable
fun ExtensionIntegrationDetails(
    metadata: ExtensionPackageMetadata,
    status: ExtensionUpdateStatus,
    repository: String?,
    updateRepositories: List<ExtensionUpdateRepository> = emptyList(),
    onUpdateRepository: (String) -> Unit = {},
    isRin: Boolean = false,
) {
    var chooseRepository by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(homeLabel(metadata.home)), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    when (metadata.home) {
                        ExtensionHomeSupport.NONE -> R.string.extensions_none_hint
                        ExtensionHomeSupport.READY -> R.string.extensions_ready_hint
                        ExtensionHomeSupport.PARTIAL, ExtensionHomeSupport.INCOMPATIBLE -> {
                            R.string.extensions_partial_hint
                        }
                        ExtensionHomeSupport.UNKNOWN -> R.string.extensions_unknown_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            metadata.distribution?.let {
                Text(
                    stringResource(R.string.extensions_adapted) + " · " + it.label,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                repository ?: metadata.distribution?.label ?: stringResource(R.string.extensions_local_origin),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(if (isRin) R.string.store_rin_heading else statusLabel(status)),
                style = MaterialTheme.typography.titleSmall,
            )
            val hint = when (status) {
                ExtensionUpdateStatus.MANUAL -> R.string.extensions_manual_hint
                ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION -> R.string.extensions_different_hint
                ExtensionUpdateStatus.UNVERIFIED -> R.string.extensions_unverified_hint
                ExtensionUpdateStatus.AMBIGUOUS -> R.string.extensions_ambiguous_hint
                ExtensionUpdateStatus.REPOSITORY_UNAVAILABLE -> R.string.extensions_unavailable_hint
                else -> null
            }
            if (isRin) {
                Text(stringResource(R.string.store_rin_description), style = MaterialTheme.typography.bodySmall)
            } else if (hint != null) {
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            }
            if (!isRin && updateRepositories.isNotEmpty()) {
                TextButton(onClick = { chooseRepository = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.SystemUpdateAlt, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.extensions_link_updates), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    if (chooseRepository) {
        AlertDialog(
            onDismissRequest = { chooseRepository = false },
            title = { Text(stringResource(R.string.extensions_link_updates)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.extensions_link_updates_hint))
                    updateRepositories.forEach { repository ->
                        TextButton(onClick = {
                            chooseRepository = false
                            onUpdateRepository(repository.url)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(repository.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    repository.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { chooseRepository = false }) {
                    Text(stringResource(R.string.extensions_cancel))
                }
            },
        )
    }
}

@Composable
fun ExtensionManagerSkeleton(contentPadding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) {
            Box(
                Modifier.fillMaxWidth().height(
                    154.dp,
                ).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)),
            )
        }
    }
}
