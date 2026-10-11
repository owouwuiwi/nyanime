package eu.kanade.presentation.browse.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreAction
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension

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
) {
    val c = LocalStoreColors.current
    StoreSheet(onDismiss) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StoreIcon(entry, Modifier.size(58.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetTitle(entry.name)
                Text(
                    entry.version + " · " + entry.languages.joinToString(" / ").uppercase(),
                    color = c.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StoreBadge(if (entry.isRin) "RIN" else "APK", c.accent, c.tint)
        }
        if (entry.isRin &&
            entry.medium !in
            setOf(
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.ADDON,
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.GUIDE,
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.TV,
            )
        ) {
            Text(
                stringResource(R.string.store_rin_description),
                color = c.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
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
        EntryButton(entry, step, Modifier.fillMaxWidth(), { onAction() }, { onCancel() }, changing)
        val anime = (entry.anime as? AnimeExtension.Installed)?.sources.orEmpty()
        val manga = (entry.manga as? MangaExtension.Installed)?.sources.orEmpty()
        val unavailableRin = entry.rinRecord != null &&
            entry.enabled &&
            entry.medium !in
            setOf(
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.ADDON,
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.GUIDE,
                eu.kanade.tachiyomi.data.extensionstore.StoreMedium.TV,
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
        if (entry.installed) {
            if (entry.enabled && entry.action != StoreAction.TRUST && !unavailableRin) {
                StoreButton(
                    stringResource(R.string.store_settings),
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
        entry.repository?.let {
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
