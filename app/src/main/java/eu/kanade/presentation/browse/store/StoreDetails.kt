package eu.kanade.presentation.browse.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreAction
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import eu.kanade.tachiyomi.data.extensionstore.StoreInstallMode
import eu.kanade.tachiyomi.data.layer.MangaLayerRegistry
import eu.kanade.tachiyomi.data.layer.MangaLayerStatus
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun StoreDetails(
    entry: StoreEntry,
    step: InstallStep?,
    changing: Boolean,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    onManage: () -> Unit,
    onFilters: () -> Unit,
    onUninstall: () -> Unit,
    onAnime: (Long) -> Unit,
    onManga: (Long) -> Unit,
    onInstallMode: (StoreInstallMode) -> Unit,
    onAutomaticUpdates: (Boolean) -> Unit,
    onAssociateOrigin: () -> Unit,
    onMigratePublisher: (() -> Unit)?,
    onMigrateAndroidPublisher: (() -> Unit)?,
    onRollbackPublisher: (() -> Unit)?,
    relatedLayers: List<StoreEntry> = emptyList(),
    onLayer: (StoreEntry) -> Unit = {},
    onReturnToSource: (() -> Unit)? = null,
    progress: Float? = null,
) {
    val c = LocalStoreColors.current
    var showVersions by rememberSaveable(entry.packageName) { mutableStateOf(false) }
    var showSignature by rememberSaveable(entry.packageName) { mutableStateOf(false) }
    StoreSheet(onDismiss) {
        if (onReturnToSource != null) {
            TextButton(onClick = onReturnToSource) { Text(stringResource(R.string.store_return_to_source)) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StoreIcon(entry, Modifier.size(58.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetTitle(entry.name)
                Text(
                    storeEntrySubtitle(entry),
                    color = c.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (entry.installed) {
                StoreBadge(
                    stringResource(if (entry.imported) R.string.store_mode_nyanime else R.string.package_system),
                    c.accent,
                    c.tint,
                )
            }
        }
        val locale = LocalConfiguration.current.locales[0]
        val descriptions =
            entry.addonCandidate?.descriptions
                ?: (entry.rinRecord?.manifest ?: entry.rinCandidate?.manifest)?.let {
                    it.guide?.descriptions
                        ?: it.addon?.descriptions
                }.orEmpty()
        val description = entry.addon?.description ?: descriptions[locale.toLanguageTag()]
            ?: descriptions[locale.language] ?: descriptions["en"]
        description?.let {
            Text(it, color = c.muted, style = MaterialTheme.typography.bodyLarge)
        }
        (
            entry.addonCandidate?.hostVersions ?: entry.addon?.hostVersions
                ?: (entry.rinRecord?.manifest ?: entry.rinCandidate?.manifest)?.takeIf {
                    it.medium in setOf("addon", "guide", "tv")
                }?.hostVersions
            )?.let {
            eu.kanade.tachiyomi.ui.addon.AddonCompatibilityNotice(it)
        }
        val layerDeclaration = (entry.rinRecord?.manifest ?: entry.rinCandidate?.manifest)?.mangaLayer
        layerDeclaration?.let { LayerPresentationBlock(it) }
        if (entry.rinRecord != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            if (entry.enabled) R.string.store_active else R.string.store_inactive,
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.store_disable_hint),
                        color = c.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    entry.enabled,
                    { onToggle() },
                    enabled =
                    !changing && (entry.enabled || entry.addonHostCompatible),
                )
            }
        }
        val busy = step in setOf(InstallStep.Pending, InstallStep.Downloading, InstallStep.Installing)
        val canChooseMode = !busy && entry.addonHostCompatible && entry.candidateUrl != null
        val newInstall = canChooseMode && !entry.installed && entry.action == StoreAction.INSTALL
        if (newInstall) {
            StoreInstallChoices(entry, onInstallMode)
        } else if (!entry.privateOnly || busy || entry.action in setOf(StoreAction.TRUST, StoreAction.UPDATE)) {
            EntryButton(entry, step, Modifier.fillMaxWidth(), { onAction() }, { onCancel() }, changing)
        }
        if (canChooseMode && entry.installed && !entry.privateOnly &&
            (
                entry.action == StoreAction.UPDATE ||
                    entry.candidateVersion == entry.installedVersion
                )
        ) {
            TextButton(onClick = { showVersions = !showVersions }) {
                Text(stringResource(R.string.store_install_options))
            }
            if (showVersions) StoreInstallChoices(entry, onInstallMode)
        }
        HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!entry.version.isBlank()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.store_version_label), color = c.muted, style = MaterialTheme.typography.labelMedium)
                    Text(entry.version, fontWeight = FontWeight.SemiBold)
                }
            }
            entry.repositoryName?.takeIf(String::isNotBlank)?.let { name ->
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.store_catalogue), color = c.muted, style = MaterialTheme.typography.labelMedium)
                    Text(name, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (step == InstallStep.Error) {
            Text(
                stringResource(R.string.package_update_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (step == InstallStep.Installed) {
            Text(stringResource(R.string.package_ready), color = c.muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (step == InstallStep.Downloading) {
            if (progress == null) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                androidx.compose.material3.LinearProgressIndicator(progress = {
                    progress
                }, modifier = Modifier.fillMaxWidth())
            }
        }
        entry.installedMetadata?.signers?.takeIf { it.isNotEmpty() }?.let { signers ->
            TextButton(onClick = { showSignature = !showSignature }) { Text(stringResource(R.string.package_publisher)) }
            if (showSignature) {
                Text(
                    signers.joinToString("\n") { it.chunked(8).joinToString(" ") },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
            }
        }
        if (relatedLayers.isNotEmpty()) {
            val registry = remember { Injekt.get<MangaLayerRegistry>() }
            val choices by registry.choices.collectAsState()
            HorizontalDivider(color = c.line)
            Text(stringResource(R.string.store_related_layers), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            relatedLayers.forEach { layer ->
                val active = choices.filter { it.packageName == layer.packageName && it.basePackage == entry.packageName }
                val status = when {
                    !layer.installed -> R.string.source_layers_unselected
                    !layer.enabled -> R.string.store_inactive
                    active.any { it.status == MangaLayerStatus.ACTIVE } -> R.string.source_layers_active
                    active.isNotEmpty() && active.all { it.status == MangaLayerStatus.INCOMPATIBLE } ->
                        R.string.source_layers_incompatible
                    active.any { it.status == MangaLayerStatus.FAILED } -> R.string.source_layers_failed
                    active.any { it.status == MangaLayerStatus.DEPENDENCY_MISSING } -> R.string.source_layers_dependency
                    else -> R.string.store_package_installed
                }
                val summary = (layer.rinRecord?.manifest ?: layer.rinCandidate?.manifest)?.mangaLayer
                    ?.presentationFor(locale)?.summary
                Surface(
                    onClick = { onLayer(layer) },
                    modifier = Modifier.fillMaxWidth(),
                    color = c.elevated,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(layer.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                        Text(stringResource(status), color = c.accent, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        layerDeclaration?.let { MangaLayerScope(it) }
        if (entry.imported && entry.installed) {
            if (entry.repository.isNullOrBlank()) {
                Text(stringResource(R.string.package_manual_updates), color = c.muted)
                StoreButton(
                    stringResource(R.string.package_associate_origin),
                    onAssociateOrigin,
                    Modifier.fillMaxWidth(),
                    outlined = true,
                )
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.package_auto_updates), Modifier.weight(1f))
                    Switch(entry.automaticUpdates, onAutomaticUpdates, enabled = !changing && !busy)
                }
            }
        }
        val anime = (entry.anime as? AnimeExtension.Installed)?.sources.orEmpty()
        val manga = (entry.manga as? MangaExtension.Installed)?.sources.orEmpty()
        val unavailableRin = entry.rinRecord != null &&
            entry.enabled &&
            entry.medium !in
            setOf(
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.ADDON,
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.GUIDE,
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.TV,
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.NEWS,
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.NOVEL,
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.MANGA_LAYER,
            ) &&
            anime.isEmpty() &&
            manga.isEmpty()
        if (unavailableRin) {
            Text(stringResource(R.string.store_sources_unavailable), color = c.muted)
            StoreButton(stringResource(R.string.store_filters), onFilters, Modifier.fillMaxWidth(), outlined = true)
        }
        if (entry.enabled && anime.size + manga.size > 1) {
            Text(
                stringResource(R.string.store_choose_source),
                style = MaterialTheme.typography.titleSmall,
            )
            anime.forEach { source ->
                StoreButton(
                    source.name + " · " + source.lang.uppercase(),
                    { onAnime(source.id) },
                    Modifier.fillMaxWidth(),
                    outlined = true,
                )
            }
            manga.forEach { source ->
                StoreButton(
                    source.name + " · " + source.lang.uppercase(),
                    { onManga(source.id) },
                    Modifier.fillMaxWidth(),
                    outlined = true,
                )
            }
        }
        if (onMigratePublisher != null || onMigrateAndroidPublisher != null) {
            Text(
                stringResource(R.string.store_manga_migration_available),
                color = c.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        onMigratePublisher?.takeUnless { busy || changing }?.let {
            StoreButton(
                stringResource(R.string.store_manga_migration_select), it, Modifier.fillMaxWidth(), outlined = true,
            )
        }
        onMigrateAndroidPublisher?.takeUnless { busy || changing }?.let {
            StoreButton(
                stringResource(R.string.store_android_migration_title), it, Modifier.fillMaxWidth(), outlined = true,
            )
        }
        onRollbackPublisher?.takeUnless { busy || changing }?.let {
            StoreButton(
                stringResource(R.string.store_manga_migration_rollback), it, Modifier.fillMaxWidth(), outlined = true,
            )
        }
        if (entry.installed) {
            if (entry.enabled && entry.action != StoreAction.TRUST && !unavailableRin && !entry.privateOnly) {
                StoreButton(
                    stringResource(if (entry.privateOnly) R.string.store_manage else R.string.store_settings),
                    onManage,
                    Modifier.fillMaxWidth(),
                    outlined = true,
                    icon = Icons.Outlined.Settings,
                )
            }
            HorizontalDivider(color = c.line)
            Text(
                stringResource(R.string.store_uninstall_description),
                color = c.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.store_uninstall))
            }
        }
        entry.repository?.takeIf { it.isNotBlank() }?.let {
            HorizontalDivider(color = c.line)
            Text(
                stringResource(R.string.store_catalogue),
                color = c.muted,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(it, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StoreInstallChoices(entry: StoreEntry, onInstallMode: (StoreInstallMode) -> Unit) {
    val importable = entry.canImport
    if (importable) {
        StoreButton(
            stringResource(R.string.store_add_to_nyanime),
            { onInstallMode(StoreInstallMode.PRIVATE) },
            Modifier.fillMaxWidth(),
            accent = true,
        )
    }
    if (!entry.privateOnly && entry.rinCandidate?.format != "rin") {
        StoreButton(
            stringResource(R.string.store_install_on_android),
            { onInstallMode(StoreInstallMode.SYSTEM) },
            Modifier.fillMaxWidth(),
            accent = !importable,
            outlined = importable,
        )
    }
}
