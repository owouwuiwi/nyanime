package eu.kanade.presentation.entries.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import aniyomi.domain.anime.SeasonAnime
import aniyomi.domain.anime.SeasonDisplayMode
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.entries.anime.model.episodesFiltered
import eu.kanade.domain.entries.anime.model.seasonsFiltered
import eu.kanade.presentation.components.relativeDateTimeText
import eu.kanade.presentation.components.releases.ReleaseFollowAction
import eu.kanade.presentation.components.releases.ScheduleEpisodeCard
import eu.kanade.presentation.components.releases.rememberScheduleNextAiringAt
import eu.kanade.presentation.entries.DownloadAction
import eu.kanade.presentation.entries.anime.components.AnimeMangaContinuityCard
import eu.kanade.presentation.entries.anime.components.AnimeSeasonListItem
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadIndicator
import eu.kanade.presentation.entries.anime.components.ExpandableAnimeDescription
import eu.kanade.presentation.entries.anime.components.NextEpisodeAiringListItem
import eu.kanade.presentation.entries.anime.components.UltraTitleSummary
import eu.kanade.presentation.entries.anime.components.getSwipeAction
import eu.kanade.presentation.entries.details.DetailAction
import eu.kanade.presentation.entries.details.DetailDownloadActions
import eu.kanade.presentation.entries.details.DetailMetadata
import eu.kanade.presentation.entries.details.DetailPart
import eu.kanade.presentation.entries.details.TitleDetailsIcons
import eu.kanade.presentation.entries.details.TitleDetailsScreen
import eu.kanade.presentation.entries.details.detailStatus
import eu.kanade.presentation.entries.details.rememberDetailDateFormatter
import eu.kanade.presentation.privacy.contentPrivacyRegion
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.presentation.share.ContentShareDialog
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.discovery.ResumeEpisodeSelector
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import eu.kanade.tachiyomi.data.share.ContentLink
import eu.kanade.tachiyomi.data.share.SharedMedium
import eu.kanade.tachiyomi.data.track.AnimeMangaContinuity
import eu.kanade.tachiyomi.source.anime.getNameForAnimeInfo
import eu.kanade.tachiyomi.ui.browse.anime.extension.details.AnimeSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreenModel
import eu.kanade.tachiyomi.ui.entries.anime.EpisodeList
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import me.saket.swipe.SwipeableActionsBox
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import androidx.compose.ui.res.stringResource as androidStringResource
import tachiyomi.core.common.i18n.stringResource as localized

@Composable
internal fun AnimeDetailsScreen(
    state: AnimeScreenModel.State.Success,
    continuity: AnimeMangaContinuity.Result?,
    onOpenManga: (Manga, Double?) -> Unit,
    onSearchManga: (String) -> Unit,
    onRetryContinuity: () -> Unit,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    showNextEpisodeAirTime: Boolean,
    alwaysUseExternalPlayer: Boolean,
    navigateUp: () -> Unit,
    onEpisodeClicked: (episode: Episode, alt: Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: (() -> Unit)?,

    // For tags menu
    onTagSearch: (String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueWatching: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditFetchIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    changeAnimeSkipIntro: (() -> Unit)?,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onMultiDeleteClicked: (List<Episode>) -> Unit,

    // For episode swipe
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,

    // Episode selection

    // Season clicked
    onSeasonClicked: (SeasonAnime) -> Unit,
    onContinueWatchingClicked: ((SeasonAnime) -> Unit)?,

    // Related anime clicked
    onRelatedAnimeClicked: (Anime) -> Unit,
    onRelatedAnimeLongClicked: (Anime) -> Unit,
    relatedAnimeDisplayMode: LibraryDisplayMode,
) {
    val context = LocalContext.current
    val anime = state.anime
    val workInfo = eu.kanade.presentation.workinfo.rememberAnimeWorkInfo(state)
    val workDescription = eu.kanade.tachiyomi.data.workinfo.WorkInformationPresentation.description(
        anime.description,
        workInfo.information,
    )
    val episodes = state.processedEpisodes
    val episodeById = remember(episodes) { episodes.associateBy { it.id } }
    val selector = remember { Injekt.get<ResumeEpisodeSelector>() }
    var resumeId by remember(anime.id) { mutableStateOf<Long?>(null) }
    var resumeResolved by remember(anime.id) { mutableStateOf(false) }
    LaunchedEffect(anime.id, episodes.map { it.episode }) {
        try {
            resumeId = selector.selectForAnime(anime.id) { it.id in episodeById }?.episode?.id
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            resumeId = episodes.firstOrNull { !it.episode.seen }?.id
        }
        resumeResolved = true
    }
    var noticesReady by remember(anime.id) { mutableStateOf(false) }
    val store = remember { ReleaseStore() }
    val newIds by remember(anime.id) {
        store.noticeFlow(ReleaseMedium.ANIME).map { notices ->
            notices.filter { it.entryId == anime.id }.mapTo(mutableSetOf()) { it.itemId }
        }
            .onEach { noticesReady = true }
            .catch {
                noticesReady = true
                emit(mutableSetOf())
            }
    }.collectAsState(initial = emptySet())
    val watchedLabel = androidStringResource(R.string.detail_watched)
    val offlineLabel = androidStringResource(R.string.detail_offline)
    val bookmarkLabel = androidStringResource(R.string.detail_bookmark)
    val fillerLabel = androidStringResource(R.string.detail_filler)
    val dateText = rememberDetailDateFormatter()
    val parts =
        remember(
            episodes.map {
                it.episode to it.isDownloaded
            },
            state.classifications,
            newIds,
            anime.displayMode,
            dateText,
        ) {
            episodes.map { item ->
                val episode = item.episode
                val number = formatEpisodeNumber(episode.episodeNumber).takeIf { episode.episodeNumber >= 0 } ?: "—"
                DetailPart(
                    id = item.id, number = number,
                    title =
                    if (anime.displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
                        context.localized(
                            AYMR.strings.display_mode_episode,
                            number,
                        )
                    } else {
                        episode.name
                    },

                    information = listOfNotNull(
                        if (episode.seen) {
                            watchedLabel
                        } else if (episode.lastSecondSeen > 0) {
                            detailTime(episode.lastSecondSeen)
                        } else {
                            dateText(episode.dateUpload)
                        },
                        episode.scanlator?.takeIf { it.isNotBlank() },
                        offlineLabel.takeIf { item.isDownloaded },
                        bookmarkLabel.takeIf { episode.bookmark },

                    ).joinToString(" · "),
                    badge = state.classifications[episode.id]?.takeIf {
                        it in
                            listOf(
                                eu.kanade.tachiyomi.data.episodeguide.EpisodeClassification.FILLER,
                                eu.kanade.tachiyomi.data.episodeguide.EpisodeClassification.MIXED,
                            )
                    }?.let { type ->
                        { eu.kanade.presentation.episodeguide.EpisodeGuideBadge(anime, episode, type) }
                    },
                    completed = episode.seen, bookmarked = episode.bookmark, offline = item.isDownloaded,
                    fresh = item.id in newIds && !episode.seen,
                    progress =
                    if (episode.totalSeconds > 0 && !episode.seen) {
                        episode.lastSecondSeen.toFloat() / episode.totalSeconds
                    } else {
                        0f
                    },

                    preview = episode.previewUrl.takeIf { state.showPreviews },
                    summary = episode.summary.takeIf { state.showSummaries },

                )
            }
        }
    val seasons = state.processedSeasons
    val seasonParts = seasons.map { item ->
        val season = item.seasonAnime
        DetailPart(
            id = season.id,
            number = formatEpisodeNumber(season.anime.seasonNumber),
            title = season.anime.title,
            information = context.getString(
                R.string.detail_progress_watch,
                season.seenCount.toInt(),
                season.totalCount.toInt(),
            ),

            completed = season.totalCount > 0 && season.seen,
            bookmarked = season.hasBookmarks,
            offline = item.downloadCount > 0,
            fresh = false,
        )
    }
    val isSeasons = anime.fetchType == FetchType.Seasons
    val rewatch = resumeResolved && resumeId == null && episodes.isNotEmpty() && episodes.all { it.episode.seen }
    val current = episodeById[resumeId]?.episode ?: episodes.firstOrNull().takeIf { rewatch }?.episode
    val watchStarted = state.episodes.any { it.episode.seen || it.episode.lastSecondSeen > 0 }
    val automaticFollowed = anime.favorite || state.trackingCount > 0 || watchStarted
    val seasonResumeId = seasons.firstOrNull { it.showContinueOverlay }?.seasonAnime?.id
        ?: seasonParts.firstOrNull { !it.completed }?.id ?: seasonParts.firstOrNull()?.id
    val currentNumber = current?.episodeNumber?.takeIf { it >= 0 && it.isFinite() }?.let(::formatEpisodeNumber)
    val primary = if (currentNumber == null) {
        stringResource(if (watchStarted) MR.strings.action_resume else MR.strings.action_start)
    } else {
        androidStringResource(
            when {
                rewatch -> R.string.detail_rewatch
                watchStarted -> R.string.detail_resume_watch
                else -> R.string.detail_start_watch
            },

            currentNumber,
        )
    }
    val nextAt = if (showNextEpisodeAirTime) {
        rememberScheduleNextAiringAt(
            anime.id,
            state.nextAiringEpisode.second.takeIf { it > 0 }?.times(1000L),
        )
    } else {
        null
    }
    val duration = nextAt?.let { eu.kanade.presentation.entries.anime.components.upcomingAiringDuration(it) }
    val nextRelease = duration?.let { "$it · ${relativeDateTimeText(nextAt!!)}" }
    val navigator = LocalNavigator.currentOrThrow
    val sourceSettings: (() -> Unit)? = {
        navigator.push(AnimeSourcePreferencesScreen(state.source.id))
    }.takeIf { state.source is ConfigurableAnimeSource }
    val menu = buildList {
        onTrackingClicked?.let {
            add(
                DetailAction(
                    androidStringResource(R.string.detail_tracking) + " · ${state.trackingCount}",
                    TitleDetailsIcons.Checks,
                    it,
                ),
            )
        }
        onWebViewClicked?.let {
            add(
                DetailAction(
                    androidStringResource(R.string.detail_web),
                    TitleDetailsIcons.Globe,
                    it,
                ),
            )
        }
        onWebViewLongClicked?.let {
            add(
                DetailAction(
                    stringResource(MR.strings.action_copy_to_clipboard),
                    TitleDetailsIcons.Share,
                    it,
                ),
            )
        }
        add(DetailAction(androidStringResource(R.string.detail_cover), TitleDetailsIcons.Info, onCoverClicked))
        add(DetailAction(androidStringResource(R.string.detail_refresh), TitleDetailsIcons.Refresh, onRefresh))
        onEditCategoryClicked?.let {
            add(
                DetailAction(
                    stringResource(MR.strings.action_edit_categories),
                    TitleDetailsIcons.Bookmark,
                    it,
                ),
            )
        }
        onEditFetchIntervalClicked?.let {
            add(
                DetailAction(
                    androidStringResource(R.string.detail_update_interval),
                    TitleDetailsIcons.Clock,
                    it,
                ),
            )
        }
        onMigrateClicked?.let {
            add(
                DetailAction(
                    stringResource(MR.strings.action_migrate),
                    TitleDetailsIcons.Globe,
                    it,
                ),
            )
        }
        sourceSettings?.let { add(DetailAction(stringResource(AYMR.strings.settings), TitleDetailsIcons.Filter, it)) }
        changeAnimeSkipIntro?.let {
            add(
                DetailAction(
                    stringResource(AYMR.strings.action_change_intro_length),
                    TitleDetailsIcons.Clock,
                    it,
                ),
            )
        }
        add(
            DetailAction(
                androidStringResource(R.string.detail_search_title),
                TitleDetailsIcons.Search,
            ) {
                onSearch(
                    anime.title,
                    true,
                )
            },
        )
        add(
            DetailAction(androidStringResource(R.string.detail_search_source), TitleDetailsIcons.Search) {
                onSearch(anime.title, false)
            },
        )
        addAll(DetailDownloadActions(false, onDownloadActionClicked))
    }
    TitleDetailsScreen(
        entryId = anime.id, manga = false, title = anime.title,
        kicker = androidStringResource(R.string.detail_anime) + " · " + detailStatus(anime.status),
        subtitle = state.source.getNameForAnimeInfo(),

        artwork = anime, backgroundArtwork = !anime.backgroundUrl.isNullOrBlank(),
        parts = if (isSeasons) seasonParts else parts,
        currentId =
        if (isSeasons) seasonResumeId else current?.id,

        primaryText = when {
            isSeasons -> stringResource(MR.strings.action_resume)
            current == null && resumeResolved -> androidStringResource(R.string.detail_empty_parts)
            else -> primary
        },
        primaryLoading = !isSeasons && !resumeResolved,
        primaryInformation =
        current?.let {
            detailTime(it.lastSecondSeen) + if (it.totalSeconds > 0) " / " + detailTime(it.totalSeconds) else ""
        }.orEmpty(),

        totalCount = if (isSeasons) state.seasons.size else state.episodes.size,
        completedCount =
        if (isSeasons) state.seasons.count { it.seasonAnime.seen } else state.episodes.count { it.episode.seen },

        noticeLoading = !noticesReady,
        favorite = anime.favorite, refreshing = state.isRefreshingData,
        sourceAvailable = state.source !is StubAnimeSource,
        hasFilters = if (isSeasons) state.anime.seasonsFiltered() else state.anime.episodesFiltered(),

        filterKey = if (isSeasons) anime.seasonFlags else anime.episodeFlags,
        modifier = Modifier.contentPrivacyRegion(PrivacyArea.DETAILS, anime).nsfwPrivacy(anime),
        snackbar = snackbarHostState, nextRelease = nextRelease,
        onBack = navigateUp, onContinue = {
            when {
                isSeasons -> seasons.firstOrNull { it.seasonAnime.id == seasonResumeId }?.let {
                    if (onContinueWatchingClicked != null) {
                        onContinueWatchingClicked(it.seasonAnime)
                    } else {
                        onSeasonClicked(it.seasonAnime)
                    }
                }
                current != null -> onEpisodeClicked(current, false)
                else -> onContinueWatching()
            }
        }, onLibrary = onAddToLibraryClicked,
        onCover = onCoverClicked, onFilter = onFilterButtonClicked, onRefresh = onRefresh,
        onShare = onShareClicked, onEditCategories = onEditCategoryClicked,
        onOpenPart = { id ->
            if (isSeasons) {
                seasons.firstOrNull { it.seasonAnime.id == id }?.let { onSeasonClicked(it.seasonAnime) }
            } else {
                episodeById[id]?.let { onEpisodeClicked(it.episode, false) }
            }
        },
        follow = {
            ReleaseFollowAction(
                ReleaseMedium.ANIME,
                anime.id,
                automaticFollowed,

                detailsStyle = true,
            )
        },
        details = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailMetadata(anime.title, anime.author, anime.artist, state.source.getNameForAnimeInfo(), onSearch)
                ExpandableAnimeDescription(
                    defaultExpandState = true,
                    description = workDescription.text,
                    tagsProvider = { anime.genre },
                    onTagSearch = onTagSearch, onCopyTagToClipboard = { context.copyToClipboard(it, it) },
                    relations = state.relatedAnime, onRelatedClick = onRelatedAnimeClicked,
                    onRelatedLongClick = onRelatedAnimeLongClicked, relatedDisplayMode = relatedAnimeDisplayMode,
                )
                if (workDescription.fromCatalogue) {
                    workInfo.information?.takeIf { !it.synopsis.isNullOrBlank() }?.let {
                        eu.kanade.presentation.workinfo.WorkLanguageNote(it)
                    }
                }
                if (continuity is AnimeMangaContinuity.Result.Found ||
                    continuity == AnimeMangaContinuity.Result.Unavailable
                ) {
                    AnimeMangaContinuityCard(
                        continuity,
                        onOpenManga,
                        onSearchManga,
                        onRetryContinuity,
                    )
                }
                if (showNextEpisodeAirTime) {
                    ScheduleEpisodeCard(anime.id) {
                        if (nextAt != null && nextAt > System.currentTimeMillis()) {
                            NextEpisodeAiringListItem(
                                animeId = anime.id,
                                title = androidStringResource(R.string.detail_next_release),
                                airingAtMillis = nextAt,
                            )
                        }
                    }
                }
                UltraTitleSummary(anime, state.episodes.filter { it.isDownloaded }.map { it.episode })
                eu.kanade.presentation.workinfo.WorkInformationSection(workInfo, hasSourceStatus = anime.status != 0L)
            }
        },
        extraParts = {
            if (!isSeasons) {
                eu.kanade.presentation.episodeguide.EpisodeGuideEntry(
                    anime,
                    state.episodes.map {
                        it.episode
                    },
                    workInfo.information?.work,
                )
            }
        },
        partLabel = androidStringResource(R.string.detail_seasons).takeIf { isSeasons },
        seasonColumns = if (anime.seasonDisplayGridMode == SeasonDisplayMode.List) {
            1
        } else {
            anime.seasonDisplayGridSize.takeIf { it > 0 }?.coerceIn(1, 12) ?: 2
        },
        seasonItem = if (isSeasons) {
            { part, beforeNavigate ->
                seasons.firstOrNull { it.seasonAnime.id == part.id }?.let { item ->
                    AnimeSeasonListItem(
                        anime = anime,
                        item = item,
                        containerHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp,
                        onSeasonClicked = {
                            beforeNavigate()
                            onSeasonClicked(it)
                        },
                        onClickContinueWatching = onContinueWatchingClicked?.let { play ->
                            { season ->
                                beforeNavigate()
                                play(season)
                            }
                        },
                    )
                }
            }
        } else {
            null
        },
        actions = menu,
        partActions = { ids, clear ->
            val selected = episodes.filter { it.id in ids }
            val selectedEpisodes = selected.map { it.episode }
            buildList {
                if (selected.isNotEmpty()) {
                    if (onDownloadEpisode != null) {
                        add(
                            DetailAction(androidStringResource(R.string.detail_download), TitleDetailsIcons.Download) {
                                clear()
                                onDownloadEpisode(selected, EpisodeDownloadAction.START)
                            },
                        )
                    }
                    add(
                        DetailAction(androidStringResource(R.string.detail_mark_watched), TitleDetailsIcons.Checks) {
                            clear()
                            onMultiMarkAsSeenClicked(selectedEpisodes, true)
                        },
                    )
                    add(
                        DetailAction(androidStringResource(R.string.detail_bookmark), TitleDetailsIcons.Bookmark) {
                            clear()
                            onMultiBookmarkClicked(selectedEpisodes, true)
                        },
                    )
                    add(
                        DetailAction(stringResource(AYMR.strings.action_mark_as_unseen), TitleDetailsIcons.Refresh) {
                            clear()
                            onMultiMarkAsSeenClicked(selectedEpisodes, false)
                        },
                    )
                    add(
                        DetailAction(
                            stringResource(AYMR.strings.action_remove_bookmark_episode),
                            TitleDetailsIcons.Bookmark,
                        ) {
                            clear()
                            onMultiBookmarkClicked(selectedEpisodes, false)
                        },
                    )
                    add(
                        DetailAction(fillerLabel, TitleDetailsIcons.Info) {
                            clear()
                            onMultiFillermarkClicked(selectedEpisodes, true)
                        },
                    )
                    add(
                        DetailAction(androidStringResource(R.string.detail_remove_filler), TitleDetailsIcons.Info) {
                            clear()
                            onMultiFillermarkClicked(selectedEpisodes, false)
                        },
                    )
                    if (selected.any { it.isDownloaded }) {
                        add(
                            DetailAction(stringResource(MR.strings.action_delete), TitleDetailsIcons.Trash) {
                                clear()
                                onMultiDeleteClicked(selectedEpisodes)
                            },
                        )
                    }
                    if (selected.size == 1) {
                        val episode = selected.single().episode
                        add(
                            DetailAction(
                                androidStringResource(R.string.detail_previous_watched),
                                TitleDetailsIcons.Checks,
                            ) {
                                clear()
                                onMarkPreviousAsSeenClicked(episode)
                            },
                        )
                        add(
                            DetailAction(
                                androidStringResource(
                                    if (alwaysUseExternalPlayer) {
                                        R.string.detail_internal_player
                                    } else {
                                        R.string.detail_external_player
                                    },
                                ),
                                TitleDetailsIcons.Play,
                            ) {
                                clear()
                                onEpisodeClicked(episode, true)
                            },
                        )
                        if (onShareClicked != null) {
                            add(
                                DetailAction(stringResource(MR.strings.action_share), TitleDetailsIcons.Share) {
                                    ContentShareDialog.show(
                                        context,
                                        ContentLink(
                                            medium = SharedMedium.ANIME,
                                            sourceId = anime.source,
                                            sourceName = state.source.name,
                                            entryUrl = anime.url,
                                            title = anime.title,
                                            itemUrl = episode.url,
                                            itemTitle = episode.name,
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
            if (!isSeasons) {
                episodeById[id]?.let { item ->
                    onDownloadEpisode?.let { onDownload ->
                        EpisodeDownloadIndicator(
                            detailsStyle = true,
                            enabled = true,
                            downloadStateProvider = { item.downloadState },
                            downloadProgressProvider = { item.downloadProgress },
                            onClick = { onDownload(listOf(item), it) },
                        )
                    }
                }
            } else {
                seasons.firstOrNull { it.seasonAnime.id == id }?.let { item ->
                    onContinueWatchingClicked?.let { play ->
                        eu.kanade.presentation.entries.details.DetailIcon(
                            TitleDetailsIcons.Play,
                            stringResource(MR.strings.action_resume),
                            { play(item.seasonAnime) },
                        )
                    }
                }
            }
        },
        swipe = { part, content ->
            val item = episodeById[part.id]
            if (isSeasons || item == null) {
                content()
            } else {
                SwipeableActionsBox(
                    startActions = listOfNotNull(
                        getSwipeAction(
                            episodeSwipeStartAction,
                            item.episode.seen,
                            item.episode.bookmark,
                            item.episode.fillermark,
                            item.downloadState,
                            MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            onEpisodeSwipe(
                                item,
                                episodeSwipeStartAction,
                            )
                        },
                    ),

                    endActions = listOfNotNull(
                        getSwipeAction(
                            episodeSwipeEndAction,
                            item.episode.seen,
                            item.episode.bookmark,
                            item.episode.fillermark,
                            item.downloadState,
                            MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            onEpisodeSwipe(
                                item,
                                episodeSwipeEndAction,
                            )
                        },
                    ),

                    swipeThreshold = 56.dp,
                    backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.background,
                ) { content() }
            }
        },
    )
}

private fun detailTime(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
