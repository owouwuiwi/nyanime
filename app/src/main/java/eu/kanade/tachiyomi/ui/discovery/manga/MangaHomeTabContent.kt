package eu.kanade.tachiyomi.ui.discovery.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.discovery.HomeLoadingTransition
import eu.kanade.presentation.discovery.HomeMangaLoadingSkeleton
import eu.kanade.presentation.discovery.manga.MangaHomeContent
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.discovery.DiscoveryHomePage
import eu.kanade.tachiyomi.ui.discovery.DiscoveryTab
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.AcknowledgeUpdateNoticeWhenVisible
import eu.kanade.tachiyomi.ui.updates.MangaUpdatesScreen
import eu.kanade.tachiyomi.ui.updates.hasNewLibraryUpdateNotice
import eu.kanade.tachiyomi.ui.updates.inboxKey
import eu.kanade.tachiyomi.ui.updates.markLibraryUpdateNoticesSeen
import kotlinx.coroutines.flow.collectLatest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun MangaHomeTabContent(
    page: DiscoveryHomePage.Manga,
    active: Boolean,
    onCycleCategory: () -> Unit,
) {
    val model = page.model
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val homeKey = state.selected?.key.orEmpty()
    val seenNotices = remember { Injekt.get<UiPreferences>().lastSeenMangaUpdateNotice() }
    val lastSeenAt by seenNotices.changes().collectAsState(initial = seenNotices.get())
    val autoAcknowledge = remember { Injekt.get<UiPreferences>().autoAcknowledgeHomeUpdates() }
    val acknowledgeOnScroll by autoAcknowledge.changes().collectAsState(initial = autoAcknowledge.get())
    val listState = rememberSaveable(homeKey, saver = LazyListState.Saver) { LazyListState() }
    DiscoveryTab.HandleHomeReselect(listState, onCycleCategory, active)
    val updateKeys = state.updates.map { it.inboxKey() }.toSet()
    val hasNewUpdates = hasNewLibraryUpdateNotice(updateKeys, lastSeenAt)
    val updatesIndex =
        (if (state.selected != null && !state.offline) 1 else 0) +
            (if (state.homes.size > 1) 1 else 0) +
            (if (state.history.isNotEmpty()) 1 else 0) +
            (if (!state.initializing && state.homes.isEmpty()) 1 else 0)
    DiscoveryTab.HandleHomeUpdateRequest(listState, page.key, updatesIndex, active)
    AcknowledgeUpdateNoticeWhenVisible(
        listState,
        "personal-updates",
        updateKeys,
        active && hasNewUpdates && acknowledgeOnScroll,
        seenNotices,
        eu.kanade.presentation.discovery.LocalHomeContentInset.current,
    )
    val openUpdates: () -> Unit = {
        markLibraryUpdateNoticesSeen(seenNotices, updateKeys)
        navigator.push(MangaUpdatesScreen)
    }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(model, active) { onDispose { model.cancelOpening() } }
    LaunchedEffect(model, active) {
        if (!active) return@LaunchedEffect
        model.events.collectLatest { event ->
            when (event) {
                is MangaHomeScreenModel.Event.Read ->
                    context.startActivity(ReaderActivity.newIntent(context, event.mangaId, event.chapterId))
                is MangaHomeScreenModel.Event.Details -> navigator.push(MangaScreen(event.mangaId))
                is MangaHomeScreenModel.Event.Error -> snackbar.showSnackbar(event.message)
            }
        }
    }
    LaunchedEffect(state.initializing) {
        if (!state.initializing) (context as? MainActivity)?.ready = true
    }
    Box(Modifier.fillMaxSize()) {
        HomeLoadingTransition(
            loading = state.initializing,
            placeholder = { HomeMangaLoadingSkeleton() },
        ) {
            MangaHomeContent(
                state = state,
                onRefresh = model::refresh,
                onSelectHome = model::selectHome,
                onSelectAll = model::selectAll,
                onPreferredSource = model::preferSource,
                onManga = { navigator.push(MangaScreen(it.id, fromSource = true)) },
                onChapter = model::openChapter,
                onResume = model::resume,
                onArchive = { filters ->
                    state.selected?.let { navigator.push(BrowseMangaSourceScreen(it.id, "", filters)) }
                },
                onExplore = { navigator.push(MangaHomeSearchScreen(homeId = page.homeId)) },
                onGenre = { genre -> navigator.push(MangaHomeSearchScreen(genre, page.homeId)) },
                onMore = { section ->
                    val filters = section.moreSelections
                    if (filters != null) {
                        state.selected?.let { navigator.push(BrowseMangaSourceScreen(it.id, "", filters)) }
                    } else {
                        model.loadSection(section.id, next = true)
                    }
                },
                onRetry = { model.loadSection(it) },
                onUpdates = openUpdates,
                onUpdate = { update ->
                    model.dismissUpdate(update)
                    context.startActivity(ReaderActivity.newIntent(context, update.mangaId, update.chapterId))
                },
                listState = listState,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
