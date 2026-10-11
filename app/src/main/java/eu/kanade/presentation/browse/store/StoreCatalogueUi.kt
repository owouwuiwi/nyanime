package eu.kanade.presentation.browse.store

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreAction
import eu.kanade.tachiyomi.data.extensionstore.StoreBridge
import eu.kanade.tachiyomi.data.extensionstore.StoreBridgeState
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import mihon.domain.extension.anime.interactor.GetAnimeExtensionStores
import mihon.domain.extensionrepo.manga.interactor.GetMangaExtensionRepo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoreToolbar(
    catalogues: Boolean,
    onBack: () -> Unit,
    onCatalogues: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
) {
    var options by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(stringResource(if (catalogues) R.string.store_catalogues_tab else R.string.store_extensions_heading))
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(if (catalogues) R.string.store_extensions_heading else R.string.store_close),
                )
            }
        },
        actions = {
            if (!catalogues) {
                IconButton(onClick = onCatalogues) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.store_catalogues))
                }
                Box {
                    IconButton(onClick = { options = true }) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.store_more_options))
                    }
                    DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.store_from_file)) },
                            onClick = {
                                options = false
                                onImport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.store_refresh)) },
                            onClick = {
                                options = false
                                onRefresh()
                            },
                        )
                    }
                }
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
internal fun StoreCatalogueRow(
    entry: StoreEntry,
    bridges: List<StoreBridge>,
    step: InstallStep?,
    changing: Boolean,
    onInfo: (StoreEntry) -> Unit,
    onAction: (StoreEntry) -> Unit,
    onCancel: (StoreEntry) -> Unit,
) {
    val busy = step in setOf(InstallStep.Pending, InstallStep.Downloading, InstallStep.Installing)
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val target = entry.candidateVersionName?.takeIf { entry.action == StoreAction.UPDATE && it != entry.version }
                val version = if (target != null && entry.version.isNotBlank()) "${entry.version} → $target" else entry.version
                val title = listOf(
                    storeEntrySubtitle(entry),
                    version,
                    entry.repositoryName.orEmpty(),
                    if (entry.installed) {
                        stringResource(if (entry.imported) R.string.store_imported_apk else R.string.store_installed_apk)
                    } else {
                        ""
                    },
                    if (entry.installed && !entry.enabled) stringResource(R.string.store_inactive) else "",
                ).filter(String::isNotBlank).joinToString(" · ")
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (bridges.isNotEmpty()) {
                    val count = bridges.count(StoreBridge::isActive)
                    val available = bridges.count { it.state == StoreBridgeState.AVAILABLE }
                    val label = when {
                        count > 0 -> pluralStringResource(R.plurals.store_bridges_active, count, count)
                        available > 0 -> pluralStringResource(R.plurals.store_bridges_available, available, available)
                        else -> pluralStringResource(R.plurals.store_bridges_linked, bridges.size, bridges.size)
                    }
                    Text(
                        label,
                        color = LocalStoreColors.current.accent,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        leadingContent = { StoreIcon(entry, Modifier.size(40.dp)) },
        trailingContent = {
            when {
                busy || changing || step == InstallStep.Error || entry.action == StoreAction.UPDATE || entry.action == StoreAction.TRUST ->
                    EntryButton(entry, step, Modifier.widthIn(max = 144.dp), onAction, onCancel, changing)
                !entry.installed -> TextButton(
                    onClick = { onInfo(entry) },
                    enabled = entry.addonHostCompatible,
                ) { Text(stringResource(if (entry.privateOnly) R.string.package_import else R.string.store_install)) }
                else -> IconButton(onClick = { onInfo(entry) }) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.store_settings))
                }
            }
        },
        modifier = Modifier.clickable { onInfo(entry) },
    )
}

@Composable
internal fun storeEntrySubtitle(entry: StoreEntry): String {
    val locale = LocalConfiguration.current.locales[0]
    val languages = remember(entry.languages, locale) {
        entry.languages.filter(String::isNotBlank).sorted().map {
            Locale.forLanguageTag(it).getDisplayLanguage(locale).ifBlank { it }
                .replaceFirstChar { character -> character.titlecase(locale) }
        }
    }
    return (listOf(stringResource(entry.medium.label())) + languages).joinToString(" · ")
}

internal fun StoreBridgeState.label() = when (this) {
    StoreBridgeState.NEEDS_SOURCE -> R.string.store_bridge_requires_source
    StoreBridgeState.AVAILABLE -> R.string.source_layers_unselected
    StoreBridgeState.TRUST -> R.string.store_bridge_check_author
    StoreBridgeState.DISABLED -> R.string.store_bridge_disabled
    StoreBridgeState.ACTIVE -> R.string.source_layers_active
    StoreBridgeState.PARTIALLY_ACTIVE -> R.string.store_bridge_partly_active
    StoreBridgeState.INCOMPATIBLE -> R.string.source_layers_incompatible
    StoreBridgeState.DEPENDENCY_MISSING -> R.string.source_layers_dependency
    StoreBridgeState.FAILED -> R.string.source_layers_failed
    StoreBridgeState.INSTALLED -> R.string.store_bridge_installed
}

@Composable
internal fun StoreCatalogueSettings(
    refreshing: Boolean,
    failed: Boolean,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onVideo: () -> Unit,
    onManga: () -> Unit,
    onTrust: () -> Unit,
    onMigration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val videoFlow = remember { Injekt.get<GetAnimeExtensionStores>().subscribe() }
    val mangaFlow = remember { Injekt.get<GetMangaExtensionRepo>().subscribeAll() }
    val video by videoFlow.collectAsState(initial = emptyList())
    val manga by mangaFlow.collectAsState(initial = emptyList())
    val c = LocalStoreColors.current
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = LocalFloatingNavigationInset.current + 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.store_your_catalogues),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = c.text,
            )
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.store_refresh), tint = c.accent)
            }
        }
        if (refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.elevated)
        } else if (failed) {
            Text(stringResource(R.string.store_refresh_error), color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (video.isEmpty() && manga.isEmpty()) {
            Text(stringResource(R.string.store_no_catalogue), color = c.muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            if (manga.isNotEmpty()) {
                CatalogueGroup(stringResource(R.string.store_manga_catalogues), manga.map { it.name to it.baseUrl }, onManga)
            }
            if (video.isNotEmpty()) {
                CatalogueGroup(stringResource(R.string.store_video_catalogues), video.map { it.name to it.indexUrl }, onVideo)
            }
        }
        StoreButton(stringResource(R.string.extensions_catalogue_add), onAdd, accent = true, icon = Icons.Outlined.Add)
        HorizontalDivider(color = c.line)
        Text(stringResource(R.string.store_tools), style = MaterialTheme.typography.titleMedium, color = c.text)
        TextButton(onClick = onTrust) { Text(stringResource(R.string.package_catalogue_trust)) }
        TextButton(onClick = onMigration) { Text(stringResource(R.string.store_sources_migration)) }
    }
}

@Composable
private fun CatalogueGroup(label: String, catalogues: List<Pair<String, String>>, onManage: () -> Unit) {
    val c = LocalStoreColors.current
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
        catalogues.forEach { (name, url) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 58.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = c.tint,
                    border = BorderStroke(1.dp, c.line),
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(19.dp), tint = c.accent)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(name, color = c.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        remember(url) { runCatching { URI(url).host ?: url }.getOrDefault(url) },
                        color = c.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onManage) { Text(stringResource(R.string.store_manage)) }
            }
            HorizontalDivider(color = c.line)
        }
    }
}
