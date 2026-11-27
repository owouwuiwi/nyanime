package eu.kanade.presentation.browse.store

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.anime.components.AnimeExtensionIcon
import eu.kanade.presentation.browse.manga.components.MangaExtensionIcon
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreAction
import eu.kanade.tachiyomi.data.extensionstore.StoreActivation
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import eu.kanade.tachiyomi.data.extensionstore.StoreMedium
import eu.kanade.tachiyomi.data.extensionstore.StoreBridge
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import java.text.Collator
import java.util.Locale

@Composable
internal fun StoreSearch(
    query: String,
    onQuery: (String) -> Unit,
    onFilters: () -> Unit,
    filtered: Boolean,
) {
    val c = LocalStoreColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            Modifier.weight(1f),
            shape = RoundedCornerShape(13.dp),
            color = c.surface,
            border = BorderStroke(1.dp, c.line),
        ) {
            Row(
                Modifier.heightIn(min = 52.dp).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(21.dp), tint = c.muted)
                Spacer(Modifier.width(10.dp))
                val hint = stringResource(R.string.store_search)
                BasicTextField(
                    query,
                    onQuery,
                    Modifier.weight(1f).semantics { contentDescription = hint },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    decorationBox = { field ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    hint,
                                    color = c.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            field()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(
                            Icons.Outlined.Close,
                            stringResource(R.string.store_clear_search),
                            tint = c.muted,
                        )
                    }
                }
                if (query.isEmpty()) Spacer(Modifier.width(14.dp))
            }
        }
        Surface(
            onClick = onFilters,
            shape = RoundedCornerShape(13.dp),
            color = if (filtered) c.tint else c.surface,
            border = BorderStroke(1.dp, c.line),
        ) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Tune,
                    stringResource(R.string.store_filters),
                    tint = if (filtered) c.accent else c.text,
                )
            }
        }
    }
}

@Composable
internal fun StoreCategories(selected: StoreMedium?, onSelect: (StoreMedium?) -> Unit) {
    val c = LocalStoreColors.current
    val chipColors = FilterChipDefaults.filterChipColors(
        labelColor = c.muted,
        selectedContainerColor = c.tint,
        selectedLabelColor = c.accent,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected == null,
                { onSelect(null) },
                label = { Text(stringResource(R.string.store_all)) },
                colors = chipColors,
                shape = RoundedCornerShape(50),
            )
        }
        val media = listOf(StoreMedium.MANGA, StoreMedium.ANIME, StoreMedium.NEWS) +
            StoreMedium.entries.filterNot { it in setOf(StoreMedium.MANGA, StoreMedium.ANIME, StoreMedium.NEWS) }
        media.forEach { medium ->
            item(key = medium) {
                FilterChip(
                    selected == medium,
                    { onSelect(medium) },
                    label = { Text(stringResource(medium.label())) },
                    colors = chipColors,
                    shape = RoundedCornerShape(50),
                )
            }
        }
    }
}

@Composable
internal fun StoreActivationFilters(
    selected: StoreActivation,
    onSelect: (StoreActivation) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.store_activation_filter), fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StoreActivation.entries.forEach { state ->
                FilterChip(
                    selected == state,
                    { onSelect(state) },
                    label = {
                        Text(
                            stringResource(
                                when (state) {
                                    StoreActivation.ALL -> R.string.store_all
                                    StoreActivation.ACTIVE -> R.string.store_active_plural
                                    StoreActivation.INACTIVE -> R.string.store_inactive_plural
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun StoreList(
    entries: List<StoreEntry>,
    bridges: Map<String, List<StoreBridge>>,
    steps: Map<String, InstallStep>,
    loading: Boolean,
    failed: Boolean,
    changing: Set<String>,
    onInfo: (StoreEntry) -> Unit,
    onAction: (StoreEntry) -> Unit,
    onCancel: (StoreEntry) -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onCatalogues: () -> Unit,
    onUpdateAll: () -> Unit,
    hasFilters: Boolean,
    modifier: Modifier = Modifier,
    filterKey: String = "",
) {
    val c = LocalStoreColors.current
    val state = rememberLazyListState()
    val locale = LocalConfiguration.current.locales[0]
    val multilingual = stringResource(R.string.store_language_multi)
    val unspecified = stringResource(R.string.store_language_unspecified)
    val updates = remember(entries) { entries.filter { it.installed && it.action == StoreAction.UPDATE } }
    val installed = remember(entries) { entries.filter { it.installed && it.action != StoreAction.UPDATE } }
    val availableBridges = remember(entries) { entries.filter { !it.installed && it.privateOnly } }
    val available = remember(entries, locale, multilingual, unspecified) {
        val collation = Collator.getInstance(locale)
        val languageNames = mutableMapOf<String, String>()
        entries.filterNot { it.installed || it.privateOnly }.groupBy { entry ->
            when {
                entry.languages.isEmpty() -> unspecified
                entry.languages.size > 1 || "all" in entry.languages -> multilingual
                else -> {
                    val language = entry.languages.single()
                    languageNames.getOrPut(language) {
                        Locale.forLanguageTag(language).getDisplayLanguage(locale).ifBlank { language }
                            .replaceFirstChar { it.titlecase(locale) }
                    }
                }
            }
        }.toSortedMap(Comparator { left, right ->
            collation.compare(left, right).takeUnless { it == 0 } ?: left.compareTo(right)
        })
    }
    val row: @Composable (StoreEntry) -> Unit = { entry ->
        StoreCatalogueRow(
            entry,
            bridges[entry.id].orEmpty(),
            steps[entry.packageName],
            entry.packageName in changing,
            onInfo,
            onAction,
            onCancel,
        )
    }
    LaunchedEffect(filterKey) { state.requestScrollToItem(0) }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(bottom = LocalFloatingNavigationInset.current + 16.dp),
    ) {
        if (loading && entries.isNotEmpty()) {
            item(key = "refreshing") {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
            }
        }
        if (failed) {
            item(key = "error") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.store_refresh_error),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.store_retry)) }
                }
            }
        }
        if (updates.isNotEmpty()) {
            item(key = "updates-heading") {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StoreListHeading(stringResource(R.string.store_updates_available), Modifier.weight(1f))
                    if (updates.count { !it.requiresBridgeScopeReview } > 1 && !hasFilters) {
                        TextButton(onClick = onUpdateAll) { Text(stringResource(R.string.store_update_all)) }
                    }
                }
            }
            items(updates, key = StoreEntry::id, contentType = { "extension" }) { row(it) }
        }
        if (installed.isNotEmpty()) {
            item(key = "installed-heading") { StoreListHeading(stringResource(R.string.store_installed)) }
            items(installed, key = StoreEntry::id, contentType = { "extension" }) { row(it) }
        }
        available.forEach { (language, group) ->
            item(key = "language:$language") { StoreListHeading(language) }
            items(group, key = StoreEntry::id, contentType = { "extension" }) { row(it) }
        }
        if (availableBridges.isNotEmpty()) {
            item(key = "bridges-heading") { StoreListHeading(stringResource(R.string.store_related_layers)) }
            items(availableBridges, key = StoreEntry::id, contentType = { "extension" }) { row(it) }
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (loading) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            stringResource(if (hasFilters) R.string.store_no_results else R.string.store_empty),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(if (hasFilters) R.string.store_empty_description else R.string.store_no_catalogue),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.muted,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = if (hasFilters) onClear else onCatalogues) {
                            Text(stringResource(if (hasFilters) R.string.store_clear_filters else R.string.store_catalogues))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreListHeading(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalStoreColors.current.accent,
    )
}

@Composable
internal fun StoreIcon(entry: StoreEntry, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
        when {
            entry.isRin -> RinStoreIcon(entry, Modifier.fillMaxSize())
            entry.anime != null -> AnimeExtensionIcon(entry.anime, Modifier.fillMaxSize())
            entry.manga != null -> MangaExtensionIcon(entry.manga, Modifier.fillMaxSize())
            else -> NewsStoreIcon(entry, Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun StoreBadge(text: String, color: Color, background: Color) {
    Surface(color = background, shape = RoundedCornerShape(7.dp)) {
        Text(
            text,
            Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun EntryButton(
    entry: StoreEntry,
    step: InstallStep?,
    modifier: Modifier,
    onAction: (StoreEntry) -> Unit,
    onCancel: (StoreEntry) -> Unit,
    changing: Boolean = false,
) {
    val downloading = step == InstallStep.Pending || step == InstallStep.Downloading
    val installing = step == InstallStep.Installing
    val label =
        when {
            !entry.addonHostCompatible &&
                entry.action in setOf(
                    StoreAction.INSTALL,
                    StoreAction.UPDATE,
                ) -> R.string.addon_host_unavailable
            downloading -> R.string.store_cancel_download
            installing -> R.string.store_installing
            entry.requiresBridgeScopeReview -> R.string.store_bridge_scope_review
            step == InstallStep.Error -> R.string.store_retry
            entry.rinRecord != null && !entry.enabled -> R.string.store_activate
            else -> entry.action.label()
        }
    StoreButton(
        stringResource(label),
        { if (downloading) onCancel(entry) else onAction(entry) },
        modifier,
        accent = !entry.installed || !entry.enabled || step == InstallStep.Error,
        outlined = entry.installed && entry.enabled && !downloading,
        enabled = !installing &&
            !changing &&
            (
                entry.addonHostCompatible ||
                    entry.action !in setOf(
                        StoreAction.INSTALL,
                        StoreAction.UPDATE,
                    ) ||
                    downloading
                ),
    )
}

@Composable
internal fun StoreButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    outlined: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = LocalStoreColors.current
    val background = if (outlined) {
        c.surface
    } else if (accent) {
        c.accent
    } else {
        c.elevated
    }
    val foreground = if (accent && !outlined) MaterialTheme.colorScheme.onPrimary else c.text
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(12.dp),
        color = background,
        border = if (outlined) BorderStroke(1.dp, c.line) else null,
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = foreground)
            Text(
                text,
                color = foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoreSheet(
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalStoreColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.text,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            if (footer != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                    footer()
                }
            }
        }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

internal fun StoreMedium.label() =
    when (this) {
        StoreMedium.ANIME -> R.string.store_anime
        StoreMedium.MANGA -> R.string.store_manga
        StoreMedium.MANGA_LAYER -> R.string.store_manga_layer
        StoreMedium.TV -> R.string.tv_title
        StoreMedium.NEWS -> R.string.store_news
        StoreMedium.NOVEL -> R.string.novel_library
        StoreMedium.GUIDE -> R.string.store_guide
        StoreMedium.ADDON -> R.string.store_addon
    }

internal fun StoreMedium.icon(): ImageVector =
    when (this) {
        StoreMedium.ANIME -> Icons.Outlined.PlayCircleOutline
        StoreMedium.MANGA -> Icons.AutoMirrored.Outlined.MenuBook
        StoreMedium.MANGA_LAYER -> Icons.Outlined.Extension
        StoreMedium.TV -> Icons.Outlined.LiveTv
        StoreMedium.NEWS -> Icons.Outlined.Newspaper
        StoreMedium.NOVEL -> Icons.AutoMirrored.Outlined.MenuBook
        StoreMedium.GUIDE -> Icons.Outlined.MenuBook
        StoreMedium.ADDON -> Icons.Outlined.Extension
    }

internal fun StoreAction.label() =
    when (this) {
        StoreAction.INSTALL -> R.string.store_install
        StoreAction.OPEN -> R.string.store_open
        StoreAction.UPDATE -> R.string.store_update
        StoreAction.TRUST -> R.string.store_trust
        StoreAction.MANAGE -> R.string.store_manage
    }
