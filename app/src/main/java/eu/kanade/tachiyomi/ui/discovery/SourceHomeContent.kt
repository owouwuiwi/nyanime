package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.discovery.ContinueWatchingRow
import eu.kanade.presentation.discovery.HomeLoadingSkeleton
import eu.kanade.presentation.discovery.HomeLoadingTransition
import eu.kanade.presentation.discovery.HomePosterRowSkeleton
import eu.kanade.presentation.discovery.HomeSelectionChip
import eu.kanade.presentation.discovery.LoadNotice
import eu.kanade.presentation.discovery.LocalAnimeRow
import eu.kanade.presentation.discovery.PanoramaHeroSkeleton
import eu.kanade.presentation.discovery.SectionHeader
import eu.kanade.presentation.discovery.SourceFeaturedCarousel
import eu.kanade.presentation.discovery.SourceFeaturedSection
import eu.kanade.presentation.discovery.SourceHomeChoiceDialog
import eu.kanade.presentation.discovery.SourceHomeDateSelector
import eu.kanade.presentation.discovery.SourceHomePosterCard
import eu.kanade.presentation.discovery.SourceHomeRankingCard
import eu.kanade.presentation.discovery.awaitingContent
import eu.kanade.presentation.discovery.panoramaAutoplay
import eu.kanade.presentation.privacy.nsfwSourcePrivacy
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.data.discovery.SourceHomeSourceChoice
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.privacy.PrivacyMedia
import eu.kanade.tachiyomi.ui.updates.AcknowledgeUpdateNoticeWhenVisible
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import eu.kanade.tachiyomi.ui.updates.dismissLibraryUpdate
import eu.kanade.tachiyomi.ui.updates.hasNewLibraryUpdateNotice
import eu.kanade.tachiyomi.ui.updates.markLibraryUpdateNoticesSeen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.discovery.SourceHomeGroup
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.homeItemKey
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.presentation.core.components.material.PullRefresh
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun DiscoveryTab.SourceHomeContent(
    page: DiscoveryHomePage.Source,
    active: Boolean,
    onCycleCategory: () -> Unit,
) {
    val state by page.model.state.collectAsStateWithLifecycle()
    HomeLoadingTransition(
        loading = state.access.loading,
        modifier = Modifier.fillMaxSize(),
        placeholder = { HomeLoadingSkeleton(withSourceAction = true) },
    ) {
        SourceHomeReadyContent(page, state, active, onCycleCategory)
    }
}

@Composable
private fun DiscoveryTab.SourceHomeReadyContent(
    page: DiscoveryHomePage.Source,
    state: SourceHomeScreenModel.State,
    active: Boolean,
    onCycleCategory: () -> Unit,
) {
    val homeKey = page.homeKey
    val model = page.model
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val dismissedUpdates = remember { Injekt.get<UiPreferences>().dismissedLibraryUpdates() }
    val seenNotices = remember { Injekt.get<UiPreferences>().lastSeenAnimeUpdateNotice() }
    val lastSeenAt by seenNotices.changes().collectAsState(initial = seenNotices.get())
    val autoAcknowledge = remember { Injekt.get<UiPreferences>().autoAcknowledgeHomeUpdates() }
    val acknowledgeOnScroll by autoAcknowledge.changes().collectAsState(initial = autoAcknowledge.get())
    val updateKeys = state.updates.data.orEmpty().mapNotNull { it.updateKey }.toSet()
    val scope = rememberCoroutineScope()
    val listState = rememberSaveable(homeKey, saver = LazyListState.Saver) { LazyListState() }
    LaunchedEffect(Unit) { (context as? MainActivity)?.ready = true }
    val access = state.access
    val source = access.group
    if (source == null) {
        Text(
            "Questa Home non è disponibile",
            Modifier.padding(top = eu.kanade.presentation.discovery.LocalHomeContentInset.current).padding(16.dp),
        )
        return
    }
    HandleHomeReselect(listState, onCycleCategory, active)
    var pendingChoice by remember { mutableStateOf<Pair<Anime, Boolean>?>(null) }
    val openCard: (Anime, Boolean) -> Unit = { anime, playEpisode ->
        val id = SourceHomeSourceChoice.preferredAnimeId(context, anime)
        navigator.push(AnimeScreen(id, true, SourceHomeSourceChoice.episodeTarget(anime, id).takeIf { playEpisode }))
    }
    pendingChoice?.takeIf { active }?.let { (anime, playEpisode) ->
        SourceHomeChoiceDialog(
            anime,
            source,
            SourceHomeSourceChoice.preferredAnimeId(context, anime),
            { pendingChoice = null },
        ) { id, remember ->
            if (remember) SourceHomeSourceChoice.remember(context, anime, id)
            pendingChoice = null
            navigator.push(
                AnimeScreen(
                    id,
                    true,
                    SourceHomeSourceChoice.episodeTarget(anime, id).takeIf {
                        playEpisode
                    },
                ),
            )
        }
    }
    val chooseSource: (Anime, Boolean) -> Unit = { anime, play -> pendingChoice = anime to play }
    val artworkRefreshKey = state.artworkRefreshKey
    val rows = remember(source) { source.rows }
    val rotateFeatured = panoramaAutoplay(listState, "hero:" + source.id, active) && pendingChoice == null
    val modern = LocalNyanimeStyle.current
    val featuredRow = rows.firstOrNull { row ->
        row.sections.size == 1 && row.sections.first().layout == "featured" && !row.sections.first().supportsDate
    }.takeIf { modern }
    val heroSection = featuredRow?.sections?.firstOrNull()
        ?: rows.firstOrNull()?.sections?.firstOrNull()?.takeIf { modern }
    val hasNewUpdates = hasNewLibraryUpdateNotice(updateKeys, lastSeenAt)
    val updatesSectionKey = "updates:" + source.id
    val updatesIndex = (if (!access.offline && heroSection != null) 1 else 0) +
        1
    HandleHomeUpdateRequest(listState, page.key, updatesIndex, active)
    AcknowledgeUpdateNoticeWhenVisible(
        listState,
        updatesSectionKey,
        updateKeys,
        active && hasNewUpdates && acknowledgeOnScroll,
        seenNotices,
        eu.kanade.presentation.discovery.LocalHomeContentInset.current,
    )
    val openUpdatesScreen: () -> Unit = {
        markLibraryUpdateNoticesSeen(seenNotices, updateKeys)
        navigator.push(UpdatesTab)
    }
    LifecycleStartEffect(access, active) {
        if (active) model.onResume()
        onStopOrDispose { if (active) model.onPause() }
    }
    Box(Modifier.fillMaxSize()) {
        PullRefresh(
            indicatorOnGestureOnly = true,
            indicatorPadding = PaddingValues(top = eu.kanade.presentation.discovery.LocalHomeContentInset.current),
            refreshing = state.sections.values.any { it.loading },
            enabled = !access.offline,
            onRefresh = model::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.nsfwSourcePrivacy(PrivacyMedia.VIDEO, source.sourceIds),
                state = listState,
                contentPadding = PaddingValues(
                    top = eu.kanade.presentation.discovery.LocalHomeContentInset.current,
                    bottom =
                    24.dp + eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!access.offline && heroSection != null) {
                    item(key = "hero:" + source.id) {
                        LaunchedEffect(access, heroSection.id, active) { if (active) model.load(heroSection.id) }
                        val featured = state.sections[heroSection.id] ?: SectionState()
                        SourceFeaturedSection(
                            state = featured,
                            title = heroSection.title,
                            refreshKey = state.artworkRefreshKey,
                            autoplay = rotateFeatured,
                            limit = if (featuredRow == null) 8 else null,
                            onRetry = { model.load(heroSection.id, true) },
                            onOpen = { openCard(it, false) },
                            onSources = { pendingChoice = it to false },
                        )
                    }
                }
                // Local playback never depends on a successful remote feed or a featured row.
                item(key = "resume:" + source.id) {
                    SectionHeader("Continua a guardare") { navigator.push(SourceHomeHistoryScreen(homeKey)) }
                    ContinueWatchingRow(
                        state.resume,
                        onOpen = { navigator.push(AnimeScreen(it)) },
                        emptyMessage = "I titoli che guardi in questa Home compariranno qui.",
                    ) { item ->
                        scope.launch {
                            context.playDiscoveryEpisode(item.episode, item.forcedStartPositionMs)
                        }
                    }
                }
                if (state.updates.data?.isNotEmpty() == true) {
                    item(key = "updates:" + source.id) {
                        SectionHeader("Le tue novità", openUpdatesScreen)
                        LocalAnimeRow(
                            state.updates,
                            onOpen = { item ->
                                item.updateKey?.let { dismissLibraryUpdate(dismissedUpdates, it) }
                                navigator.push(AnimeScreen(item.anime.id))
                            },
                        ) { item ->
                            item.updateKey?.let { dismissLibraryUpdate(dismissedUpdates, it) }
                            scope.launch { context.playDiscoveryEpisode(item.episode) }
                        }
                    }
                }
                if (access.offline) {
                    item(key = "offline") {
                        Text("Solo download · nessuna richiesta alle fonti", Modifier.padding(horizontal = 16.dp))
                    }
                }
                if (!access.offline) {
                    items(
                        rows.filter {
                            it.id != featuredRow?.id
                        },
                        key = { it.id },
                        contentType = { "source_home_row" },
                    ) { row ->
                        SourceHomeSectionRow(
                            model,
                            homeKey,
                            source,
                            row,
                            active,
                            artworkRefreshKey,
                            openCard,
                            chooseSource,
                        )
                    }
                    if (source.sections.isEmpty()) {
                        item {
                            Text(
                                "Aggiorna l’estensione per usare le sezioni della Home",
                                Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Each row observes only its own payload; another feed or playback update keeps its cells intact. */
@Composable
private fun SourceHomeSectionRow(
    model: SourceHomeScreenModel,
    homeKey: String,
    source: SourceHomeGroup,
    row: SourceHomeGroup.Row,
    active: Boolean,
    refreshKey: Int,
    onOpen: (Anime, Boolean) -> Unit,
    onChoice: (Anime, Boolean) -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    var selection by rememberSaveable(homeKey, row.id) { mutableStateOf<String?>(null) }
    val variantStates = rememberSaveableStateHolder()
    val section = row.selected(selection)
    var date by rememberSaveable(homeKey, section.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, section.id, date, active) {
        if (active) model.load(section.id, date = date)
    }
    val sectionState = remember(model, section.id) {
        model.state.map { it.sections[section.id] ?: SectionState<SourceHomePage>() }.distinctUntilChanged()
    }
    val value by sectionState.collectAsStateWithLifecycle(
        initialValue = model.state.value.sections[section.id] ?: SectionState(),
    )
    Column {
        SectionHeader(value.data?.title?.takeIf { row.sections.size == 1 } ?: row.title) {
            navigator.push(SourceHomeListScreen(homeKey, section.id, section.title, date))
        }
        if (row.sections.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(row.sections, key = { it.id }) { variant ->
                    HomeSelectionChip(
                        variant.group?.tab ?: variant.title,
                        variant.id == section.id,
                    ) {
                        selection = variant.id
                    }
                }
            }
        }
        if (section.supportsDate) SourceHomeDateSelector(date) { date = it }
        LoadNotice(value.loading, value.error, value.stale, showLoadingIndicator = false) {
            model.load(section.id, true, date)
        }
        if (value.data?.items?.isEmpty() == true && !value.loading && value.error == null) {
            Text("Nessun titolo in questa sezione", Modifier.padding(horizontal = 16.dp))
        }
        // Each variant owns its scroll state; switching never reuses another tab's offset.
        variantStates.SaveableStateProvider(section.id) {
            HomeLoadingTransition(
                loading = value.awaitingContent,
                placeholder = {
                    if (section.layout == "featured") {
                        PanoramaHeroSkeleton()
                    } else {
                        HomePosterRowSkeleton()
                    }
                },
            ) {
                if (section.layout == "featured") {
                    SourceFeaturedCarousel(
                        value.data?.items.orEmpty(),
                        refreshKey,
                        title = "",
                        onSources = { onChoice(it, false) },
                    ) { anime ->
                        onOpen(anime, false)
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(value.data?.items.orEmpty(), key = { _, anime ->
                            anime.homeItemKey
                        }, contentType = { _, _ -> section.layout }) { index, anime ->
                            if (section.layout == "ranking") {
                                val position = index + 1
                                SourceHomeRankingCard(
                                    anime,
                                    position,
                                    source.sourceLabel(anime.source),
                                    { onOpen(anime, true) },
                                    { onChoice(anime, true) },
                                )
                            } else {
                                SourceHomePosterCard(
                                    anime,
                                    source.sourceLabel(
                                        anime.source,
                                    ),
                                    {
                                        onOpen(anime, true)
                                    },
                                    refreshKey = refreshKey,
                                    onSources = { onChoice(anime, true) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
