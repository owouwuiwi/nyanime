package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.EntryComfortableGridItem
import eu.kanade.presentation.library.components.EntryCompactGridItem
import eu.kanade.presentation.library.components.EntryListItem
import eu.kanade.presentation.library.components.LazyLibraryGrid
import eu.kanade.presentation.library.components.LibraryShelfActionsSheet
import eu.kanade.presentation.library.components.LibraryShelfItem
import eu.kanade.presentation.library.components.LibraryShelfPosition
import eu.kanade.presentation.library.components.LibraryShelfSectionHeader
import eu.kanade.presentation.library.components.UnviewedBadge
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryScreenModel
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryScreenModel
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import it.lorelore.nyanime.presentation.library.LibraryQuickFilter
import it.lorelore.nyanime.presentation.library.LibraryQuickFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.entries.manga.model.MangaCover
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus
import tachiyomi.source.local.entries.anime.isLocal
import tachiyomi.source.local.entries.manga.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
internal fun LibrariesTab.AllLibrariesContent() {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animeModel = rememberScreenModel(tag = "combined-library-anime") { AnimeLibraryScreenModel() }
    val mangaModel = rememberScreenModel(tag = "combined-library-manga") { MangaLibraryScreenModel() }
    val animeState by animeModel.state.collectAsState()
    val mangaState by mangaModel.state.collectAsState()
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var quickFilter by rememberSaveable { mutableStateOf(LibraryQuickFilter.ALL) }
    var collection by rememberSaveable { mutableStateOf<String?>(null) }
    val collections =
        animeState.categories.map { "anime:${it.id}" to "Anime · ${it.visualName}" } +
            mangaState.categories.map { "manga:${it.id}" to "Manga · ${it.visualName}" }
    LaunchedEffect(collections.map { it.first }) {
        if (collection != null && collections.none { it.first == collection }) collection = null
    }
    val displayPreference = remember { Injekt.get<LibraryPreferences>().allDisplayMode() }
    val displayMode by displayPreference.collectAsState()
    val viewedNotices = remember { Injekt.get<UiPreferences>().viewedLibraryShelfNotices() }

    var aliasesReady by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query != null && !aliasesReady) {
            Injekt.get<eu.kanade.tachiyomi.data.search.SmartTitleSearch>().prepareLibrary()
            aliasesReady = true
        }
    }

    // Keep the current list while the replacement is prepared. produceState cancels a superseded
    // query, so a slower previous search cannot overwrite the latest result or block a gesture.
    val preparedEntries by produceState<List<UnifiedLibraryEntry>?>(
        null,
        animeState.library,
        mangaState.library,
        query,
        aliasesReady,
        collection,
    ) {
        value = withContext(Dispatchers.Default) {
            val animeItems = animeState.library.filterKeys {
                collection == null || collection == "anime:${it.id}"
            }.values.flatten().distinctBy { it.libraryAnime.id }
                .filter { query.isNullOrBlank() || it.matches(query.orEmpty()) }.map(UnifiedLibraryEntry::Anime)
            val mangaItems = mangaState.library.filterKeys {
                collection == null || collection == "manga:${it.id}"
            }.values.flatten().distinctBy { it.libraryManga.id }
                .filter { query.isNullOrBlank() || it.matches(query.orEmpty()) }.map(UnifiedLibraryEntry::Manga)
            (animeItems + mangaItems).sortedBy { it.title.lowercase() }
        }
    }
    val entries = preparedEntries.orEmpty()
    val filteredEntries =
        remember(entries, quickFilter) {
            entries.filter {
                quickFilter.includes(it.unviewedCount, it.completedCount, it.lastAccess, it.status.newReleaseCount)
            }
        }

    fun refreshAll() {
        val animeStarted = AnimeLibraryUpdateJob.startNow(context, null)
        val mangaStarted = MangaLibraryUpdateJob.startNow(context, null)
        scope.launch {
            snackbar.showSnackbar(
                context.getString(
                    if (animeStarted || mangaStarted) {
                        R.string.library_all_refresh_started
                    } else {
                        R.string.library_all_refresh_running
                    },
                ),
            )
        }
    }

    Scaffold(
        modifier = Modifier.privacyRegion(PrivacyArea.LIBRARY),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { contentPadding ->
        when {
            preparedEntries == null || animeState.isLoading && mangaState.isLoading -> {
                Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            entries.isEmpty() && query == null && collection == null -> {
                Column(
                    Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.CollectionsBookmark,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.library_all_empty_title),
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.library_empty_hint),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
                    AllLibraryControls(
                        query = query,
                        count = entries.size,
                        displayMode = displayMode,
                        onDisplayMode = displayPreference::set,
                        onQueryChange = { query = it },
                        onRefresh = ::refreshAll,
                        collections = collections, collection = collection, onCollection = { collection = it },
                    )
                    LibraryQuickFilters(quickFilter) { quickFilter = it }
                    if (filteredEntries.isEmpty()) {
                        Text(
                            stringResource(R.string.library_quick_filter_empty),
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AllLibraryShelf(
                        entries = filteredEntries,
                        displayMode = displayMode,
                        contentPadding = PaddingValues(
                            bottom =
                            contentPadding.calculateBottomPadding() +
                                eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset.current,
                        ),
                        onOpen = { entry ->
                            acknowledgeShelfNotices(viewedNotices, entry.status)
                            when (entry) {
                                is UnifiedLibraryEntry.Anime -> navigator.push(AnimeScreen(entry.item.libraryAnime.id))
                                is UnifiedLibraryEntry.Manga -> navigator.push(MangaScreen(entry.item.libraryManga.id))
                            }
                        },
                        onContinue = { entry ->
                            acknowledgeShelfNotices(viewedNotices, entry.status)
                            when (entry) {
                                is UnifiedLibraryEntry.Anime -> scope.launchIO {
                                    val episode = animeModel.getNextUnseenEpisode(entry.item.libraryAnime.anime)
                                        ?: return@launchIO
                                    MainActivity.startPlayerActivity(
                                        context,
                                        episode.animeId,
                                        episode.id,
                                        playerPreferences.alwaysUseExternalPlayer().get(),
                                    )
                                }
                                is UnifiedLibraryEntry.Manga -> scope.launchIO {
                                    val chapter = mangaModel.getNextUnreadChapter(entry.item.libraryManga.manga)
                                        ?: return@launchIO
                                    context.startActivity(
                                        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                    )
                                }
                            }
                        },
                        onDownload = { entry ->
                            when (entry) {
                                is UnifiedLibraryEntry.Anime -> {
                                    animeModel.downloadAllUnseen(entry.item.libraryAnime.anime)
                                }
                                is UnifiedLibraryEntry.Manga -> {
                                    mangaModel.downloadAllUnread(entry.item.libraryManga.manga)
                                }
                            }
                        },
                        onMarkViewed = { entry, viewed ->
                            when (entry) {
                                is UnifiedLibraryEntry.Anime -> animeModel.markSeen(entry.item.libraryAnime, viewed)
                                is UnifiedLibraryEntry.Manga -> mangaModel.markRead(entry.item.libraryManga, viewed)
                            }
                        },
                        onRefresh = { entry ->
                            when (entry) {
                                is UnifiedLibraryEntry.Anime -> AnimeLibraryUpdateJob.startNow(context, null)
                                is UnifiedLibraryEntry.Manga -> MangaLibraryUpdateJob.startNow(context, null)
                            }
                        },
                    )
                }
            }
        }
    }

    BackHandler(enabled = query != null) { query = null }
}

@Composable
private fun AllLibraryControls(
    query: String?,
    count: Int,
    displayMode: LibraryDisplayMode,
    onDisplayMode: (LibraryDisplayMode) -> Unit,
    onQueryChange: (String?) -> Unit,
    onRefresh: () -> Unit,
    collections: List<Pair<String, String>>,
    collection: String?,
    onCollection: (String?) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var displayMenu by remember { mutableStateOf(false) }
    var collectionMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(15.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        if (query == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CollectionsBookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Box(Modifier.weight(1f)) {
                    androidx.compose.material3.TextButton(onClick = { collectionMenu = true }) {
                        Text(
                            collections.find { it.first == collection }?.second
                                ?: stringResource(R.string.library_filter_all_titles),
                            maxLines = 2,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    DropdownMenu(expanded = collectionMenu, onDismissRequest = { collectionMenu = false }) {
                        DropdownMenuItem(text = {
                            Text(stringResource(R.string.library_filter_all_titles))
                        }, onClick = {
                            onCollection(null)
                            collectionMenu =
                                false
                        })
                        collections.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                onCollection(key)
                                collectionMenu =
                                    false
                            })
                        }
                    }
                }
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { displayMenu = true }) {
                        Icon(Icons.Outlined.ViewModule, "Visualizzazione")
                    }
                    DropdownMenu(expanded = displayMenu, onDismissRequest = { displayMenu = false }) {
                        allLibraryDisplayModes.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onDisplayMode(mode)
                                    displayMenu = false
                                },
                                leadingIcon = if (displayMode == mode) {
                                    { Icon(Icons.Outlined.CollectionsBookmark, null) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Search, stringResource(R.string.library_search_action))
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.library_all_refresh))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    focusManager.clearFocus()
                    onQueryChange(null)
                }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                }
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.library_search_action)) },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
            }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}

private val allLibraryDisplayModes = listOf(
    LibraryDisplayMode.CompactGrid to "Griglia",
    LibraryDisplayMode.ComfortableGrid to "Griglia ampia",
    LibraryDisplayMode.CoverOnlyGrid to "Solo copertine",
    LibraryDisplayMode.List to "Elenco",
    LibraryDisplayMode.Shelf to "Scaffale",
)

@Composable
private fun AllLibraryShelf(
    entries: List<UnifiedLibraryEntry>,
    displayMode: LibraryDisplayMode,
    contentPadding: PaddingValues,
    onOpen: (UnifiedLibraryEntry) -> Unit,
    onContinue: (UnifiedLibraryEntry) -> Unit,
    onDownload: (UnifiedLibraryEntry) -> Unit,
    onMarkViewed: (UnifiedLibraryEntry, Boolean) -> Unit,
    onRefresh: (UnifiedLibraryEntry) -> Unit,
) {
    var menuEntry by remember { mutableStateOf<UnifiedLibraryEntry?>(null) }
    var downloading by remember { mutableStateOf<Set<String>>(emptySet()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val haptic = LocalHapticFeedback.current
    val motion = appMotionEnabled()
    val news = remember(entries) { entries.filter { it.status.newReleaseCount > 0 } }
    val library = remember(entries) { entries.filterNot { it.status.newReleaseCount > 0 } }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(downloading) {
        if (downloading.isNotEmpty()) {
            delay(1_200)
            downloading = emptySet()
        }
    }

    if (displayMode == LibraryDisplayMode.Shelf) {
        FastScrollLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        ) {
            if (news.isNotEmpty()) {
                item(key = "all_news_header", contentType = "library_shelf_header") {
                    LibraryShelfSectionHeader(
                        title = stringResource(R.string.library_shelf_news),
                        count = news.size,
                        isNews = true,
                    )
                }
            }
            news.forEachIndexed { index, entry ->
                item(key = "all_entry_${entry.key}", contentType = "all_library_shelf_item") {
                    UnifiedShelfRow(
                        modifier = Modifier.then(if (motion) Modifier.animateItemFastScroll() else Modifier),
                        entry = entry,
                        index = index,
                        groupSize = news.size,
                        now = now,
                        downloading = entry.key in downloading,
                        onOpen = { onOpen(entry) },
                        onContinue = { onContinue(entry) },
                        onDownload = {
                            downloading = downloading + entry.key
                            onDownload(entry)
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuEntry = entry
                        },
                    )
                }
            }
            if (news.isNotEmpty() && library.isNotEmpty()) {
                item(key = "all_library_header", contentType = "library_shelf_header") {
                    LibraryShelfSectionHeader(
                        title = stringResource(R.string.library_shelf_collection),
                        count = library.size,
                        isNews = false,
                    )
                }
            }
            library.forEachIndexed { index, entry ->
                item(key = "all_library_${entry.key}", contentType = "all_library_shelf_item") {
                    UnifiedShelfRow(
                        modifier = Modifier.then(if (motion) Modifier.animateItemFastScroll() else Modifier),
                        entry = entry,
                        index = index,
                        groupSize = library.size,
                        now = now,
                        downloading = entry.key in downloading,
                        onOpen = { onOpen(entry) },
                        onContinue = { onContinue(entry) },
                        onDownload = {
                            downloading = downloading + entry.key
                            onDownload(entry)
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuEntry = entry
                        },
                    )
                }
            }
        }
    } else {
        AllLibraryGridOrList(
            entries = entries,
            displayMode = displayMode,
            contentPadding = contentPadding,
            onOpen = onOpen,
            onContinue = onContinue,
            onLongClick = { menuEntry = it },
        )
    }

    menuEntry?.let { entry ->
        LibraryShelfActionsSheet(
            title = entry.title,
            coverData = entry.cover,
            isAnime = entry is UnifiedLibraryEntry.Anime,
            hasUnviewed = entry.unviewedCount > 0,
            onDismiss = { menuEntry = null },
            onOpen = { onOpen(entry) },
            onContinue = { onContinue(entry) }.takeIf { entry.unviewedCount > 0 },
            onMarkViewed = { onMarkViewed(entry, true) },
            onMarkUnviewed = { onMarkViewed(entry, false) },
            onUpdate = { onRefresh(entry) },
            onChangeCategory = null,
            onSelect = null,
            onRemove = null,
        )
    }
}

@Composable
private fun AllLibraryGridOrList(
    entries: List<UnifiedLibraryEntry>,
    displayMode: LibraryDisplayMode,
    contentPadding: PaddingValues,
    onOpen: (UnifiedLibraryEntry) -> Unit,
    onContinue: (UnifiedLibraryEntry) -> Unit,
    onLongClick: (UnifiedLibraryEntry) -> Unit,
) {
    if (displayMode == LibraryDisplayMode.List) {
        FastScrollLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
        ) {
            items(entries, key = { it.key }, contentType = { "all_library_list_item" }) { entry ->
                EntryListItem(
                    title = entry.title,
                    coverData = entry.cover,
                    contentLabels = entry.contentLabels,
                    libraryStyle = true,
                    onClick = { onOpen(entry) },
                    onLongClick = { onLongClick(entry) },
                    onClickContinueViewing = { onContinue(entry) }.takeIf { entry.unviewedCount > 0 },
                    badge = {
                        DownloadsBadge(count = entry.downloadCount)
                        UnviewedBadge(count = entry.unviewedCount)
                    },
                )
            }
        }
    } else {
        LazyLibraryGrid(
            modifier = Modifier.fillMaxSize(),
            columns = 0,
            contentPadding = contentPadding,
            showTitle = displayMode != LibraryDisplayMode.CoverOnlyGrid,
        ) {
            gridItems(entries, key = { it.key }, contentType = { "all_library_grid_item" }) { entry ->
                val onOpenEntry = { onOpen(entry) }
                val onLongClickEntry = { onLongClick(entry) }
                val onContinueEntry = { onContinue(entry) }.takeIf { entry.unviewedCount > 0 }
                if (displayMode == LibraryDisplayMode.ComfortableGrid) {
                    EntryComfortableGridItem(
                        title = entry.title,
                        coverData = entry.cover,
                        contentLabels = entry.contentLabels,
                        libraryStyle = true,
                        onClick = onOpenEntry,
                        onLongClick = onLongClickEntry,
                        onClickContinueViewing = onContinueEntry,
                        coverBadgeStart = {
                            DownloadsBadge(count = entry.downloadCount)
                            UnviewedBadge(count = entry.unviewedCount)
                        },
                    )
                } else {
                    EntryCompactGridItem(
                        title = entry.title.takeIf { displayMode == LibraryDisplayMode.CompactGrid },
                        coverData = entry.cover,
                        contentLabels = entry.contentLabels,
                        libraryStyle = true,
                        onClick = onOpenEntry,
                        onLongClick = onLongClickEntry,
                        onClickContinueViewing = onContinueEntry,
                        coverBadgeStart = {
                            DownloadsBadge(count = entry.downloadCount)
                            UnviewedBadge(count = entry.unviewedCount)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedShelfRow(
    modifier: Modifier,
    entry: UnifiedLibraryEntry,
    index: Int,
    groupSize: Int,
    now: Long,
    downloading: Boolean,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    onDownload: () -> Unit,
    onLongClick: () -> Unit,
) {
    val position = when {
        groupSize == 1 -> LibraryShelfPosition.Only
        index == 0 -> LibraryShelfPosition.First
        index == groupSize - 1 -> LibraryShelfPosition.Last
        else -> LibraryShelfPosition.Middle
    }
    LibraryShelfItem(
        title = entry.title,
        coverData = entry.cover,
        status = entry.status,
        progressText = entry.progressText(),
        progressFraction = entry.totalCount.takeIf { it > 0 }?.let { entry.completedCount.toFloat() / it },
        unviewedCount = entry.unviewedCount,
        downloadCount = entry.downloadCount,
        isAnime = entry is UnifiedLibraryEntry.Anime,
        isLocal = entry.isLocal,
        selected = false,
        now = now,
        downloading = downloading,
        position = position,
        onClick = onOpen,
        onLongClick = onLongClick,
        onContinue = onContinue.takeIf { entry.unviewedCount > 0 },
        onDownload = onDownload.takeIf { !entry.isLocal && entry.unviewedCount > 0 },
        contentLabels = entry.contentLabels,
        modifier = modifier.padding(vertical = 1.dp),
    )
}

@Composable
private fun UnifiedLibraryEntry.progressText(): String = when (this) {
    is UnifiedLibraryEntry.Anime -> stringResource(
        R.string.library_shelf_episode_progress,
        item.libraryAnime.seenCount,
        item.libraryAnime.totalCount,
    )
    is UnifiedLibraryEntry.Manga -> stringResource(
        R.string.library_shelf_chapter_progress,
        item.libraryManga.readCount,
        item.libraryManga.totalChapters,
    )
}

private sealed interface UnifiedLibraryEntry {
    val key: String
    val title: String
    val status: LibraryShelfStatus
    val totalCount: Long
    val completedCount: Long
    val lastAccess: Long
    val unviewedCount: Long
    val downloadCount: Long
    val isLocal: Boolean
    val contentLabels: List<String>?
    val cover: tachiyomi.domain.entries.EntryCover

    data class Anime(val item: AnimeLibraryItem) : UnifiedLibraryEntry {
        override val key = "anime_${item.libraryAnime.id}"
        override val title = item.libraryAnime.anime.title
        override val status = item.shelfStatus
        override val totalCount = item.libraryAnime.totalCount
        override val completedCount = item.libraryAnime.seenCount
        override val lastAccess = item.libraryAnime.lastSeen
        override val unviewedCount = item.libraryAnime.unseenCount
        override val downloadCount = item.downloadCount
        override val isLocal = item.libraryAnime.anime.isLocal()
        override val contentLabels = item.libraryAnime.anime.genre
        override val cover = AnimeCover(
            animeId = item.libraryAnime.id,
            sourceId = item.libraryAnime.anime.source,
            isAnimeFavorite = item.libraryAnime.anime.favorite,
            url = item.libraryAnime.anime.thumbnailUrl,
            lastModified = item.libraryAnime.anime.coverLastModified,
        )
    }

    data class Manga(val item: MangaLibraryItem) : UnifiedLibraryEntry {
        override val key = "manga_${item.libraryManga.id}"
        override val title = item.libraryManga.manga.title
        override val status = item.shelfStatus
        override val totalCount = item.libraryManga.totalChapters
        override val completedCount = item.libraryManga.readCount
        override val lastAccess = item.libraryManga.lastRead
        override val unviewedCount = item.libraryManga.unreadCount
        override val downloadCount = item.downloadCount
        override val isLocal = item.libraryManga.manga.isLocal()
        override val contentLabels = item.libraryManga.manga.genre
        override val cover = MangaCover(
            mangaId = item.libraryManga.id,
            sourceId = item.libraryManga.manga.source,
            isMangaFavorite = item.libraryManga.manga.favorite,
            url = item.libraryManga.manga.thumbnailUrl,
            lastModified = item.libraryManga.manga.coverLastModified,
        )
    }
}
