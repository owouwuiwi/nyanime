package eu.kanade.presentation.entries.details

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import eu.kanade.presentation.discovery.SourceHomeArtwork
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.PosterDestinationContent
import eu.kanade.presentation.motion.PosterDetailArtwork
import eu.kanade.presentation.motion.PosterDetailsContent
import eu.kanade.presentation.motion.animateModernContentSize
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.motion.posterDetailPreview
import eu.kanade.presentation.motion.posterForeground
import eu.kanade.presentation.theme.NyanimeFloatingSurface
import eu.kanade.presentation.theme.NyanimeGlassHeader
import eu.kanade.presentation.theme.nyanimeArtworkScrim
import eu.kanade.presentation.theme.nyanimeBackdropSource
import eu.kanade.presentation.theme.nyanimeBrandColors
import eu.kanade.presentation.theme.nyanimePinnedBackdrop
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.watch.WatchTogetherActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource as sharedStringResource
internal data class DetailAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TitleDetailsScreen(
    entryId: Long,
    manga: Boolean,
    title: String,
    kicker: String,
    subtitle: String,
    artwork: Any?,
    parts: List<DetailPart>,
    currentId: Long?,
    primaryText: String,
    primaryInformation: String,
    totalCount: Int,
    completedCount: Int,
    favorite: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onLibrary: () -> Unit,
    onCover: () -> Unit,
    onFilter: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPart: (Long) -> Unit,
    follow: @Composable RowScope.() -> Unit,
    details: @Composable () -> Unit,
    actions: List<DetailAction>,
    partActions: @Composable (Set<Long>, () -> Unit) -> List<DetailAction>,
    download: @Composable (Long) -> Unit,
    swipe: @Composable (DetailPart, @Composable () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    extraParts: @Composable () -> Unit = {},
    partLabel: String? = null,
    seasonColumns: Int = 1,
    seasonItem: (@Composable (DetailPart, () -> Unit) -> Unit)? = null,
    episodeSections: List<DetailEpisodeSection> = emptyList(),
    countEpisodeSections: Boolean = false,
    initialEpisodeSection: String? = null,
    onExpandEpisodeSection: (String, Boolean) -> Unit = { _, _ -> },
    onOpenEpisodeSection: (String) -> Unit = {},
    backgroundArtwork: Boolean = false,
    sourceAvailable: Boolean = true,
    hasFilters: Boolean = false,
    filterKey: Long = 0L,
    refreshing: Boolean = false,
    loading: Boolean = false,
    primaryLoading: Boolean = false,
    noticeLoading: Boolean = false,
    nextRelease: String? = null,
    onShare: (() -> Unit)? = null,
    onEditCategories: (() -> Unit)? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) = TitleDetailsPage {
    val context = LocalContext.current
    var detailsTab by rememberSaveable(entryId) { mutableStateOf(false) }
    var manager by rememberSaveable(entryId) { mutableStateOf(false) }
    var managerFilter by rememberSaveable(entryId) { mutableStateOf(DetailFilter.INDEX) }
    var managerQuery by rememberSaveable(entryId) { mutableStateOf("") }
    var initialSelection by rememberSaveable(entryId) { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf<List<DetailAction>?>(null) }
    val partsState = rememberLazyListState()
    val detailsState = rememberLazyListState()
    val listState = if (detailsTab) detailsState else partsState
    var visitedDetails by rememberSaveable(entryId) { mutableStateOf(false) }
    var searching by rememberSaveable(entryId) { mutableStateOf(false) }
    var requestSearchFocus by remember { mutableStateOf(false) }
    var query by rememberSaveable(entryId) { mutableStateOf("") }
    var indexOpen by rememberSaveable(entryId) { mutableStateOf(false) }
    var highlightedId by remember { mutableStateOf<Long?>(null) }
    var tabsHeight by remember { mutableStateOf(0) }
    var extrasHeight by remember { mutableIntStateOf(0) }
    val visibleParts = remember(parts, query) { DetailParts.search(parts, query) }
    var expandedSections by rememberSaveable(entryId) { mutableStateOf(arrayListOf<String>()) }
    var collapsedSearchSections by rememberSaveable(entryId, query) { mutableStateOf(arrayListOf<String>()) }
    var sectionsInitialized by rememberSaveable(entryId) { mutableStateOf(false) }
    LaunchedEffect(episodeSections, currentId, primaryLoading) {
        if (!sectionsInitialized && episodeSections.isNotEmpty() && !primaryLoading) {
            DetailEpisodeSections.initial(episodeSections, currentId, initialEpisodeSection)?.let { key ->
                expandedSections = arrayListOf(key)
                onExpandEpisodeSection(key, false)
            }
            sectionsInitialized = true
        }
    }
    // Restoring an expanded section must also restore its subscription after process recreation.
    LaunchedEffect(expandedSections, episodeSections.map { it.key }) {
        expandedSections.forEach { onExpandEpisodeSection(it, false) }
    }
    val sectionRows = remember(episodeSections, expandedSections, collapsedSearchSections, query) {
        DetailEpisodeSections.rows(episodeSections, expandedSections.toSet(), query, collapsedSearchSections.toSet())
    }
    val fresh = remember(parts) { parts.filter { it.fresh && !it.completed } }
    val columns = if (seasonItem == null) 1 else seasonColumns.coerceAtLeast(1)
    val density = LocalDensity.current
    val rowKeys = remember(visibleParts, sectionRows, columns, seasonItem != null, searching, detailsTab) {
        when {
            !searching || detailsTab -> emptyList()
            episodeSections.isNotEmpty() && sectionRows.isNotEmpty() -> sectionRows.map { it.key }
            visibleParts.isEmpty() -> listOf(DetailListLayout.EMPTY_ROW)
            seasonItem != null -> visibleParts.chunked(columns).map { "season:${it.first().id}" }
            else -> visibleParts.map { "part:${it.id}" }
        }
    }
    val width = LocalConfiguration.current.screenWidthDp
    val rowHeights = remember(visibleParts, columns, width, density.density, density.fontScale) {
        mutableStateMapOf<String, Int>()
    }
    val libraryIcon = if (favorite) TitleDetailsIcons.BookmarkCheck else TitleDetailsIcons.Bookmark
    val libraryLabel = stringResource(if (favorite) R.string.detail_in_library else R.string.detail_add_library)
    val freshLabel = stringResource(if (manga) R.string.detail_new_read else R.string.detail_new_watch)
    val canResume = parts.any { it.id == currentId }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val sectionAlpha = remember { Animatable(1f) }
    BackHandler(enabled = searching && !manager && !indexOpen) {
        searching = false
        query = ""
        requestSearchFocus = false
        focus.clearFocus()
        keyboard?.hide()
    }
    var tabJob by remember { mutableStateOf<Job?>(null) }
    var lastQuery by remember(entryId) { mutableStateOf(query) }
    var lastFilters by remember(entryId) { mutableStateOf(filterKey) }
    LaunchedEffect(query, filterKey) {
        if (query != lastQuery || filterKey != lastFilters) {
            lastQuery = query
            lastFilters = filterKey
            if (!detailsTab && !loading) partsState.scrollToItem(DetailListLayout.TABS)
        }
    }
    LaunchedEffect(highlightedId) {
        if (highlightedId != null) {
            delay(1100)
            highlightedId = null
        }
    }
    val backdrop = remember { HazeState() }
    val enabled = !loading
    val motion = modernMotionEnabled()
    val plural = partLabel ?: stringResource(if (manga) R.string.detail_chapters else R.string.detail_episodes)
    val sectionModifier = Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(horizontal = 20.dp)
        .graphicsLayer { alpha = sectionAlpha.value }
    val changeTab: (Boolean) -> Unit = { target ->
        if (target != detailsTab) {
            tabJob?.cancel()
            tabJob = scope.launch {
                focus.clearFocus()
                keyboard?.hide()
                if (motion) sectionAlpha.animateTo(0f, tween(90)) else sectionAlpha.snapTo(1f)
                val targetState = if (target) detailsState else partsState
                // The other tab may not have a layout yet. Schedule its position; waiting for
                // scrollToItem here would prevent that tab from ever becoming active.
                if (listState.firstVisibleItemIndex < DetailListLayout.TABS) {
                    targetState.requestScrollToItem(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                    )
                } else if (target && !visitedDetails) {
                    targetState.requestScrollToItem(DetailListLayout.TABS)
                }
                if (target) visitedDetails = true
                detailsTab = target
                if (motion) {
                    withFrameNanos { }
                    sectionAlpha.animateTo(1f, tween(ModernMotion.EXIT_MILLIS))
                }
            }
        }
    }
    val jumpTo: (Long) -> Unit = { id ->
        scope.launch {
            val section = episodeSections.firstOrNull { it.parts.any { part -> part.id == id } }
            if (section != null && section.key !in expandedSections) {
                expandedSections = ArrayList(expandedSections + section.key)
                onExpandEpisodeSection(section.key, false)
                withFrameNanos { }
            }
            if (section != null) collapsedSearchSections = ArrayList(collapsedSearchSections - section.key)
            val destination = if (episodeSections.isEmpty()) {
                DetailListLayout.rowIndex(visibleParts, id, columns)
            } else {
                DetailEpisodeSections.index(
                    DetailEpisodeSections.rows(
                        episodeSections,
                        expandedSections.toSet(),
                        query,
                        collapsedSearchSections.toSet(),
                    ),
                    id,
                )
            }
            if (destination != null) {
                focus.clearFocus()
                keyboard?.hide()
                if (motion) {
                    partsState.animateScrollToItem(
                        destination,
                        -tabsHeight,
                    )
                } else {
                    partsState.scrollToItem(destination, -tabsHeight)
                }
                highlightedId = id
            }
        }
    }
    Scaffold(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        containerColor = Color.Transparent,
        topBar = {
            NyanimeGlassHeader(backdrop) {
                DetailTopBar(title, manga, onBack, onShare.takeIf { enabled }, { menu = actions }, enabled)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Keyboard space belongs to the content, not to a shrinking list viewport.
        // The footer and padding change together, preserving the current scroll anchor.
        val keyboardPadding = (
            with(density) { WindowInsets.ime.getBottom(this).toDp() } - padding.calculateBottomPadding()
            ).coerceAtLeast(0.dp)
        val bottomPadding = 24.dp + keyboardPadding
        PullRefresh(
            refreshing = refreshing,
            onRefresh = onRefresh,
            enabled = enabled && !manager,
            indicatorOnGestureOnly = true,
            indicatorPadding = padding,
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().nyanimeBackdropSource(backdrop)
                    .padding(padding).consumeWindowInsets(padding),
            ) {
                // Use the same measure constraints as the list. An onSizeChanged state update
                // arrives a frame late and lets a growing viewport clamp the saved position.
                val fillHeight = DetailListLayout.viewportFillHeight(
                    constraints.maxHeight,
                    tabsHeight,
                    extrasHeight,
                    with(density) { bottomPadding.roundToPx() },
                    with(density) { DetailRowMinHeight.roundToPx() },
                    rowKeys,
                    rowHeights,
                ).takeIf { searching && !detailsTab } ?: 0
                val rowModifier: (String) -> Modifier = { key ->
                    sectionModifier.heightIn(min = DetailRowMinHeight).let { row ->
                        if (searching && fillHeight > 0) {
                            row.onSizeChanged { rowHeights[key] = it.height }
                        } else {
                            row
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    detailsPrefix { section ->
                        when (section) {
                            DetailListSection.HERO -> {
                                DetailHero(title, kicker, subtitle, artwork, backgroundArtwork, onCover, loading)
                            }
                            DetailListSection.PROGRESS -> {
                                Column(Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                                    Spacer(Modifier.height(20.dp))
                                    DetailProgress(manga, completedCount, totalCount, loading)
                                    Spacer(Modifier.height(15.dp))
                                    DetailPrimary(
                                        primaryText,
                                        primaryInformation,
                                        manga,
                                        enabled && currentId != null && !primaryLoading,
                                        onContinue,
                                        loading || primaryLoading,
                                    )
                                    Spacer(Modifier.height(9.dp))
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        DetailQuickAction(
                                            libraryLabel,

                                            libraryIcon,
                                            onLibrary,
                                            onEditCategories,
                                            active = favorite,
                                            enabled = enabled,
                                        )
                                        follow()
                                        DetailQuickAction(
                                            stringResource(R.string.detail_together),
                                            TitleDetailsIcons.Users,
                                            {
                                                context.startActivity(
                                                    Intent(context, WatchTogetherActivity::class.java),
                                                )
                                            },
                                            enabled = enabled,
                                        )
                                    }
                                    Spacer(Modifier.height(20.dp))
                                }
                            }
                            DetailListSection.NOTICE -> {
                                // Optional data never inserts a speculative skeleton that later collapses.
                                // Its own lazy key keeps the item under the user's finger anchored.
                                Column(
                                    Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(horizontal = 20.dp)
                                        .animateModernContentSize(),
                                ) {
                                    if (!loading && !noticeLoading) {
                                        when {
                                            !sourceAvailable -> DetailNotice(
                                                stringResource(R.string.detail_no_source),
                                                subtitle,
                                                TitleDetailsIcons.Info,
                                            )
                                            fresh.isNotEmpty() -> DetailNotice(
                                                if (fresh.size == 1) {
                                                    freshLabel
                                                } else {
                                                    stringResource(R.string.detail_new_count, fresh.size)
                                                },
                                                if (fresh.size == 1) {
                                                    stringResource(
                                                        if (manga) {
                                                            R.string.detail_available_chapter
                                                        } else {
                                                            R.string.detail_available_episode
                                                        },
                                                        fresh.single().number,
                                                    )
                                                } else {
                                                    stringResource(R.string.detail_available)
                                                },
                                                TitleDetailsIcons.Sparkles,
                                                onClick = {
                                                    managerFilter = DetailFilter.NEW
                                                    managerQuery = ""
                                                    manager = true
                                                },
                                            )
                                            nextRelease != null -> DetailNotice(
                                                stringResource(R.string.detail_next_release),
                                                nextRelease,
                                                TitleDetailsIcons.Clock,
                                            )
                                        }
                                        if (!sourceAvailable || fresh.isNotEmpty() || nextRelease != null) {
                                            Spacer(Modifier.height(20.dp))
                                        }
                                    }
                                }
                            }
                            DetailListSection.TABS -> {
                                Column(
                                    Modifier.widthIn(max = 700.dp).fillMaxWidth()
                                        .detailPinnedSurface(listState, maxWidth, padding.calculateTopPadding())
                                        .padding(horizontal = 20.dp)
                                        .onSizeChanged { tabsHeight = it.height },
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        DetailTab(plural, !detailsTab) { changeTab(false) }
                                        Spacer(Modifier.width(24.dp))
                                        DetailTab(
                                            stringResource(R.string.detail_details),
                                            detailsTab,
                                        ) { changeTab(true) }
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = {
                                            managerFilter = DetailFilter.INDEX
                                            managerQuery = query
                                            manager = true
                                        }, enabled = enabled) {
                                            Text(stringResource(R.string.detail_manage), fontSize = 12.sp)
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    if (!detailsTab) {
                                        Box(
                                            Modifier.fillMaxWidth().padding(top = 12.dp)
                                                .graphicsLayer { alpha = sectionAlpha.value },
                                        ) {
                                            DetailLoadingSlot(loading, placeholder = {
                                                Column(
                                                    Modifier.matchParentSize(),
                                                    verticalArrangement = Arrangement.spacedBy(18.dp),
                                                ) {
                                                    Skeleton(Modifier.fillMaxWidth(0.6f).height(16.dp))
                                                    Skeleton(Modifier.fillMaxWidth(0.35f).height(12.dp))
                                                }
                                            }) {
                                                DetailListControls(
                                                    plural,
                                                    if (countEpisodeSections) {
                                                        sectionRows.count {
                                                            it is DetailEpisodeRow.Header
                                                        }
                                                    } else {
                                                        visibleParts.size
                                                    },
                                                    totalCount, hasFilters, query, searching,
                                                    requestFocus = requestSearchFocus,
                                                    onFocusRequested = { requestSearchFocus = false },
                                                    compact = LocalConfiguration.current.screenHeightDp < 520,
                                                    onQuery = { query = it },
                                                    onSearch = {
                                                        searching = !searching
                                                        requestSearchFocus = searching
                                                        if (!searching) {
                                                            query = ""
                                                            focus.clearFocus()
                                                            keyboard?.hide()
                                                        }
                                                    },
                                                    onFilter = onFilter,
                                                    onIndex = {
                                                        focus.clearFocus()
                                                        keyboard?.hide()
                                                        indexOpen = true
                                                    },
                                                    canResume = canResume,
                                                    onResume = { currentId?.let(jumpTo) },
                                                    enabled = enabled,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            DetailListSection.EXTRAS -> {
                                if (!loading && !detailsTab) {
                                    Box(sectionModifier.onSizeChanged { extrasHeight = it.height }) { extraParts() }
                                }
                            }
                        }
                    }
                    if (loading) {
                        items(3, key = { "loading:$it" }) {
                            Box(
                                sectionModifier.height(DetailRowMinHeight).animateItem(
                                    fadeInSpec = tween(if (motion) ModernMotion.EXIT_MILLIS else 0),
                                    fadeOutSpec = tween(if (motion) ModernMotion.EXIT_MILLIS else 0),
                                    placementSpec = null,
                                ).padding(vertical = 8.dp),
                            ) {
                                Skeleton(Modifier.fillMaxSize())
                            }
                        }
                    } else if (detailsTab) {
                        item("details") { Box(sectionModifier.padding(top = 20.dp)) { details() } }
                    } else {
                        if (episodeSections.isNotEmpty() && sectionRows.isNotEmpty()) {
                            items(sectionRows, key = { it.key }, contentType = {
                                when (it) {
                                    is DetailEpisodeRow.Header -> "episode-section"
                                    is DetailEpisodeRow.Item -> "part"
                                    is DetailEpisodeRow.Status -> "episode-section-status"
                                }
                            }) { row ->
                                when (row) {
                                    is DetailEpisodeRow.Header -> DetailEpisodeSectionHeader(
                                        row.section,
                                        if (query.isNotBlank()) {
                                            row.section.key !in collapsedSearchSections
                                        } else {
                                            row.section.key in
                                                expandedSections
                                        },
                                        onToggle = {
                                            val key = row.section.key
                                            if (query.isNotBlank()) {
                                                collapsedSearchSections = if (key in collapsedSearchSections) {
                                                    onExpandEpisodeSection(key, false)
                                                    ArrayList(collapsedSearchSections - key)
                                                } else {
                                                    ArrayList(collapsedSearchSections + key)
                                                }
                                            } else {
                                                expandedSections = if (key in expandedSections) {
                                                    ArrayList(expandedSections - key)
                                                } else {
                                                    onExpandEpisodeSection(key, false)
                                                    ArrayList(expandedSections + key)
                                                }
                                            }
                                        },
                                        onOpen = { onOpenEpisodeSection(row.section.key) },
                                        modifier = sectionModifier.onSizeChanged { rowHeights[row.key] = it.height },
                                    )
                                    is DetailEpisodeRow.Status -> Column(
                                        rowModifier(row.key).padding(vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (row.section.loading) {
                                            Text(
                                                stringResource(R.string.episode_section_loading),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            repeat(2) { Skeleton(Modifier.fillMaxWidth().height(44.dp)) }
                                        } else if (row.section.failed || !row.section.loaded) {
                                            Text(
                                                stringResource(
                                                    if (row.section.failed) {
                                                        R.string.episode_section_failed
                                                    } else {
                                                        R.string.episode_section_not_loaded
                                                    },
                                                ),
                                            )
                                            TextButton(onClick = { onExpandEpisodeSection(row.section.key, true) }) {
                                                Text(
                                                    stringResource(
                                                        if (row.section.failed) {
                                                            R.string.episode_section_retry
                                                        } else {
                                                            R.string.episode_section_load
                                                        },
                                                    ),
                                                )
                                            }
                                        } else {
                                            Text(
                                                stringResource(R.string.episode_section_empty),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    is DetailEpisodeRow.Item -> DetailListRowFrame(
                                        row.part.id == highlightedId,
                                        rowModifier(row.key).animateItem(
                                            fadeInSpec = tween(if (motion) ModernMotion.EXIT_MILLIS else 0),
                                            fadeOutSpec = tween(if (motion) ModernMotion.EXIT_MILLIS else 0),
                                            placementSpec = null,
                                        ),
                                    ) {
                                        swipe(row.part) {
                                            DetailPartRow(
                                                row.part,
                                                row.part.id == currentId,
                                                false,
                                                false,
                                                onClick = { onOpenPart(row.part.id) },
                                                onLongClick = {
                                                    focus.clearFocus()
                                                    keyboard?.hide()
                                                    initialSelection = row.part.id
                                                    managerFilter = DetailFilter.INDEX
                                                    managerQuery = query
                                                    manager = true
                                                },
                                                trailing = { download(row.part.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (visibleParts.isEmpty()) {
                            item(DetailListLayout.EMPTY_ROW) {
                                Column(rowModifier(DetailListLayout.EMPTY_ROW).padding(vertical = 24.dp)) {
                                    Text(
                                        stringResource(
                                            if (parts.isEmpty()) {
                                                R.string.detail_empty_parts
                                            } else {
                                                R.string.detail_empty
                                            },
                                        ),
                                    )
                                    if (query.isNotBlank()) {
                                        Text(stringResource(R.string.detail_empty_help), fontSize = 12.sp)
                                    }
                                }
                            }
                        } else if (seasonItem != null) {
                            items(visibleParts.chunked(columns), key = {
                                "season:${it.first().id}"
                            }, contentType = { "season" }) { row ->
                                DetailListRowFrame(
                                    row.any {
                                        it.id == highlightedId
                                    },
                                    rowModifier("season:${row.first().id}"),
                                ) {
                                    DetailSeasonRows(row, columns, seasonItem)
                                }
                            }
                        } else {
                            items(visibleParts, key = { "part:${it.id}" }, contentType = { "part" }) { part ->
                                DetailListRowFrame(
                                    part.id == highlightedId,
                                    rowModifier("part:${part.id}").animateItem(
                                        fadeInSpec = tween(if (motion) ModernMotion.EXIT_MILLIS else 0),
                                        fadeOutSpec = tween(if (motion) ModernMotion.EXIT_MILLIS else 0),
                                        placementSpec = null,
                                    ),
                                ) {
                                    swipe(part) {
                                        DetailPartRow(
                                            part,
                                            part.id == currentId,
                                            false,
                                            false,
                                            onClick = { onOpenPart(part.id) },
                                            onLongClick = {
                                                focus.clearFocus()
                                                keyboard?.hide()
                                                initialSelection = part.id
                                                managerFilter = DetailFilter.INDEX
                                                managerQuery = query
                                                manager = true
                                            },
                                            trailing = { download(part.id) },
                                        )
                                    }
                                }
                            }
                        }
                        if (searching) {
                            item("search_viewport") { Spacer(Modifier.height(with(density) { fillHeight.toDp() })) }
                        }
                    }
                }
            }
        }
    }
    if (indexOpen) {
        if (episodeSections.isNotEmpty()) {
            ModalBottomSheet(onDismissRequest = { indexOpen = false }) {
                Text(
                    plural,
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                LazyColumn(Modifier.navigationBarsPadding()) {
                    items(episodeSections, key = { it.key }) { section ->
                        DetailEpisodeSectionHeader(
                            section,
                            section.key in expandedSections,
                            onToggle = {
                                indexOpen = false
                                expandedSections = ArrayList((expandedSections + section.key).distinct())
                                collapsedSearchSections = ArrayList(collapsedSearchSections - section.key)
                                onExpandEpisodeSection(section.key, false)
                                scope.launch {
                                    withFrameNanos { }
                                    val rows = DetailEpisodeSections.rows(
                                        episodeSections,
                                        expandedSections.toSet(),
                                        query,
                                        collapsedSearchSections.toSet(),
                                    )
                                    val index = rows.indexOfFirst {
                                        it is DetailEpisodeRow.Header &&
                                            it.section.key == section.key
                                    }
                                    if (index >=
                                        0
                                    ) {
                                        partsState.scrollToItem(DetailListLayout.FIRST_ROW + index, -tabsHeight)
                                    }
                                }
                            },
                            onOpen = {
                                indexOpen = false
                                onOpenEpisodeSection(section.key)
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        } else {
            val firstId = visibleParts.getOrNull(
                ((partsState.firstVisibleItemIndex - DetailListLayout.FIRST_ROW).coerceAtLeast(0)) * columns,
            )?.id
            DetailGroupSheet(visibleParts.size, DetailParts.groupFor(visibleParts, firstId), { group ->
                indexOpen = false
                DetailListLayout.groupTarget(visibleParts, group)?.let(jumpTo)
            }) { indexOpen = false }
        }
    }
    if (manager) {
        DetailManager(
            entryId, title, if (countEpisodeSections) stringResource(R.string.detail_episodes) else plural,
            parts, currentId, onOpenPart, onFilter, partActions, download, swipe,
            seasonColumns = seasonColumns,
            seasonItem = seasonItem,
            initialSelection = initialSelection,
            initialFilter = managerFilter,
            initialQuery = managerQuery,
            startSelecting = seasonItem == null && managerFilter == DetailFilter.INDEX,
            onDismiss = {
                manager = false
                initialSelection = null
            },
        )
    }
    menu?.let { DetailActionSheet(it) { menu = null } }
}

@Composable
private fun TitleDetailsPage(content: @Composable () -> Unit) = PosterDetailsContent {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 1.35.em)) {
            content()
        }
    }
}

/** Transparent within the page; continues its stationary backdrop when covering scrolling rows. */
@Composable
private fun Modifier.detailPinnedSurface(listState: LazyListState, viewportWidth: Dp, topInset: Dp): Modifier {
    val pinned by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex >= DetailListLayout.TABS }
    }
    val opacity = animateFloatAsState(
        if (pinned) 1f else 0f,
        tween(if (modernMotionEnabled()) ModernMotion.EXIT_MILLIS else 0),
        label = "detail_pinned_surface",
    )
    return nyanimePinnedBackdrop(viewportWidth, topInset) { opacity.value }
}

@Composable
private fun DetailTopBar(
    title: String,
    manga: Boolean,
    onBack: () -> Unit,
    onShare: (() -> Unit)?,
    onMenu: () -> Unit,
    enabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 62.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailIcon(TitleDetailsIcons.Back, stringResource(R.string.detail_back), onBack)
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(if (manga) R.string.detail_your_read else R.string.detail_your_watch),
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        DetailIcon(
            TitleDetailsIcons.Share,
            stringResource(R.string.detail_share),
            { onShare?.invoke() },
            enabled = onShare != null,
        )
        DetailIcon(TitleDetailsIcons.More, stringResource(R.string.detail_actions), onMenu, enabled)
    }
}

@Composable
private fun DetailHero(
    title: String,
    kicker: String,
    subtitle: String,
    artwork: Any?,
    background: Boolean,
    onCover: () -> Unit,
    loading: Boolean,
) = PosterDestinationContent {
    DetailHeroContent(title, kicker, subtitle, artwork, background, onCover, loading)
}

@Composable
private fun DetailHeroContent(
    title: String,
    kicker: String,
    subtitle: String,
    artwork: Any?,
    background: Boolean,
    onCover: () -> Unit,
    loading: Boolean,
) {
    val scale = LocalConfiguration.current.fontScale
    BoxWithConstraints(Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(horizontal = 12.dp)) {
        val height = (maxWidth * 0.865f).coerceIn(280.dp, 400.dp) + (40.dp * (scale - 1f).coerceIn(0f, 1f))
        Box(
            Modifier.fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(onClick = onCover),
        ) {
            PosterDetailArtwork(
                artwork,
                Modifier.matchParentSize(),
                background = background,
                sourceArtwork = true,
                shade = nyanimeArtworkScrim,
            )
            Column(
                Modifier.align(Alignment.BottomStart).posterForeground().padding(horizontal = 20.dp, vertical = 21.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(16.dp, 3.dp).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        kicker.uppercase(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                    )
                }
                Text(
                    title,
                    color = Color.White,
                    fontSize = 31.sp,
                    lineHeight = 33.sp,
                    letterSpacing = (-1.1)
                        .sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                DetailLoadingSlot(loading, placeholder = {
                    Skeleton(
                        Modifier.align(Alignment.CenterStart).fillMaxWidth(0.55f)
                            .height(with(LocalDensity.current) { 16.sp.toDp() }),
                    )
                }) {
                    Text(
                        subtitle,
                        color = Color.White,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailProgress(
    manga: Boolean,
    completed: Int,
    total: Int,
    loading: Boolean,
) {
    val fraction = if (total > 0) completed.toFloat() / total else 0f
    val progress by animateFloatAsState(
        fraction,
        tween(if (modernMotionEnabled()) ModernMotion.RESIZE_MILLIS else 0),
        label = "detail_progress",
    )
    Row(
        Modifier.fillMaxWidth().heightIn(
            min = with(LocalDensity.current) {
                15.sp.toDp()
            },
        ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DetailLoadingSlot(loading, placeholder = { Skeleton(Modifier.width(100.dp).height(13.dp)) }) {
            Text(
                stringResource(
                    if (manga) R.string.detail_progress_read else R.string.detail_progress_watch,
                    completed,
                    total,
                ),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        DetailLoadingSlot(loading, placeholder = { Skeleton(Modifier.width(24.dp).height(13.dp)) }) {
            Text(
                "${(fraction * 100).toInt()}%",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier.fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        Spacer(
            Modifier.fillMaxWidth(
                progress.coerceIn(
                    0f,
                    1f,
                ),
            )
                .height(3.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun DetailPrimary(
    label: String,
    information: String,
    manga: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    loading: Boolean,
) {
    val brand = nyanimeBrandColors
    val density = LocalDensity.current
    val reservedHeight = maxOf(57.dp, 18.dp + with(density) { 18.9.sp.toDp() + 14.85.sp.toDp() })
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(15.dp),
        color = brand.actionBackground,
        contentColor = brand.actionText,
        modifier = Modifier.fillMaxWidth().heightIn(min = reservedHeight),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(if (manga) TitleDetailsIcons.Book else TitleDetailsIcons.Play, null, Modifier.size(21.dp))
            Column(Modifier.weight(1f)) {
                DetailLoadingSlot(loading, placeholder = { Skeleton(Modifier.fillMaxWidth(0.7f).height(17.dp)) }) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Bold,
                        minLines = 1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DetailLoadingSlot(loading, placeholder = {}) {
                    Text(
                        information,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        minLines = 1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = LocalContentColor.current.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun RowScope.DetailQuickAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    Column(
        Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(
                vertical = 10.dp,
                horizontal = 3.dp,
            ),

        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            null,
            Modifier.size(21.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
        Text(label, fontSize = 11.sp, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun DetailNotice(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
) {
    NyanimeFloatingSurface(
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) {
                TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.detail_view), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.heightIn(min = 48.dp)
            .combinedClickable(onClick = onClick),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            Modifier.padding(
                top = 15.dp,
                bottom = 12.dp,
            ),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.width(44.dp)
                .height(3.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

@Composable
private fun DetailPartRow(
    part: DetailPart,
    current: Boolean,
    selection: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = DetailRowMinHeight)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(vertical = 10.dp),

            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.widthIn(min = 42.dp).height(49.dp)
                    .background(
                        if (current) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    selection -> Icon(
                        if (selected) TitleDetailsIcons.SquareCheck else TitleDetailsIcons.Square,
                        null,
                        Modifier.size(20.dp),
                    )
                    part.completed -> Icon(
                        TitleDetailsIcons.Check,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(part.number, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                part.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                    SourceHomeArtwork(preview, Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp)))
                }
                part.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(
                        summary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (part.fresh && !part.completed) {
                    Text(
                        stringResource(R.string.detail_new),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    part.title,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        part.information,
                        modifier = Modifier.weight(1f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    part.badge?.invoke()
                }
                if (current && part.progress > 0f) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(3.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(2.dp),
                            ),
                    ) {
                        Spacer(
                            Modifier.fillMaxWidth(
                                part.progress.coerceIn(
                                    0f,
                                    1f,
                                ),
                            )
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
            if (part.bookmarked) {
                Icon(
                    TitleDetailsIcons.Bookmark,
                    stringResource(R.string.detail_bookmark),
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (!selection) trailing()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DetailManager(
    entryId: Long,
    title: String,
    plural: String,
    parts: List<DetailPart>,
    currentId: Long?,
    onOpen: (Long) -> Unit,
    onFilter: () -> Unit,
    actions: @Composable (Set<Long>, () -> Unit) -> List<DetailAction>,
    download: @Composable (Long) -> Unit,
    swipe: @Composable (DetailPart, @Composable () -> Unit) -> Unit,
    seasonColumns: Int,
    seasonItem: (@Composable (DetailPart, () -> Unit) -> Unit)?,
    initialSelection: Long?,
    initialFilter: DetailFilter,
    initialQuery: String,
    startSelecting: Boolean,
    onDismiss: () -> Unit,
) {
    var filter by rememberSaveable(entryId) { mutableStateOf(initialFilter) }
    var group by rememberSaveable(entryId) {
        mutableStateOf(DetailParts.groupFor(parts, initialSelection ?: currentId))
    }
    var query by rememberSaveable(entryId) { mutableStateOf(initialQuery) }
    var selecting by rememberSaveable(entryId) { mutableStateOf(startSelecting || initialSelection != null) }
    var selection by rememberSaveable(entryId) {
        mutableStateOf(initialSelection?.let { longArrayOf(it) } ?: longArrayOf())
    }
    var anchor by rememberSaveable(entryId) { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf<List<DetailAction>?>(null) }
    var groups by remember { mutableStateOf(false) }
    val selected = selection.toSet()
    val visible = remember(
        parts,
        filter,
        query,
        group,
        currentId,
    ) {
        DetailParts.visible(
            parts,
            filter,
            query,
            group,
            currentId,
        )
    }
    val clear = {
        selection = longArrayOf()
        selecting = false
        anchor = null
    }
    LaunchedEffect(parts.map { it.id }) {
        selection = selection.filter { id -> parts.any { it.id == id } }.toLongArray()
        group = group.coerceIn(0, DetailParts.groupCount(parts.size) - 1)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = null,
    ) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        BackHandler(enabled = selecting) { clear() }
        val compact = LocalConfiguration.current.screenHeightDp < 520
        Column(
            Modifier.fillMaxWidth()
                .fillMaxSize(0.92f)
                .imePadding()
                .navigationBarsPadding()
                .padding(
                    horizontal = 20.dp,
                    vertical = 14.dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plural, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                DetailIcon(TitleDetailsIcons.Filter, stringResource(R.string.detail_manage), onFilter)
                DetailIcon(TitleDetailsIcons.Close, stringResource(android.R.string.cancel), onDismiss)
            }
            val controls: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${parts.count { it.offline }} ${stringResource(R.string.detail_offline)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(13.dp),
                        )
                        .padding(
                            horizontal = 13.dp,
                            vertical = 12.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        TitleDetailsIcons.Search,
                        null,
                        Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(color = LocalContentColor.current, fontSize = 14.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        decorationBox = { field ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        stringResource(R.string.detail_number_search),
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                field()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        DetailIcon(
                            TitleDetailsIcons.Close,
                            stringResource(android.R.string.cancel),
                            { query = "" },
                        )
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DetailFilter.entries.forEach { value ->
                        val label = stringResource(
                            when (value) {
                                DetailFilter.CONTINUE -> R.string.detail_from_here
                                DetailFilter.NEW -> R.string.detail_new
                                DetailFilter.INDEX -> R.string.detail_index
                                DetailFilter.OFFLINE -> R.string.detail_offline
                                DetailFilter.BOOKMARKS -> R.string.detail_bookmarks
                            },
                        )
                        Surface(
                            onClick = {
                                filter = value
                                query = ""
                                focus.clearFocus()
                            },
                            shape = RoundedCornerShape(30.dp),
                            color =
                            if (filter == value) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ) {
                            Text(
                                label,
                                Modifier.padding(
                                    horizontal = 11.dp,
                                    vertical = 10.dp,
                                ),
                                fontSize = 11.sp,
                                color =
                                if (filter == value) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (filter == DetailFilter.INDEX && query.isBlank()) {
                        TextButton(onClick = { groups = true }, modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    R.string.detail_range,
                                    if (parts.isEmpty()) 0 else group * DetailParts.GROUP_SIZE + 1,
                                    minOf(
                                        parts.size,
                                        (group + 1) * DetailParts.GROUP_SIZE,
                                    ),
                                    parts.size,
                                ),
                                fontSize = 12.sp,
                            )
                        }
                    } else {
                        Text(
                            pluralStringResource(
                                R.plurals.detail_entries,
                                visible.size,
                                visible.size,
                                parts.size,
                            ),
                            Modifier.weight(1f),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (seasonItem == null) {
                        TextButton(onClick = {
                            focus.clearFocus()
                            keyboard?.hide()
                            if (selecting) clear() else selecting = true
                        }) {
                            Text(
                                stringResource(if (selecting) R.string.detail_done else R.string.detail_select),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                if (selecting) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { selection = parts.map { it.id }.toLongArray() }) {
                            Text(stringResource(R.string.detail_select_all), fontSize = 11.sp)
                        }
                        TextButton(onClick = {
                            selection = parts.filterNot { it.id in selected }.map { it.id }.toLongArray()
                        }) {
                            Text(stringResource(R.string.detail_invert_selection), fontSize = 11.sp)
                        }
                    }
                }
            }
            if (!compact) controls()
            val rowsState = rememberLazyListState()
            LaunchedEffect(filter, query, group) { rowsState.scrollToItem(0) }
            LazyColumn(Modifier.weight(1f), state = rowsState) {
                if (compact) item("controls") { Column { controls() } }
                if (visible.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                TitleDetailsIcons.Book,
                                null,
                                Modifier.size(30.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.detail_empty),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.detail_empty_help),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (seasonItem != null) {
                    items(visible.chunked(seasonColumns), key = { it.first().id }) { row ->
                        DetailSeasonRows(row, seasonColumns, seasonItem, onNavigate = onDismiss)
                    }
                } else {
                    items(visible, key = { it.id }) { part ->
                        val oneActions = actions(setOf(part.id), {})
                        val content: @Composable () -> Unit = {
                            DetailPartRow(
                                part,
                                part.id == currentId,
                                selecting,
                                part.id in selected,
                                onClick = {
                                    if (selecting) {
                                        focus.clearFocus()
                                        keyboard?.hide()
                                        val updated = if (part.id in selected) {
                                            selected - part.id
                                        } else {
                                            selected + part.id
                                        }
                                        selection = updated.toLongArray()
                                        anchor = part.id
                                    } else {
                                        onDismiss()
                                        onOpen(part.id)
                                    }
                                },
                                onLongClick = {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    selecting = true
                                    selection = (selected + DetailParts.range(visible, anchor, part.id)).toLongArray()
                                    anchor = part.id
                                },
                                trailing = {
                                    Row {
                                        download(part.id)
                                        DetailIcon(TitleDetailsIcons.More, stringResource(R.string.detail_actions), {
                                            menu = listOf(
                                                DetailAction(part.title, TitleDetailsIcons.Play) {
                                                    onDismiss()
                                                    onOpen(part.id)
                                                },
                                            ) +
                                                oneActions
                                        })
                                    }
                                },
                            )
                        }
                        if (selecting) content() else swipe(part, content)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (selecting && !WindowInsets.isImeVisible) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.detail_selected, selected.size, selected.size),
                        Modifier.weight(1f),
                        fontSize = 12.sp,
                    )
                    TextButton(onClick = {
                        selection = DetailParts.selectVisible(
                            selected,
                            visible,
                        )
                            .toLongArray()
                    }) {
                        Text(
                            stringResource(R.string.detail_select_visible),
                            fontSize = 11.sp,
                        )
                    }
                }
                val bulk = actions(selected, clear)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    bulk.take(3).forEach { action ->
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .combinedClickable(onClick = action.onClick)
                                .padding(
                                    horizontal = 4.dp,
                                    vertical = 12.dp,
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(action.icon, null, Modifier.size(19.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                action.label,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                    if (bulk.size > 3) {
                        DetailIcon(
                            TitleDetailsIcons.More,
                            stringResource(R.string.detail_actions),
                            { menu = bulk.drop(3) },
                        )
                    }
                }
            } else if (!selecting && !WindowInsets.isImeVisible && filter == DetailFilter.INDEX && query.isBlank()) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DetailIcon(
                        TitleDetailsIcons.Back,
                        stringResource(R.string.detail_previous),
                        { group-- },
                        enabled = group > 0,
                    )
                    Text(
                        stringResource(
                            R.string.detail_range,
                            if (parts.isEmpty()) 0 else group * DetailParts.GROUP_SIZE + 1,
                            minOf(
                                parts.size,
                                (group + 1) * DetailParts.GROUP_SIZE,
                            ),
                            parts.size,
                        ),
                        fontSize = 12.sp,
                    )
                    DetailIcon(
                        TitleDetailsIcons.Next,
                        stringResource(R.string.detail_next),
                        { group++ },
                        enabled = group + 1 < DetailParts.groupCount(parts.size),
                    )
                }
            } else if (!selecting && !WindowInsets.isImeVisible) {
                TextButton(onClick = {
                    filter = DetailFilter.INDEX
                    query = ""
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.detail_index)) }
            }
        }
    }
    if (groups) {
        DetailGroupSheet(parts.size, group, {
            group = it
            groups = false
        }) { groups = false }
    }
    menu?.let { DetailActionSheet(it) { menu = null } }
}

@Composable
internal fun DetailIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            icon,
            label,
            Modifier.size(21.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailActionSheet(
    actions: List<DetailAction>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(
            topStart = 25.dp,
            topEnd = 25.dp,
        ),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.88f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.detail_actions),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onDismiss) {
                    Icon(TitleDetailsIcons.Close, sharedStringResource(MR.strings.action_close))
                }
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
                overscrollEffect = null,
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 24.dp,
                ),
            ) {
                items(actions) { action ->
                    Surface(onClick = {
                        onDismiss()
                        action.onClick()
                    }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(action.icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(action.label, Modifier.padding(start = 14.dp), fontSize = 15.sp)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailGroupSheet(
    size: Int,
    current: Int,
    onGroup: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Text(
            stringResource(R.string.detail_group),
            Modifier.padding(
                horizontal = 20.dp,
                vertical = 10.dp,
            ),
            fontWeight = FontWeight.Bold,
        )
        LazyColumn(
            Modifier.heightIn(max = 420.dp),
            contentPadding = PaddingValues(
                horizontal = 20.dp,
                vertical = 12.dp,
            ),
        ) {
            items(DetailParts.groupCount(size)) { group ->
                TextButton(onClick = { onGroup(group) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            R.string.detail_range,
                            if (size == 0) 0 else group * DetailParts.GROUP_SIZE + 1,
                            minOf(
                                size,
                                (group + 1) * DetailParts.GROUP_SIZE,
                            ),
                            size,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (group == current) Icon(TitleDetailsIcons.Check, null, Modifier.size(19.dp))
                }
            }
        }
    }
}

@Composable
private fun Skeleton(
    modifier: Modifier,
    radius: Int = 8,
) {
    // A stationary placeholder avoids continuous GPU work and respects reduced motion.
    Spacer(
        modifier.background(
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
            RoundedCornerShape(radius.dp),
        ),
    )
}

/** Both layers share one measured slot. Read opacity in drawing, not per-frame composition. */
@Composable
private fun DetailLoadingSlot(
    loading: Boolean,
    placeholder: @Composable BoxScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val alpha = animateFloatAsState(
        if (loading) 0f else 1f,
        tween(if (modernMotionEnabled()) ModernMotion.EXIT_MILLIS else 0),
        label = "detail_loading_slot",
    )
    Box {
        Box(
            Modifier.graphicsLayer { this.alpha = alpha.value }
                .then(if (loading) Modifier.clearAndSetSemantics {} else Modifier),
        ) { content() }
        Box(Modifier.matchParentSize().graphicsLayer { this.alpha = 1f - alpha.value }, content = placeholder)
    }
}

@Composable
internal fun TitleDetailsLoadingScreen(
    manga: Boolean,
    onBack: () -> Unit,
) {
    val preview = posterDetailPreview()
    TitleDetailsScreen(
        entryId = -1, manga = manga, title = preview?.title.orEmpty(),
        kicker = stringResource(if (manga) R.string.detail_chapters else R.string.detail_episodes),
        subtitle = "", artwork = null, parts = emptyList(), currentId = null, primaryText = "", primaryInformation = "",
        totalCount = 0, completedCount = 0, favorite = false, loading = true,
        onBack = onBack, onContinue = {}, onLibrary = {}, onCover = {}, onFilter = {}, onRefresh = {}, onOpenPart = {},
        follow = {
            DetailQuickAction(
                stringResource(R.string.detail_follow),
                TitleDetailsIcons.Bell,
                {},
                enabled = false,
            )
        },

        details = {},
        actions = emptyList(),
        partActions = {
                _,
                _,
            ->
            emptyList()
        },
        download = {},
        swipe = {
                _,
                content,
            ->
            content()
        },

    )
}

@Composable
private fun DetailSeasonRows(
    parts: List<DetailPart>,
    columns: Int,
    item: @Composable (DetailPart, () -> Unit) -> Unit,
    onNavigate: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { part -> Box(Modifier.weight(1f)) { item(part, onNavigate) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
