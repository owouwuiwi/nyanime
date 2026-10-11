package eu.kanade.tachiyomi.data.cloud.sync

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

internal enum class SyncSettingsGroup { APPEARANCE, PLAYBACK, READER, CATALOGUES, GENERAL }

internal data class PortableSetting(val group: SyncSettingsGroup, val preference: Preference<*>) {
    val key get() = preference.key()
    val default get() = preference.defaultValue()
}

/** Explicit capabilities, not a copy of SharedPreferences. New credentials never become portable by accident. */
internal class PortableSettings(store: PreferenceStore) {
    val entries: Map<String, PortableSetting> = buildList {
        fun group(group: SyncSettingsGroup, vararg values: Preference<*>) {
            values.forEach { add(PortableSetting(group, it)) }
        }
        with(UiPreferences(store)) {
            group(
                SyncSettingsGroup.APPEARANCE,
                themeMode(),
                appTheme(),
                themeDarkAmoled(),
                relativeTime(),
                dateFormat(),
                startScreen(),
                navStyle(),
            )
            group(
                SyncSettingsGroup.CATALOGUES,
                sourceHomeLogo(),
                homeCategoryOrder(),
                showMangaInOtherLanguages(),
                preferredMangaHomeSource(),
            )
            group(
                SyncSettingsGroup.GENERAL,
                autoAcknowledgeHomeUpdates(),
                lastSeenAnimeUpdateNotice(),
                lastSeenMangaUpdateNotice(),
            )
        }
        with(PlayerPreferences(store)) {
            group(
                SyncSettingsGroup.PLAYBACK, preserveWatchingPosition(),
                progressPreference(),
                showAudioShortcut(),
                showQualityShortcut(),
                allowGestures(),
                showLoadingCircle(),
                showCurrentChapter(),
                showFailedHosters(),
                showEmptyHosters(),
                playerFullscreen(),
                hideControls(),
                displayVolPer(),
                showSystemStatusBar(),
                playerTimeToDisappear(),
                lastSleepTimerMinutes(),
                panelOpacity(),
                enableSkipIntro(),
                autoSkipIntro(),
                enableNetflixStyleIntroSkip(),
                waitingTimeIntroSkip(),
                aniSkipEnabled(),
                disableAniSkipOnChapters(),
                enablePip(),
                pipEpisodeToasts(),
                pipOnExit(),
                pipReplaceWithPrevious(),
                playerSpeed(),
                speedPresets(),
                invertDuration(),
                aspectState(),
                autoplayEnabled(),
            )
            // Motion/accessibility is a deliberate appearance choice; hardware brightness/volume is excluded.
            group(SyncSettingsGroup.APPEARANCE, reduceMotion())
        }
        with(GesturePreferences(store)) {
            group(
                SyncSettingsGroup.PLAYBACK, gestureVolumeBrightness(),
                swapVolumeBrightness(),
                gestureHorizontalSeek(),
                showSeekBar(),
                defaultIntroLength(),
                skipLengthPreference(),
                playerSmoothSeek(),
                leftDoubleTapGesture(),
                centerDoubleTapGesture(),
                rightDoubleTapGesture(),
                playerLongPressAction(),
                mediaPreviousGesture(),
                mediaPlayPauseGesture(),
                mediaNextGesture(),
            )
        }
        with(SubtitlePreferences(store)) {
            group(
                SyncSettingsGroup.PLAYBACK, preferredSubLanguages(),
                subtitleWhitelist(),
                subtitleBlacklist(),
                screenshotSubtitles(),
                subtitleFont(),
                subtitleFontSize(),
                subtitleFontScale(),
                subtitleBorderSize(),
                boldSubtitles(),
                italicSubtitles(),
                textColorSubtitles(),
                borderColorSubtitles(),
                borderStyleSubtitles(),
                shadowOffsetSubtitles(),
                backgroundColorSubtitles(),
                subtitleJustification(),
                subtitlePos(),
                overrideSubsASS(),
            )
        }
        with(ReaderPreferences(store)) {
            group(
                SyncSettingsGroup.READER, pageTransitions(),
                flashOnPageChange(),
                flashDurationMillis(),
                flashPageInterval(),
                flashColor(),
                doubleTapAnimSpeed(),
                showPageNumber(),
                showReadingMode(),
                fullscreen(),
                keepScreenOn(),
                defaultReadingMode(),
                webtoonDoubleTapZoomEnabled(),
                imageScaleType(),
                zoomStart(),
                readerTheme(),
                alwaysShowChapterTransition(),
                preserveReadingPosition(),
                cropBorders(),
                navigateToPan(),
                landscapeZoom(),
                cropBordersWebtoon(),
                webtoonSidePadding(),
                readerHideThreshold(),
                skipRead(),
                skipFiltered(),
                skipDupe(),
                webtoonDisableZoomOut(),
                dualPageSplitPaged(),
                dualPageInvertPaged(),
                dualPageSplitWebtoon(),
                dualPageInvertWebtoon(),
                dualPageRotateToFit(),
                dualPageRotateToFitInvert(),
                dualPageRotateToFitWebtoon(),
                dualPageRotateToFitInvertWebtoon(),
                readWithLongTap(),
                readWithVolumeKeys(),
                readWithVolumeKeysInverted(),
                navigationModePager(),
                navigationModeWebtoon(),
                pagerNavInverted(),
                webtoonNavInverted(),
                showNavigationOverlayOnStart(),
            )
        }
        with(SourcePreferences(store)) {
            group(
                SyncSettingsGroup.CATALOGUES, tolerantSearch(),
                onlineSearchAssistance(),
                rememberAtlasSearch(),
                atlasKeyboardOnOpen(),
                liveAtlasSearch(),
                sourceDisplayMode(),
                enabledLanguages(),
                showNsfwSource(),
                migrationSortingMode(),
                migrationSortingDirection(),
                disabledAnimeSources(),
                disabledMangaSources(),
                pinnedAnimeSources(),
                pinnedMangaSources(),
                hideInAnimeLibraryItems(),
                hideInMangaLibraryItems(),
            )
        }
        with(LibraryPreferences(store)) {
            group(
                SyncSettingsGroup.GENERAL, displayMode(),
                allDisplayMode(),
                mangaSortingMode(),
                animeSortingMode(),
                showContinueViewingButton(),
                categoryTabs(),
                categoryNumberOfItems(),
                categorizedDisplaySettings(),
                hideHiddenCategoriesSettings(),
                filterIntervalCustom(),
                downloadBadge(),
                unreadBadge(),
                localBadge(),
                languageBadge(),
                newShowUpdatesCount(),
                randomAnimeSortSeed(),
                randomMangaSortSeed(),
                filterUnseen(),
                filterUnread(),
                filterStartedAnime(),
                filterStartedManga(),
                filterBookmarkedAnime(),
                filterBookmarkedManga(),
                filterCompletedAnime(),
                filterCompletedManga(),
                filterEpisodeBySeen(),
                filterChapterByRead(),
                filterEpisodeByBookmarked(),
                filterChapterByBookmarked(),
                filterEpisodeByFillermarked(),
                sortEpisodeBySourceOrNumber(),
                sortChapterBySourceOrNumber(),
                displayEpisodeByNameOrNumber(),
                displayChapterByNameOrNumber(),
                sortEpisodeByAscendingOrDescending(),
                sortChapterByAscendingOrDescending(),
                showEpisodeThumbnailPreviews(),
                showEpisodeSummaries(),
                filterSeasonByUnseen(),
                filterSeasonByStarted(),
                filterSeasonByCompleted(),
                filterSeasonByBookmarked(),
                filterSeasonByFillermarked(),
                sortSeasonBySourceOrNumber(),
                sortSeasonByAscendingOrDescending(),
                seasonDisplayGridMode(),
                seasonDownloadOverlay(),
                seasonUnseenOverlay(),
                seasonLocalOverlay(),
                seasonLangOverlay(),
                seasonContinueOverlay(),
                seasonDisplayMode(),
                swipeEpisodeStartAction(),
                swipeEpisodeEndAction(),
                swipeChapterStartAction(),
                swipeChapterEndAction(),
                markDuplicateReadChapterAsRead(),
                markDuplicateSeenEpisodeAsSeen(),
            )
        }
        with(TrackPreferences(store)) {
            group(
                SyncSettingsGroup.GENERAL,
                autoUpdateTrack(),
                trackOnAddingToLibrary(),
                showNextEpisodeAiringTime(),
                autoUpdateTrackOnMarkRead(),
                anilistScoreType(),
            )
        }
    }.associateBy { it.key }
}
