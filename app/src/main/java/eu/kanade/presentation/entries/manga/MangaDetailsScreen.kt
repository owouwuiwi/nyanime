package eu.kanade.presentation.entries.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.relativeDateTimeText
import eu.kanade.presentation.components.releases.ReleaseFollowAction
import eu.kanade.presentation.discovery.manga.MangaSourceAction
import eu.kanade.presentation.entries.DownloadAction
import eu.kanade.presentation.entries.details.DetailAction
import eu.kanade.presentation.entries.details.DetailDownloadActions
import eu.kanade.presentation.entries.details.DetailMetadata
import eu.kanade.presentation.entries.details.DetailPart
import eu.kanade.presentation.entries.details.TitleDetailsIcons
import eu.kanade.presentation.entries.details.TitleDetailsScreen
import eu.kanade.presentation.entries.details.detailStatus
import eu.kanade.presentation.entries.details.rememberDetailDateFormatter
import eu.kanade.presentation.entries.manga.components.ChapterDownloadAction
import eu.kanade.presentation.entries.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.entries.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.entries.manga.components.MangaAnimeContinuityCard
import eu.kanade.presentation.entries.manga.components.getSwipeAction
import eu.kanade.presentation.privacy.contentPrivacyRegion
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.presentation.share.ContentShareDialog
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import eu.kanade.tachiyomi.data.share.ContentLink
import eu.kanade.tachiyomi.data.share.SharedMedium
import eu.kanade.tachiyomi.data.track.MangaAnimeContinuity
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.manga.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.browse.manga.extension.details.MangaSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.entries.manga.ChapterList
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import me.saket.swipe.SwipeableActionsBox
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.manga.model.StubMangaSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import androidx.compose.ui.res.stringResource as androidStringResource
import tachiyomi.core.common.i18n.stringResource as localized

@Composable
internal fun MangaDetailsScreen(
    state: MangaScreenModel.State.Success,
    onOpenAnime: (Anime) -> Unit,
    onSearchAnime: (String) -> Unit,
    onRetryAnime: () -> Unit,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditFetchIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
) {
    val context = LocalContext.current
    val manga = state.manga
    val workInfo = eu.kanade.presentation.workinfo.rememberMangaWorkInfo(state)
    val workDescription = eu.kanade.tachiyomi.data.workinfo.WorkInformationPresentation.description(
        manga.description,
        workInfo.information,
    )
    val chapters = state.processedChapters
    val chapterById = remember(chapters) { chapters.associateBy { it.id } }
    val unread = state.chapters.getNextUnread(manga)
    val reread = unread == null && chapters.isNotEmpty() && chapters.all { it.chapter.read }
    val current = unread ?: chapters.firstOrNull().takeIf { reread }?.chapter
    var noticesReady by remember(manga.id) { mutableStateOf(false) }
    val store = remember { ReleaseStore() }
    val newIds by remember(manga.id) {
        store.noticeFlow(ReleaseMedium.MANGA).map { notices ->
            notices.filter { it.entryId == manga.id }.mapTo(mutableSetOf()) { it.itemId }
        }
            .onEach { noticesReady = true }
            .catch {
                noticesReady = true
                emit(mutableSetOf())
            }
    }.collectAsState(initial = emptySet())
    val readLabel = androidStringResource(R.string.detail_read)
    val offlineLabel = androidStringResource(R.string.detail_offline)
    val bookmarkLabel = androidStringResource(R.string.detail_bookmark)
    val dateText = rememberDetailDateFormatter()
    val parts = remember(chapters.map { it.chapter to it.isDownloaded }, newIds, manga.displayMode, dateText) {
        chapters.map { item ->
            val chapter = item.chapter
            val number = formatChapterNumber(chapter.chapterNumber).takeIf { chapter.chapterNumber >= 0 } ?: "—"
            DetailPart(
                id = item.id,
                number = number,
                title = if (manga.displayMode ==
                    Manga.CHAPTER_DISPLAY_NUMBER
                ) {
                    context.localized(MR.strings.display_mode_chapter, number)
                } else {
                    chapter.name
                },
                information = listOfNotNull(
                    if (chapter.read) {
                        readLabel
                    } else if (chapter.lastPageRead >
                        0
                    ) {
                        context.getString(R.string.detail_page, chapter.lastPageRead + 1)
                    } else {
                        dateText(chapter.dateUpload)
                    },
                    chapter.scanlator?.takeIf {
                        it.isNotBlank()
                    },
                    offlineLabel.takeIf { item.isDownloaded },
                    bookmarkLabel.takeIf { chapter.bookmark },
                ).joinToString(" · "),
                completed = chapter.read,
                bookmarked = chapter.bookmark,
                offline = item.isDownloaded,
                fresh = item.id in newIds && !chapter.read,
            )
        }
    }
    val currentNumber = current?.chapterNumber?.takeIf { it >= 0 && it.isFinite() }?.let(::formatChapterNumber)
    val primary = if (currentNumber == null) {
        stringResource(MR.strings.action_resume)
    } else {
        androidStringResource(
            if (state.chapters.any {
                    it.chapter.read || it.chapter.lastPageRead > 0
                }
            ) {
                R.string.detail_resume_read
            } else {
                R.string.detail_start_read
            },
            currentNumber,
        )
    }
    val navigator = LocalNavigator.currentOrThrow
    val sourceSettings: (() -> Unit)? = {
        navigator.push(MangaSourcePreferencesScreen(state.source.id))
    }.takeIf { state.source is ConfigurableSource }
    val menu = buildList {
        if (state.trackingAvailable) {
            add(
                DetailAction(
                    androidStringResource(R.string.detail_tracking) +
                        if (state.trackingCount > 0) " · ${state.trackingCount}" else "",
                    TitleDetailsIcons.Checks,
                    onTrackingClicked,
                ),
            )
        }
        onWebViewClicked?.let {
            add(DetailAction(androidStringResource(R.string.detail_web), TitleDetailsIcons.Globe, it))
        }
        onWebViewLongClicked?.let {
            add(DetailAction(stringResource(MR.strings.action_copy_to_clipboard), TitleDetailsIcons.Share, it))
        }
        add(DetailAction(androidStringResource(R.string.detail_cover), TitleDetailsIcons.Info, onCoverClicked))
        add(DetailAction(androidStringResource(R.string.detail_refresh), TitleDetailsIcons.Refresh, onRefresh))
        onEditCategoryClicked?.let {
            add(DetailAction(stringResource(MR.strings.action_edit_categories), TitleDetailsIcons.Bookmark, it))
        }
        onEditFetchIntervalClicked?.let {
            add(DetailAction(androidStringResource(R.string.detail_update_interval), TitleDetailsIcons.Clock, it))
        }
        onMigrateClicked?.let {
            add(DetailAction(stringResource(MR.strings.action_migrate), TitleDetailsIcons.Globe, it))
        }
        sourceSettings?.let {
            add(DetailAction(stringResource(MR.strings.action_settings), TitleDetailsIcons.Filter, it))
        }
        add(
            DetailAction(androidStringResource(R.string.detail_search_title), TitleDetailsIcons.Search) {
                onSearch(manga.title, true)
            },
        )
        add(
            DetailAction(androidStringResource(R.string.detail_search_source), TitleDetailsIcons.Search) {
                onSearch(manga.title, false)
            },
        )
        addAll(DetailDownloadActions(true, onDownloadActionClicked))
    }
    TitleDetailsScreen(
        entryId = manga.id, manga = true, title = manga.title,
        kicker =
        androidStringResource(R.string.detail_manga) + " · " + detailStatus(manga.status),
        subtitle = state.source.getNameForMangaInfo(), artwork = manga, parts = parts, currentId = current?.id,
        primaryText = if (current == null) androidStringResource(R.string.detail_empty_parts) else primary,
        primaryInformation = current?.let {
            androidStringResource(R.string.detail_page, it.lastPageRead + 1)
        }.orEmpty(),
        totalCount = state.chapters.size, completedCount = state.chapters.count { it.chapter.read },
        noticeLoading = !noticesReady,
        favorite = manga.favorite, refreshing = state.isRefreshingData,
        sourceAvailable = state.source !is StubMangaSource, hasFilters = state.filterActive,
        filterKey = manga.chapterFlags,
        modifier = Modifier.contentPrivacyRegion(PrivacyArea.DETAILS, manga).nsfwPrivacy(manga),
        snackbar = snackbarHostState,
        onBack = navigateUp, onContinue = {
            if (reread &&
                current != null
            ) {
                onChapterClicked(current)
            } else {
                onContinueReading()
            }
        }, onLibrary = onAddToLibraryClicked,
        onCover = onCoverClicked, onFilter = onFilterButtonClicked, onRefresh = onRefresh,
        onShare = onShareClicked, onEditCategories = onEditCategoryClicked,
        onOpenPart = { id -> chapterById[id]?.let { onChapterClicked(it.chapter) } },
        follow = {
            ReleaseFollowAction(
                ReleaseMedium.MANGA,
                manga.id,
                manga.favorite ||
                    state.trackingCount > 0 ||
                    state.chapters.any { it.chapter.read || it.chapter.lastPageRead > 0 },
                detailsStyle = true,
            )
        },
        details = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailMetadata(manga.title, manga.author, manga.artist, state.source.getNameForMangaInfo(), onSearch)
                MangaSourceAction(MangaHomeItem(manga), discover = true)
                ExpandableMangaDescription(
                    defaultExpandState = true,
                    description = workDescription.text,
                    tagsProvider = { manga.genre },
                    onTagSearch = onTagSearch,
                    onCopyTagToClipboard = { context.copyToClipboard(it, it) },
                )
                if (workDescription.fromCatalogue) {
                    workInfo.information?.takeIf { !it.synopsis.isNullOrBlank() }?.let {
                        eu.kanade.presentation.workinfo.WorkLanguageNote(it)
                    }
                }
                if (state.animeContinuity is MangaAnimeContinuity.Result.Found ||
                    state.animeContinuity == MangaAnimeContinuity.Result.Unavailable
                ) {
                    MangaAnimeContinuityCard(state.animeContinuity, onOpenAnime, onSearchAnime, onRetryAnime)
                }
                eu.kanade.presentation.workinfo.WorkInformationSection(workInfo, hasSourceStatus = manga.status != 0L)
            }
        },
        actions = menu,
        partActions = { ids, clear ->
            val selected = chapters.filter { it.id in ids }
            val selectedChapters = selected.map { it.chapter }
            buildList {
                if (selected.isNotEmpty()) {
                    if (onDownloadChapter !=
                        null
                    ) {
                        add(
                            DetailAction(
                                androidStringResource(R.string.detail_download),
                                TitleDetailsIcons.Download,
                            ) {
                                clear()
                                onDownloadChapter(selected, ChapterDownloadAction.START)
                            },
                        )
                    }
                    add(
                        DetailAction(androidStringResource(R.string.detail_mark_read), TitleDetailsIcons.Checks) {
                            clear()
                            onMultiMarkAsReadClicked(selectedChapters, true)
                        },
                    )
                    add(
                        DetailAction(androidStringResource(R.string.detail_bookmark), TitleDetailsIcons.Bookmark) {
                            clear()
                            onMultiBookmarkClicked(selectedChapters, true)
                        },
                    )
                    add(
                        DetailAction(stringResource(MR.strings.action_mark_as_unread), TitleDetailsIcons.Refresh) {
                            clear()
                            onMultiMarkAsReadClicked(selectedChapters, false)
                        },
                    )
                    add(
                        DetailAction(stringResource(MR.strings.action_remove_bookmark), TitleDetailsIcons.Bookmark) {
                            clear()
                            onMultiBookmarkClicked(selectedChapters, false)
                        },
                    )
                    if (selected.any {
                            it.isDownloaded
                        }
                    ) {
                        add(
                            DetailAction(stringResource(MR.strings.action_delete), TitleDetailsIcons.Trash) {
                                clear()
                                onMultiDeleteClicked(selectedChapters)
                            },
                        )
                    }
                    if (selected.size == 1) {
                        val chapter = selected.single().chapter
                        add(
                            DetailAction(
                                androidStringResource(R.string.detail_previous_read),
                                TitleDetailsIcons.Checks,
                            ) {
                                clear()
                                onMarkPreviousAsReadClicked(chapter)
                            },
                        )
                        if (onShareClicked !=
                            null
                        ) {
                            add(
                                DetailAction(stringResource(MR.strings.action_share), TitleDetailsIcons.Share) {
                                    ContentShareDialog.show(
                                        context,
                                        ContentLink(
                                            medium = SharedMedium.MANGA,
                                            sourceId = manga.source,
                                            sourceName = state.source.name,
                                            entryUrl = manga.url,
                                            title = manga.title,
                                            itemUrl = chapter.url,
                                            itemTitle = chapter.name,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        download = { id ->
            chapterById[id]?.let { item ->
                onDownloadChapter?.let { onDownload ->
                    ChapterDownloadIndicator(
                        detailsStyle = true,
                        enabled = true,
                        downloadStateProvider = {
                            item.downloadState
                        },
                        downloadProgressProvider = { item.downloadProgress },
                        onClick = { onDownload(listOf(item), it) },
                    )
                }
            }
        },
        swipe = { part, content ->
            val item = chapterById[part.id]
            if (item == null) {
                content()
            } else {
                SwipeableActionsBox(
                    startActions = listOfNotNull(
                        getSwipeAction(
                            chapterSwipeStartAction,
                            item.chapter.read,
                            item.chapter.bookmark,
                            item.downloadState,
                            MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            onChapterSwipe(item, chapterSwipeStartAction)
                        },
                    ),
                    endActions = listOfNotNull(
                        getSwipeAction(
                            chapterSwipeEndAction,
                            item.chapter.read,
                            item.chapter.bookmark,
                            item.downloadState,
                            MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            onChapterSwipe(item, chapterSwipeEndAction)
                        },
                    ),
                    swipeThreshold = 56.dp,
                    backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.background,
                ) { content() }
            }
        },
    )
}
