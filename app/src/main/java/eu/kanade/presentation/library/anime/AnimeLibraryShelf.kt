package eu.kanade.presentation.library.anime

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
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.util.plus
import tachiyomi.source.local.entries.anime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun AnimeLibraryShelf(
    items: List<AnimeLibraryItem>,
    contentPadding: PaddingValues,
    selection: List<LibraryAnime>,
    onClick: (LibraryAnime) -> Unit,
    onContinue: (LibraryAnime) -> Unit,
    onDownload: (LibraryAnime) -> Unit,
    onMarkSeen: (LibraryAnime, Boolean) -> Unit,
    onUpdate: () -> Unit,
    onChangeCategory: (LibraryAnime) -> Unit,
    onSelect: (LibraryAnime) -> Unit,
    onRemove: (LibraryAnime) -> Unit,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    var menuItem by remember { mutableStateOf<AnimeLibraryItem?>(null) }
    var downloading by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val motion = appMotionEnabled()
    val haptic = LocalHapticFeedback.current
    val viewedNotices = remember { Injekt.get<UiPreferences>().viewedLibraryShelfNotices() }
    fun open(item: AnimeLibraryItem) {
        acknowledgeShelfNotices(viewedNotices, item.shelfStatus)
        onClick(item.libraryAnime)
    }
    fun continueWatching(item: AnimeLibraryItem) {
        acknowledgeShelfNotices(viewedNotices, item.shelfStatus)
        onContinue(item.libraryAnime)
    }
    val newsItems = remember(items) { items.filter { it.shelfStatus.newReleaseCount > 0 } }
    val shelfItems = remember(items) { items.filterNot { it.shelfStatus.newReleaseCount > 0 } }
    LaunchedEffect(Unit) {
        while (isActive) {
            val delayMillis = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(delayMillis)
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
            item(key = "anime_news_header", contentType = "library_shelf_header") {
                LibraryShelfSectionHeader(
                    title = stringResource(eu.kanade.tachiyomi.R.string.library_shelf_news),
                    count = newsItems.size,
                    isNews = true,
                )
            }
        }
        newsItems.forEachIndexed { index, item ->
            item(key = "anime_news_${item.libraryAnime.id}", contentType = "anime_library_shelf_item") {
                AnimeShelfRow(
                    modifier = Modifier.then(if (motion) Modifier.animateItemFastScroll() else Modifier),
                    item = item,
                    index = index,
                    groupSize = newsItems.size,
                    selection = selection,
                    downloading = downloading,
                    now = now,
                    onClick = { open(item) },
                    onContinue = { continueWatching(item) },
                    onDownload = { libraryAnime ->
                        downloading = downloading + libraryAnime.id
                        onDownload(libraryAnime)
                    },
                    onLongClick = { selectedItem, libraryAnime ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (selection.isEmpty()) menuItem = selectedItem else onSelect(libraryAnime)
                    },
                )
            }
        }
        if (newsItems.isNotEmpty() && shelfItems.isNotEmpty()) {
            item(key = "anime_shelf_header", contentType = "library_shelf_header") {
                LibraryShelfSectionHeader(
                    title = stringResource(eu.kanade.tachiyomi.R.string.library_shelf_collection),
                    count = shelfItems.size,
                    isNews = false,
                )
            }
        }
        shelfItems.forEachIndexed { index, item ->
            item(key = "anime_shelf_${item.libraryAnime.id}", contentType = "anime_library_shelf_item") {
                AnimeShelfRow(
                    modifier = Modifier.then(if (motion) Modifier.animateItemFastScroll() else Modifier),
                    item = item,
                    index = index,
                    groupSize = shelfItems.size,
                    selection = selection,
                    downloading = downloading,
                    now = now,
                    onClick = { open(item) },
                    onContinue = { continueWatching(item) },
                    onDownload = { libraryAnime ->
                        downloading = downloading + libraryAnime.id
                        onDownload(libraryAnime)
                    },
                    onLongClick = { selectedItem, libraryAnime ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (selection.isEmpty()) menuItem = selectedItem else onSelect(libraryAnime)
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
        val libraryAnime = item.libraryAnime
        LibraryShelfActionsSheet(
            title = libraryAnime.anime.title,
            coverData = AnimeCover(
                animeId = libraryAnime.id,
                sourceId = libraryAnime.anime.source,
                isAnimeFavorite = libraryAnime.anime.favorite,
                url = libraryAnime.anime.thumbnailUrl,
                lastModified = libraryAnime.anime.coverLastModified,
            ),
            isAnime = true,
            hasUnviewed = libraryAnime.unseenCount > 0,
            onDismiss = { menuItem = null },
            onOpen = { open(item) },
            onContinue = { continueWatching(item) }.takeIf { libraryAnime.unseenCount > 0 },
            onMarkViewed = { onMarkSeen(libraryAnime, true) },
            onMarkUnviewed = { onMarkSeen(libraryAnime, false) },
            onUpdate = onUpdate,
            onChangeCategory = { onChangeCategory(libraryAnime) },
            onSelect = { onSelect(libraryAnime) },
            onRemove = { onRemove(libraryAnime) },
        )
    }
}

@Composable
private fun AnimeShelfRow(
    modifier: Modifier,
    item: AnimeLibraryItem,
    index: Int,
    groupSize: Int,
    selection: List<LibraryAnime>,
    downloading: Set<Long>,
    now: Long,
    onClick: (LibraryAnime) -> Unit,
    onContinue: (LibraryAnime) -> Unit,
    onDownload: (LibraryAnime) -> Unit,
    onLongClick: (AnimeLibraryItem, LibraryAnime) -> Unit,
) {
    val libraryAnime = item.libraryAnime
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
        title = libraryAnime.anime.title,
        coverData = AnimeCover(
            animeId = libraryAnime.id,
            sourceId = libraryAnime.anime.source,
            isAnimeFavorite = libraryAnime.anime.favorite,
            url = libraryAnime.anime.thumbnailUrl,
            lastModified = libraryAnime.anime.coverLastModified,
        ),
        status = item.shelfStatus,
        progressText = stringResource(
            eu.kanade.tachiyomi.R.string.library_shelf_episode_progress,
            libraryAnime.seenCount,
            libraryAnime.totalCount,
        ),
        unviewedCount = libraryAnime.unseenCount,
        downloadCount = item.downloadCount,
        isAnime = true,
        isLocal = libraryAnime.anime.isLocal(),
        selected = selection.any { it.id == libraryAnime.id },
        now = now,
        downloading = libraryAnime.id in downloading,
        position = position,
        onClick = { onClick(libraryAnime) },
        onLongClick = { onLongClick(item, libraryAnime) },
        onContinue = { onContinue(libraryAnime) }.takeIf { libraryAnime.unseenCount > 0 },
        onDownload = { onDownload(libraryAnime) }.takeIf { libraryAnime.unseenCount > 0 },
        contentLabels = libraryAnime.anime.genre,
    )
}
