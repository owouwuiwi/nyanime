package eu.kanade.presentation.browse.store

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreAction
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import eu.kanade.tachiyomi.data.extensionstore.StoreMedium
import eu.kanade.tachiyomi.data.extensionstore.StoreShelf
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import mihon.domain.extension.anime.interactor.GetAnimeExtensionStores
import mihon.domain.extensionrepo.manga.interactor.GetMangaExtensionRepo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.util.Locale

@Composable
internal fun StoreShowcaseHeader(onBack: () -> Unit, onImport: () -> Unit) {
    val c = LocalStoreColors.current
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.store_close), tint = c.text)
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                color = c.accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.store_title),
                color = c.text,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        StoreButton(stringResource(R.string.store_from_file), onImport, outlined = true, icon = Icons.Outlined.Add)
    }
}

@Composable
internal fun StoreShowcaseTabs(
    shelf: StoreShelf,
    catalogues: Boolean,
    updates: Int,
    onShelf: (StoreShelf) -> Unit,
    onCatalogues: () -> Unit,
) {
    val c = LocalStoreColors.current
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup()) {
            ShowcaseTab(
                stringResource(R.string.store_explore_tab),
                !catalogues && shelf == StoreShelf.DISCOVER,
                { onShelf(StoreShelf.DISCOVER) },
                Modifier.weight(1f),
            )
            ShowcaseTab(
                stringResource(R.string.store_installed),
                !catalogues && shelf == StoreShelf.OWNED,
                { onShelf(StoreShelf.OWNED) },
                Modifier.weight(1.12f),
                updates,
            )
            ShowcaseTab(
                stringResource(R.string.store_catalogues_tab),
                catalogues,
                onCatalogues,
                Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = c.line)
    }
}

@Composable
private fun ShowcaseTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, count: Int = 0) {
    val c = LocalStoreColors.current
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .selectable(selected, onClick = onClick, role = Role.Tab),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 3.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                Modifier.weight(1f, fill = false),
                color = if (selected) c.accent else c.muted,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            if (count > 0) StoreBadge(count.toString(), c.accent, c.tint)
        }
        if (selected) {
            Spacer(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(.76f).height(3.dp)
                    .background(c.accent, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
            )
        }
    }
}

@Composable
internal fun StoreShowcaseFeature(entry: StoreEntry, onInfo: (StoreEntry) -> Unit) {
    val c = LocalStoreColors.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))
            .padding(16.dp),
    ) {
        val showArt = maxWidth >= 235.dp
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    storeEntrySubtitle(entry),
                    color = scheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    entry.name,
                    color = scheme.onPrimaryContainer,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                StoreButton(stringResource(R.string.store_view_extension), { onInfo(entry) }, outlined = true)
            }
            if (showArt) {
                Box(Modifier.size(width = 90.dp, height = 84.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.padding(start = 16.dp).size(56.dp, 68.dp).rotate(17f)
                            .background(scheme.tertiary, RoundedCornerShape(9.dp)),
                    )
                    Box(
                        Modifier.padding(end = 18.dp).size(56.dp, 68.dp).rotate(-16f)
                            .background(scheme.secondary, RoundedCornerShape(9.dp)),
                    )
                    Box(
                        Modifier.size(56.dp, 68.dp).rotate(4f).background(c.accent, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val icon = if (entry.medium == StoreMedium.MANGA) {
                            Icons.AutoMirrored.Outlined.MenuBook
                        } else {
                            entry.medium.icon()
                        }
                        Icon(icon, null, Modifier.size(28.dp), tint = scheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun StoreShowcaseRow(
    entry: StoreEntry,
    step: InstallStep?,
    changing: Boolean,
    onInfo: (StoreEntry) -> Unit,
    onAction: (StoreEntry) -> Unit,
    onCancel: (StoreEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalStoreColors.current
    val busy = step == InstallStep.Pending || step == InstallStep.Downloading || step == InstallStep.Installing
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 67.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onInfo(entry) }
                    .heightIn(min = 54.dp).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StoreIcon(entry, Modifier.size(42.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        entry.name,
                        color = c.text,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (!entry.installed) {
                            storeEntrySubtitle(entry)
                        } else {
                            val status = when {
                                !entry.enabled -> R.string.store_inactive
                                entry.automaticUpdatePending -> R.string.store_rin_auto_update
                                entry.imported -> R.string.store_mode_nyanime
                                else -> R.string.package_system
                            }
                            val target = entry.candidateVersionName?.takeIf {
                                entry.action == StoreAction.UPDATE && it != entry.version
                            }
                            if (target != null && entry.version.isNotBlank()) {
                                "${entry.version} → $target"
                            } else {
                                listOf(entry.version, stringResource(status)).filter(String::isNotBlank).joinToString(" · ")
                            }
                        },
                        color = c.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!busy && step != InstallStep.Error &&
                (!entry.installed || (entry.enabled && entry.action in setOf(StoreAction.OPEN, StoreAction.MANAGE)))
            ) {
                Surface(
                    onClick = { onInfo(entry) },
                    modifier = Modifier.widthIn(min = 58.dp),
                    color = c.tint,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (entry.installed) R.string.store_manage else R.string.store_view),
                            color = c.accent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            } else {
                EntryButton(entry, step, Modifier.widthIn(max = 150.dp), onAction, onCancel, changing)
            }
        }
        if (busy || changing) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.elevated)
        } else {
            HorizontalDivider(color = c.line)
        }
    }
}

@Composable
internal fun storeEntrySubtitle(entry: StoreEntry): String {
    val locale = LocalConfiguration.current.locales[0]
    val languages = remember(entry.languages, locale) {
        entry.languages.filter(String::isNotBlank).sorted().map {
            Locale.forLanguageTag(it).getDisplayLanguage(locale).ifBlank { it }
                .replaceFirstChar { character -> if (character.isLowerCase()) character.titlecase(locale) else character.toString() }
        }
    }
    return (listOf(stringResource(entry.medium.label())) + languages).joinToString(" · ")
}

internal fun relatedStoreLayers(base: StoreEntry, entries: List<StoreEntry>): List<StoreEntry> {
    if (!base.installed) return emptyList()
    val metadata = base.installedMetadata ?: return emptyList()
    val version = base.installedVersion ?: return emptyList()
    val manifestIds = base.rinRecord?.manifest?.sources.orEmpty().map { it.id }
    val (medium, ids) = when (base.medium) {
        StoreMedium.MANGA -> "manga" to (manifestIds + (base.manga as? MangaExtension.Installed)?.sources.orEmpty().map { it.id })
        StoreMedium.ANIME -> "anime" to (manifestIds + (base.anime as? AnimeExtension.Installed)?.sources.orEmpty().map { it.id })
        StoreMedium.TV -> "tv" to manifestIds
        StoreMedium.NEWS -> "news" to listOf(0L)
        else -> return emptyList()
    }
    return entries.filter { candidate ->
        val declaration = (candidate.rinRecord?.manifest ?: candidate.rinCandidate?.manifest)?.mangaLayer
        candidate.medium == StoreMedium.MANGA_LAYER && declaration?.targets.orEmpty().any { target ->
            target.medium == medium && target.packageName == base.packageName && target.sourceId in ids &&
                (candidate.installed || target.matches(version.toLong(), metadata.signers, metadata.signerHistory))
        }
    }.sortedWith(compareByDescending<StoreEntry> { it.installed }.thenBy { it.name })
}

@Composable
internal fun StoreShowcaseCatalogues(
    refreshing: Boolean,
    failed: Boolean,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onVideo: () -> Unit,
    onManga: () -> Unit,
    onTrust: () -> Unit,
    onStudio: () -> Unit,
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
        TextButton(onClick = onStudio) { Text(stringResource(R.string.studio_title)) }
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
