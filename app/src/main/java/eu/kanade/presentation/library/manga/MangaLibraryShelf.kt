package eu.kanade.presentation.library.manga

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.library.components.GlobalSearchItem
import eu.kanade.presentation.library.components.LibraryShelfActionsSheet
import eu.kanade.presentation.library.components.LibraryShelfItem
import eu.kanade.presentation.library.components.LibraryShelfPosition
import eu.kanade.presentation.library.components.LibraryShelfSectionHeader
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.ui.library.acknowledgeShelfNotices
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tachiyomi.domain.entries.manga.model.MangaCover
import tachiyomi.domain.library.manga.LibraryManga
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.util.plus
import tachiyomi.source.local.entries.manga.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun MangaLibraryShelf(
    items: List<MangaLibraryItem>,
    contentPadding: PaddingValues,
    selection: List<LibraryManga>,
    onClick: (LibraryManga) -> Unit,
    onContinue: (LibraryManga) -> Unit,
    onDownload: (LibraryManga) -> Unit,
    onMarkRead: (LibraryManga, Boolean) -> Unit,
    onUpdate: () -> Unit,
    onChangeCategory: (LibraryManga) -> Unit,
    onSelect: (LibraryManga) -> Unit,
    onRemove: (LibraryManga) -> Unit,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    var menuItem by remember { mutableStateOf<MangaLibraryItem?>(null) }
    var downloading by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val motion = appMotionEnabled()
    val haptic = LocalHapticFeedback.current
    val viewedNotices = remember { Injekt.get<UiPreferences>().viewedLibraryShelfNotices() }
    fun open(item: MangaLibraryItem) {
        acknowledgeShelfNotices(viewedNotices, item.shelfStatus)
        onClick(item.libraryManga)
    }
    fun continueReading(item: MangaLibraryItem) {
        acknowledgeShelfNotices(viewedNotices, item.shelfStatus)
        onContinue(item.libraryManga)
    }
    val newsItems = remember(items) { items.filter { it.shelfStatus.newReleaseCount > 0 } }
    val shelfItems = remember(items) { items.filterNot { it.shelfStatus.newReleaseCount > 0 } }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            now = System.currentTimeMillis()
        }
    }

    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 12.dp, vertical = 7.dp),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            item(key = "global_search") {
                GlobalSearchItem(
                    modifier = Modifier.fillMaxWidth(),
                    searchQuery = searchQuery,
                    onClick = onGlobalSearchClicked,
                )
            }
        }
        if (newsItems.isNotEmpty()) {
            item(key = "manga_news_header", contentType = "library_shelf_header") {
                LibraryShelfSectionHeader(
                    title = stringResource(eu.kanade.tachiyomi.R.string.library_shelf_news),
                    count = newsItems.size,
                    isNews = true,
                )
            }
        }
        newsItems.forEachIndexed { index, item ->
            item(key = "manga_news_${item.libraryManga.id}", contentType = "manga_library_shelf_item") {
                MangaShelfRow(
                    modifier = Modifier.then(if (motion) Modifier.animateItemFastScroll() else Modifier),
                    item = item,
                    index = index,
                    groupSize = newsItems.size,
                    selection = selection,
                    downloading = downloading,
                    now = now,
                    onClick = { open(item) },
                    onContinue = { continueReading(item) },
                    onDownload = { libraryManga ->
                        downloading = downloading + libraryManga.id
                        onDownload(libraryManga)
                    },
                    onLongClick = { selectedItem, libraryManga ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (selection.isEmpty()) menuItem = selectedItem else onSelect(libraryManga)
                    },
                )
            }
        }
        if (newsItems.isNotEmpty() && shelfItems.isNotEmpty()) {
            item(key = "manga_shelf_header", contentType = "library_shelf_header") {
                LibraryShelfSectionHeader(
                    title = stringResource(eu.kanade.tachiyomi.R.string.library_shelf_collection),
                    count = shelfItems.size,
                    isNews = false,
                )
            }
        }
        shelfItems.forEachIndexed { index, item ->
            item(key = "manga_shelf_${item.libraryManga.id}", contentType = "manga_library_shelf_item") {
                MangaShelfRow(
                    modifier = Modifier.then(if (motion) Modifier.animateItemFastScroll() else Modifier),
                    item = item,
                    index = index,
                    groupSize = shelfItems.size,
                    selection = selection,
                    downloading = downloading,
                    now = now,
                    onClick = { open(item) },
                    onContinue = { continueReading(item) },
                    onDownload = { libraryManga ->
                        downloading = downloading + libraryManga.id
                        onDownload(libraryManga)
                    },
                    onLongClick = { selectedItem, libraryManga ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (selection.isEmpty()) menuItem = selectedItem else onSelect(libraryManga)
                    },
                )
            }
        }
    }

    LaunchedEffect(downloading) {
        if (downloading.isNotEmpty()) {
            delay(1_200)
            downloading = emptySet()
        }
    }

    menuItem?.let { item ->
        val libraryManga = item.libraryManga
        LibraryShelfActionsSheet(
            title = libraryManga.manga.title,
            coverData = MangaCover(
                mangaId = libraryManga.id,
                sourceId = libraryManga.manga.source,
                isMangaFavorite = libraryManga.manga.favorite,
                url = libraryManga.manga.thumbnailUrl,
                lastModified = libraryManga.manga.coverLastModified,
            ),
            isAnime = false,
            hasUnviewed = libraryManga.unreadCount > 0,
            onDismiss = { menuItem = null },
            onOpen = { open(item) },
            onContinue = { continueReading(item) }.takeIf { libraryManga.unreadCount > 0 },
            onMarkViewed = { onMarkRead(libraryManga, true) },
            onMarkUnviewed = { onMarkRead(libraryManga, false) },
            onUpdate = onUpdate,
            onChangeCategory = { onChangeCategory(libraryManga) },
            onSelect = { onSelect(libraryManga) },
            onRemove = { onRemove(libraryManga) },
        )
    }
}

@Composable
private fun MangaShelfRow(
    modifier: Modifier,
    item: MangaLibraryItem,
    index: Int,
    groupSize: Int,
    selection: List<LibraryManga>,
    downloading: Set<Long>,
    now: Long,
    onClick: (LibraryManga) -> Unit,
    onContinue: (LibraryManga) -> Unit,
    onDownload: (LibraryManga) -> Unit,
    onLongClick: (MangaLibraryItem, LibraryManga) -> Unit,
) {
    val libraryManga = item.libraryManga
    val position = when {
        groupSize == 1 -> LibraryShelfPosition.Only
        index == 0 -> LibraryShelfPosition.First
        index == groupSize - 1 -> LibraryShelfPosition.Last
        else -> LibraryShelfPosition.Middle
    }
    LibraryShelfItem(
        modifier = modifier
            .padding(vertical = 1.dp)
            .fillMaxWidth(),
        title = libraryManga.manga.title,
        coverData = MangaCover(
            mangaId = libraryManga.id,
            sourceId = libraryManga.manga.source,
            isMangaFavorite = libraryManga.manga.favorite,
            url = libraryManga.manga.thumbnailUrl,
            lastModified = libraryManga.manga.coverLastModified,
        ),
        status = item.shelfStatus,
        progressText = stringResource(
            eu.kanade.tachiyomi.R.string.library_shelf_chapter_progress,
            libraryManga.readCount,
            libraryManga.totalChapters,
        ),
        unviewedCount = libraryManga.unreadCount,
        downloadCount = item.downloadCount,
        isAnime = false,
        isLocal = libraryManga.manga.isLocal(),
        selected = selection.any { it.id == libraryManga.id },
        now = now,
        downloading = libraryManga.id in downloading,
        position = position,
        onClick = { onClick(libraryManga) },
        onLongClick = { onLongClick(item, libraryManga) },
        onContinue = { onContinue(libraryManga) }.takeIf { libraryManga.unreadCount > 0 },
        onDownload = { onDownload(libraryManga) }.takeIf { libraryManga.unreadCount > 0 },
        contentLabels = libraryManga.manga.genre,
    )
}
