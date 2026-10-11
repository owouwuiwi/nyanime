package eu.kanade.presentation.browse.store

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.anime.components.AnimeExtensionIcon
import eu.kanade.presentation.browse.manga.components.MangaExtensionIcon
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreAction
import eu.kanade.tachiyomi.data.extensionstore.StoreActivation
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import eu.kanade.tachiyomi.data.extensionstore.StoreMedium
import eu.kanade.tachiyomi.data.extensionstore.StoreShelf
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset

@Composable
internal fun StoreShelves(
    selected: StoreShelf,
    owned: Int,
    discover: Int,
    onSelect: (StoreShelf) -> Unit,
) {
    val c = LocalStoreColors.current
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.elevated)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StoreShelf.entries.forEach { shelf ->
            val active = selected == shelf
            val color by
                animateColorAsState(
                    if (active) c.surface else c.elevated,
                    tween(if (modernMotionEnabled()) ModernMotion.PAGE_MILLIS else 0),
                    label = "storeShelf",
                )
            Surface(
                onClick = { onSelect(shelf) },
                Modifier.weight(1f).semantics {
                    role = Role.Tab
                    this.selected = active
                },
                shape = RoundedCornerShape(14.dp),
                color = color,
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Text(
                        stringResource(
                            if (shelf == StoreShelf.OWNED) {
                                R.string.store_mine
                            } else {
                                R.string.store_discover
                            },
                        ),
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) c.text else c.muted,
                    )
                    Text(
                        (if (shelf == StoreShelf.OWNED) owned else discover).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.muted,
                    )
                }
            }
        }
    }
}

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
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = c.elevated) {
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
            shape = RoundedCornerShape(16.dp),
            color = if (filtered) c.tint else c.elevated,
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
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected == null,
                { onSelect(null) },
                label = { Text(stringResource(R.string.store_all)) },
            )
        }
        StoreMedium.entries.forEach { medium ->
            item(key = medium) {
                FilterChip(
                    selected == medium,
                    { onSelect(medium) },
                    label = { Text(stringResource(medium.label())) },
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
internal fun StoreGrid(
    entries: List<StoreEntry>,
    steps: Map<String, InstallStep>,
    shelf: StoreShelf,
    searching: Boolean,
    loading: Boolean,
    failed: Boolean,
    changing: Set<String>,
    onInfo: (StoreEntry) -> Unit,
    onAction: (StoreEntry) -> Unit,
    onToggle: (StoreEntry) -> Unit,
    onCancel: (StoreEntry) -> Unit,
    onRetry: () -> Unit,
    onDiscover: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    filterKey: String = "",
    hasFilters: Boolean = false,
    onCatalogues: () -> Unit = {},
) {
    val c = LocalStoreColors.current
    val state = rememberLazyGridState()
    val motion = modernMotionEnabled()
    LaunchedEffect(shelf, filterKey) { state.requestScrollToItem(0) }
    LazyVerticalGrid(
        GridCells.Adaptive(300.dp),
        modifier.fillMaxWidth(),
        state,
        contentPadding =
        PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 4.dp,
            bottom = LocalFloatingNavigationInset.current + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (failed) {
            item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = c.elevated, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
        }
        items(entries, key = StoreEntry::id, contentType = { "extension" }) { entry ->
            StoreTile(
                entry,
                steps[entry.packageName],
                entry.packageName in changing,
                onInfo,
                onAction,
                onToggle,
                onCancel,
                if (motion) Modifier.animateItem() else Modifier,
            )
        }
        if (entries.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (loading) {
                        repeat(3) {
                            Surface(
                                Modifier.fillMaxWidth().height(94.dp),
                                color = c.surface,
                                shape = RoundedCornerShape(20.dp),
                            ) {}
                        }
                    } else {
                        Icon(
                            if (shelf == StoreShelf.OWNED && !searching) {
                                Icons.Outlined.Extension
                            } else {
                                Icons.Outlined.Search
                            },
                            null,
                            Modifier.size(40.dp),
                            tint = c.muted,
                        )
                        Text(
                            stringResource(
                                when {
                                    hasFilters -> R.string.store_no_results
                                    shelf == StoreShelf.OWNED -> R.string.store_empty
                                    else -> R.string.store_empty_catalogue
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                when {
                                    hasFilters -> R.string.store_empty_description
                                    shelf == StoreShelf.OWNED -> R.string.store_empty_owned
                                    else -> R.string.store_empty_catalogue_hint
                                },
                            ),
                            color = c.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        StoreButton(
                            stringResource(
                                when {
                                    hasFilters -> R.string.store_reset_filters
                                    shelf == StoreShelf.OWNED -> R.string.store_discover
                                    else -> R.string.store_catalogues
                                },
                            ),
                            when {
                                hasFilters -> onClear
                                shelf == StoreShelf.OWNED -> onDiscover
                                else -> onCatalogues
                            },
                            outlined = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreSectionHeading(label: String, count: Int, modifier: Modifier = Modifier) {
    val c = LocalStoreColors.current
    Row(
        modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = c.text,
        )
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

@Composable
private fun StoreTile(
    entry: StoreEntry,
    step: InstallStep?,
    changing: Boolean,
    onInfo: (StoreEntry) -> Unit,
    onAction: (StoreEntry) -> Unit,
    onToggle: (StoreEntry) -> Unit,
    onCancel: (StoreEntry) -> Unit,
    modifier: Modifier,
) {
    val c = LocalStoreColors.current
    val status =
        stringResource(if (entry.enabled) R.string.store_active else R.string.store_inactive)
    val installedState = if (entry.isRin || entry.addon != null) {
        status
    } else {
        stringResource(R.string.store_package_installed)
    }
    Surface(
        onClick = { onInfo(entry) },
        modifier = modifier.fillMaxWidth(),
        color = c.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, c.line.copy(alpha = .5f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StoreIcon(entry, Modifier.size(46.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = c.text,
                    )
                    Text(
                        if (entry.installed) {
                            listOf(installedState, entry.languages.joinToString(" / ").uppercase())
                                .filter(String::isNotBlank).joinToString(" · ")
                        } else {
                            entry.languages.joinToString(" / ").uppercase().ifBlank {
                                stringResource(entry.medium.label())
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.rinRecord != null) {
                    Switch(
                        entry.enabled,
                        { onToggle(entry) },
                        enabled = !changing,
                        modifier =
                        Modifier.semantics {
                            contentDescription = entry.name
                            stateDescription = status
                        },
                    )
                } else {
                    IconButton(onClick = { onInfo(entry) }) {
                        Icon(
                            Icons.Outlined.MoreHoriz,
                            stringResource(R.string.store_info),
                            tint = c.muted,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StoreBadge(
                    stringResource(if (entry.imported) R.string.package_imported else R.string.package_system),
                    if (entry.isRin) c.accent else c.muted,
                    if (entry.isRin) c.tint else c.elevated,
                )
                Text(
                    if (entry.automaticUpdatePending) {
                        stringResource(R.string.store_rin_auto_update)
                    } else {
                        entry.version
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                EntryButton(
                    entry,
                    step,
                    Modifier.widthIn(min = 92.dp, max = 148.dp),
                    onAction,
                    onCancel,
                    changing,
                )
            }
            if (
                changing ||
                step == InstallStep.Downloading ||
                step == InstallStep.Pending ||
                step == InstallStep.Installing
            ) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = c.accent,
                    trackColor = c.elevated,
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
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
        StoreMedium.ANIME -> R.string.store_video
        StoreMedium.MANGA -> R.string.store_manga
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
