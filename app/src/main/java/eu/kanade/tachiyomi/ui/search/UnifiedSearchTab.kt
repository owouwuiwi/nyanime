package eu.kanade.tachiyomi.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.discovery.LocalDiscoveryHeaderHost
import eu.kanade.presentation.discovery.SourceHomeWordmark
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.presentation.theme.LocalDarkTheme
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.watch.WatchTogetherButton
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.search.SearchMedium
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

val LocalUnifiedSearch = compositionLocalOf<UnifiedSearchScreenModel?> { null }

data object UnifiedSearchTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            2u,
            stringResource(R.string.unified_search_search),
            rememberVectorPainter(Icons.Outlined.Search),
        )

    @Composable
    override fun Content() {
        if (eu.kanade.tachiyomi.ui.news.LocalNewsSearch.current) {
            eu.kanade.tachiyomi.ui.news.NewsContent(
                requireNotNull(eu.kanade.tachiyomi.ui.news.LocalNewsModel.current),
                search = true,
            )
            return
        }
        val model = requireNotNull(LocalUnifiedSearch.current)
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        var chosen by remember { mutableStateOf<UnifiedSearchCard?>(null) }
        LaunchedEffect(state.scrollRevision) { chosen = null }
        val open: (UnifiedSearchTarget) -> Unit = { target ->
            chosen = null
            if (target.medium == SearchMedium.VIDEO) {
                navigator.push(AnimeScreen(target.id))
            } else {
                navigator.push(MangaScreen(target.id))
            }
        }
        UnifiedSearchContent(
            state = state,
            model = model,
            onOpen = { card ->
                val live = card.entry.tvChannel
                if (live != null) {
                    eu.kanade.tachiyomi.ui.tv.TvActions.play(context, live)
                } else if (card.targets.size == 1) {
                    open(card.targets.single())
                } else {
                    chosen = card
                }
            },
            onAdvanced = { route ->
                model.showPanel(null)
                val section = UnifiedSearchFilters.section(route, state.selected)
                if (route.tv) {
                    navigator.push(eu.kanade.tachiyomi.ui.tv.TvBrowseScreen())
                } else if (route.medium ==
                    SearchMedium.VIDEO
                ) {
                    navigator.push(
                        BrowseAnimeSourceScreen(
                            route.source,
                            state.query,
                            section,
                            route.home?.browseFilters.orEmpty(),
                        ),
                    )
                } else {
                    navigator.push(BrowseMangaSourceScreen(route.source, state.query, section?.selections.orEmpty()))
                }
            },
        )
        chosen?.let { card ->
            UnifiedSearchSheet(onDismiss = { chosen = null }, title = card.entry.title) {
                LazyColumn(Modifier.heightIn(max = 540.dp)) {
                    items(card.targets, key = { "${it.medium}:${it.id}" }) { target ->
                        val route = state.routes.firstOrNull {
                            it.source == target.source && it.medium == target.medium
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                                open(target)
                            }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    route?.name ?: target.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    target.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(route?.language?.uppercase().orEmpty(), style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Outlined.ExpandMore, null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedSearchContent(
    state: UnifiedSearchState,
    model: UnifiedSearchScreenModel,
    onOpen: (UnifiedSearchCard) -> Unit,
    onAdvanced: (UnifiedSearchRoute) -> Unit,
) {
    val grid = rememberLazyGridState()
    val motion = appMotionEnabled()
    val results = remember(state.cards, state.includeOtherResults, state.query) { state.results }
    var appliedScrollRevision by rememberSaveable { mutableStateOf(state.scrollRevision) }
    LaunchedEffect(state.scrollRevision) {
        if (appliedScrollRevision != state.scrollRevision) {
            grid.scrollToItem(0)
            appliedScrollRevision = state.scrollRevision
        }
    }
    LaunchedEffect(grid, model) {
        snapshotFlow {
            val info = grid.layoutInfo
            val nearEnd = info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 5
            nearEnd to (grid.firstVisibleItemIndex > 1)
        }.distinctUntilChanged().collect { (nearEnd, reading) ->
            if (reading) model.preserveBroadResults()
            // A shortened grid must not crawl every hidden source page to fill the viewport.
            if (nearEnd && !model.state.value.results.narrowed) model.loadMore()
        }
    }
    LaunchedEffect(state.loading, state.cards.size) {
        val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (!state.loading && !results.narrowed && last >= grid.layoutInfo.totalItemsCount - 5) model.loadMore()
    }
    val context = LocalContext.current
    var announcedFilters by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(state.removedFilters) {
        if (state.removedFilters > announcedFilters) {
            android.widget.Toast.makeText(
                context,
                R.string.unified_search_filters_removed,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            announcedFilters = state.removedFilters
        }
    }
    Column(Modifier.fillMaxSize().privacyRegion(PrivacyArea.SEARCH)) {
        if (LocalDiscoveryHeaderHost.current == null) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 54.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceHomeWordmark(null, Modifier.weight(1f))
                WatchTogetherButton()
                IconButton(onClick = { model.showPanel(UnifiedSearchPanel.SETTINGS) }) {
                    Icon(Icons.Outlined.Tune, stringResource(R.string.unified_search_settings))
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(128.dp),
            state = grid,
            modifier = Modifier.fillMaxSize().testTag("unified_search_results"),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = LocalFloatingNavigationInset.current + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                if (state.exploring) {
                                    R.string.unified_search_explore
                                } else {
                                    R.string.unified_search_results
                                },
                            ),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            onClick = { model.showPanel(UnifiedSearchPanel.CATEGORIES) },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.widthIn(max = 144.dp).heightIn(min = 44.dp)
                                .testTag("unified_search_category"),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    categoryLabel(state),
                                    Modifier.weight(
                                        1f,
                                        fill = false,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    Icons.Outlined.ExpandMore,
                                    stringResource(R.string.unified_search_categories),
                                    Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    if (!state.exploring) {
                        Text(
                            stringResource(
                                R.string.unified_search_query_scope,
                                state.query.ifBlank { state.input.trim() },
                                categoryLabel(state),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (!state.exploring) {
                item(key = "assistance", span = { GridItemSpan(maxLineSpan) }) {
                    val corrected = state.assistance.values.mapNotNull { it.correctedQuery }.distinct()
                    Surface(
                        onClick = { model.showPanel(UnifiedSearchPanel.ASSISTANCE) },
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("unified_search_assistance"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    state.assistance.isEmpty() && state.loading ->
                                        stringResource(R.string.unified_search_preparing_help)
                                    state.exact -> stringResource(R.string.search_exact_active)
                                    corrected.isNotEmpty() -> stringResource(
                                        R.string.search_recovered,
                                        corrected.joinToString(" · "),
                                    )
                                    state.assistance.values.any {
                                        it.loading
                                    } -> stringResource(R.string.search_finding_titles)
                                    else -> stringResource(R.string.unified_search_search_help)
                                },
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (state.cards.isNotEmpty()) {
                item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(
                                if (state.exploring) {
                                    R.string.unified_search_discover
                                } else {
                                    R.string.unified_search_found
                                },
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            results.cards.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(results.cards, key = { _, card -> card.key }, span = { index, _ ->
                GridItemSpan(if (index == 0 && state.exploring) maxLineSpan else 1)
            }) { index, card ->
                UnifiedSearchPoster(
                    card,
                    index == 0 && state.exploring,
                    { onOpen(card) },
                    Modifier.animateItem(
                        fadeInSpec = if (motion) tween(ModernMotion.PAGE_MILLIS) else null,
                        placementSpec = if (motion &&
                            !grid.isScrollInProgress
                        ) {
                            tween(ModernMotion.RESIZE_MILLIS)
                        } else {
                            null
                        },
                        fadeOutSpec = if (motion) tween(ModernMotion.EXIT_MILLIS) else null,
                    ),
                )
            }
            if (results.narrowed) {
                item(key = "other-results", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        onClick = model::showOtherResults,
                        modifier = Modifier.fillMaxWidth().testTag("unified_search_other_results"),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(stringResource(R.string.unified_search_other_results, results.otherCount))
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp))
                    }
                }
            }
            if (state.loading || state.initializing) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.unified_search_searching),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else if (state.cards.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(28.dp))
                        Text(
                            stringResource(
                                when {
                                    state.offline -> R.string.unified_search_offline
                                    state.exploring -> R.string.unified_search_begin
                                    state.input.trim() != state.query -> R.string.unified_search_confirm
                                    else -> R.string.unified_search_empty
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.selectedGenres.isNotEmpty()) {
                            TextButton(onClick = model::clearGenres) {
                                Text(stringResource(R.string.unified_search_clear_filters))
                            }
                        }
                    }
                }
            }
            if (state.failures > 0) {
                item(key = "errors", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = model::retryFailures, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.unified_search_retry_sources, state.failures))
                    }
                }
            }
            if (state.more && !state.loading) {
                item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        onClick = model::loadMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.unified_search_more))
                    }
                }
            }
        }
    }
    state.panel?.let { panel ->
        UnifiedSearchPanelContent(panel, state, model, onAdvanced)
    }
}

@Composable
private fun categoryLabel(state: UnifiedSearchState): String =
    state.categories.firstOrNull { it.id == state.category }?.title
        ?: stringResource(R.string.unified_search_all)

@Composable
private fun UnifiedSearchPoster(
    card: UnifiedSearchCard,
    featured: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = card.entry
    val context = LocalContext.current
    val motion = appMotionEnabled()
    val tint = if (entry.tvChannel !=
        null
    ) {
        Color(0xFF9BD1E8)
    } else if (entry.medium == SearchMedium.MANGA) {
        Color(0xFF9BD1E8)
    } else {
        Color(0xFFFFB278)
    }
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().then(
                when {
                    featured -> Modifier.height(if (entry.tvChannel != null) 160.dp else 210.dp)
                    entry.tvChannel != null -> Modifier.aspectRatio(16f / 9f)
                    else -> Modifier.aspectRatio(0.72f)
                },
            )
                .clip(RoundedCornerShape(if (featured) 22.dp else 16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onOpen),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(entry.artwork).crossfade(if (motion) 180 else 0).build(),
                contentDescription = entry.title,
                contentScale = if (entry.tvChannel != null) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(
                    if (entry.tvChannel !=
                        null
                    ) {
                        Modifier.padding(18.dp)
                    } else {
                        Modifier
                    },
                ),
            )
            if (featured) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.88f),
                            ),
                        ),
                    ),
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xDC15171B),
                contentColor = tint,
                modifier = Modifier.align(if (featured) Alignment.BottomStart else Alignment.TopStart).padding(10.dp),
            ) {
                Text(
                    entry.categoryTitle.ifBlank {
                        stringResource(
                            if (entry.medium == SearchMedium.MANGA) {
                                R.string.unified_search_manga
                            } else {
                                R.string.unified_search_video
                            },
                        )
                    },
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (featured) {
                Text(
                    entry.title,
                    Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 46.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!featured) {
            Text(
                entry.title,
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(top = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.targets.size > 1) {
            TextButton(
                onClick = onOpen,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text(
                    stringResource(R.string.unified_search_sources, card.targets.size),
                    style = MaterialTheme.typography.labelSmall,
                )
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnifiedSearchSheet(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            content()
        }
    }
}

@Composable
private fun UnifiedSearchPanelContent(
    panel: UnifiedSearchPanel,
    state: UnifiedSearchState,
    model: UnifiedSearchScreenModel,
    onAdvanced: (UnifiedSearchRoute) -> Unit,
) {
    val preferences = remember { Injekt.get<SourcePreferences>() }
    UnifiedSearchSheet(
        onDismiss = { model.showPanel(null) },
        title = stringResource(
            when (panel) {
                UnifiedSearchPanel.CATEGORIES -> R.string.unified_search_categories
                UnifiedSearchPanel.FILTERS -> R.string.unified_search_filters
                UnifiedSearchPanel.ASSISTANCE -> R.string.unified_search_search_help
                UnifiedSearchPanel.SETTINGS -> R.string.unified_search_settings
            },
        ),
    ) {
        LazyColumn(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (panel) {
                UnifiedSearchPanel.CATEGORIES -> {
                    item {
                        UnifiedSearchCategoryRow(
                            stringResource(R.string.unified_search_all),
                            state.category == null,
                        ) {
                            model.selectCategory(null)
                            model.showPanel(null)
                        }
                    }
                    items(state.categories, key = { it.orderKey }) { category ->
                        UnifiedSearchCategoryRow(
                            category.title,
                            category.id == state.category,
                        ) {
                            model.selectCategory(category.id)
                            model.showPanel(null)
                        }
                    }
                }
                UnifiedSearchPanel.FILTERS -> {
                    item {
                        Text(
                            stringResource(
                                R.string.unified_search_supported_sources,
                                state.eligible.map {
                                    it.medium to it.source
                                }.distinct().size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(state.genres, key = { it.key }) { genre ->
                        UnifiedSearchCategoryRow(
                            genre.label,
                            genre.key in state.selectedGenres,
                        ) {
                            model.toggleGenre(genre.key)
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.unified_search_advanced),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                    items(state.eligible.distinctBy { it.medium to it.source }, key = { it.key }) { route ->
                        TextButton(onClick = {
                            onAdvanced(route)
                        }) {
                            Text(route.name + " · " + route.language.uppercase())
                        }
                    }
                }
                UnifiedSearchPanel.ASSISTANCE -> {
                    item {
                        TextButton(onClick = {
                            model.toggleExact()
                            model.showPanel(null)
                        }) {
                            Text(
                                stringResource(
                                    if (state.exact) R.string.search_smart_resume else R.string.search_exact,
                                ),
                            )
                        }
                    }
                    items(
                        state.assistance.values.flatMap {
                            it.suggestions
                        }.distinctBy {
                            it.title
                        },
                        key = {
                            it.title
                        },
                    ) { suggestion ->
                        TextButton(onClick = {
                            model.useSuggestion(suggestion.title)
                            model.showPanel(null)
                        }) {
                            Text(suggestion.title)
                        }
                    }
                }
                UnifiedSearchPanel.SETTINGS -> {
                    item { UnifiedSearchSetting(R.string.unified_search_remember, preferences.rememberUnifiedSearch()) }
                    item {
                        UnifiedSearchSetting(
                            R.string.unified_search_keyboard,
                            preferences.unifiedSearchKeyboardOnOpen(),
                        )
                    }
                    item { UnifiedSearchSetting(R.string.unified_search_live, preferences.liveUnifiedSearch()) }
                    item { UnifiedSearchSetting(R.string.search_tolerant, preferences.tolerantSearch()) }
                    item {
                        UnifiedSearchSetting(
                            R.string.search_catalog_assistance,
                            preferences.onlineSearchAssistance(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedSearchSetting(label: Int, preference: tachiyomi.core.common.preference.Preference<Boolean>) {
    val checked by preference.changes().collectAsState(initial = preference.get())
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = preference::set)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(label),
            Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun UnifiedSearchCategoryRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun UnifiedSearchGenreBar(model: UnifiedSearchScreenModel) {
    val state by model.state.collectAsState()
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            UnifiedSearchGenrePill(
                selected = state.selectedGenres.isNotEmpty(),
                onClick = { model.showPanel(UnifiedSearchPanel.FILTERS) },
                label = stringResource(R.string.unified_search_filters),
                isFilter = true,
                count = state.selectedGenres.size,
            )
        }
        items(state.genres, key = { it.key }) { genre ->
            UnifiedSearchGenrePill(
                selected = genre.key in state.selectedGenres,
                onClick = { model.toggleGenre(genre.key) },
                label = genre.label,
            )
        }
    }
}

@Composable
private fun UnifiedSearchGenrePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    isFilter: Boolean = false,
    count: Int = 0,
) {
    val dark = LocalDarkTheme.current
    val motion = appMotionEnabled()
    val accent = MaterialTheme.colorScheme.primary
    val base = if (dark) Color(0xFF29181C) else Color(0xFFFFF9F2)
    val color by animateColorAsState(
        targetValue = if (selected) {
            accent.copy(alpha = if (dark) 0.24f else 0.12f).compositeOver(base)
        } else {
            base
        },
        animationSpec = tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
        label = "unifiedSearchGenreSelection",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (selected) {
                accent.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            },
        ),
        modifier = Modifier.heightIn(min = 44.dp).semantics { this.selected = selected },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isFilter) Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (count > 0) Text(count.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}
