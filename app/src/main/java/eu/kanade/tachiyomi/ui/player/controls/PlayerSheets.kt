/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player.controls

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import dev.vivvvek.seeker.Segment
import eu.kanade.tachiyomi.ui.player.Anime4KMode
import eu.kanade.tachiyomi.ui.player.ArtType
import eu.kanade.tachiyomi.ui.player.Decoder
import eu.kanade.tachiyomi.ui.player.HeadphoneCinemaState
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerViewModel.VideoTrack
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.AudioTracksSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.ChaptersSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.MoreSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.PlaybackSpeedSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.QualitySheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.ScreenshotSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.SleepTimerDialog
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.SubtitlesSheet
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.custombuttons.model.CustomButton
import tachiyomi.domain.entries.anime.model.Anime
import java.io.InputStream

@Composable
fun PlayerSheets(
    anime: Anime?,
    sheetShown: Sheets,
    onOpenSheet: (Sheets) -> Unit,
    isEpisodeOnline: Boolean,

    // subtitles sheet
    subtitles: ImmutableList<VideoTrack>,
    selectedSubtitles: ImmutableList<Int>,
    onAddSubtitle: (Uri) -> Unit,
    onSelectSubtitle: (Int) -> Unit,

    // audio sheet
    audioTracks: ImmutableList<VideoTrack>,
    selectedAudio: Int,
    onAddAudio: (Uri) -> Unit,
    onSelectAudio: (Int) -> Unit,
    cinemaState: HeadphoneCinemaState,
    onCinemaSelect: (Boolean) -> Unit,

    // video sheet
    isLoadingHosters: Boolean,
    hosterState: List<HosterState>,
    expandedState: List<Boolean>,
    selectedVideoIndex: Pair<Int, Int>,
    onClickHoster: (Int) -> Unit,
    onClickVideo: (Int, Int) -> Unit,
    displayHosters: Pair<Boolean, Boolean>,

    // chapters sheet
    chapter: Segment?,
    chapters: ImmutableList<Segment>,
    onSeekToChapter: (Int) -> Unit,

    // Decoders sheet
    decoder: Decoder,
    onUpdateDecoder: (Decoder) -> Unit,

    // Speed sheet
    speed: Float,
    onSpeedChange: (Float) -> Unit,

    // More sheet
    sleepTimerTimeRemaining: Int,
    onStartSleepTimer: (Int) -> Unit,
    onStartCustomSleepTimer: (Int) -> Unit,
    onEndSleepTimer: () -> Unit,
    sleepTimerAtEpisodeEnd: Boolean,
    lastCustomTimerMinutes: Int,
    onExtendSleepTimer: (Int) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenWatchTogether: () -> Unit,
    onShareContent: (() -> Unit)?,
    reduceMotion: Boolean,
    buttons: ImmutableList<CustomButton>,
    onSelectAnime4KCustom: (Anime4KMode) -> Unit,
    anime4kAvailable: Boolean,

    // Screenshot sheet
    isLocalSource: Boolean,
    showSubtitles: Boolean,
    onToggleShowSubtitles: (Boolean) -> Unit,
    cachePath: String,
    onSetAsArt: (ArtType, (() -> InputStream)) -> Unit,
    onShare: (() -> InputStream) -> Unit,
    onSave: (() -> InputStream) -> Unit,
    takeScreenshot: (String, Boolean) -> InputStream?,
    onDismissScreenshot: () -> Unit,

    onOpenPanel: (Panels) -> Unit,
    onDismissRequest: () -> Unit,
    dismissSheet: Boolean,
) {
    when (sheetShown) {
        Sheets.WatchTogether -> eu.kanade.tachiyomi.ui.watch.WatchTogetherSheet(onDismissRequest)
        Sheets.None -> {}
        Sheets.SubtitleTracks -> {
            val subtitlesPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) {
                if (it == null) return@rememberLauncherForActivityResult
                onAddSubtitle(it)
            }
            SubtitlesSheet(
                tracks = subtitles.toImmutableList(),
                selectedTracks = selectedSubtitles,
                onSelect = onSelectSubtitle,
                onAddSubtitle = { subtitlesPicker.launch(arrayOf("*/*")) },
                onOpenSubtitleSettings = { onOpenPanel(Panels.SubtitleSettings) },
                onOpenSubtitleDelay = { onOpenPanel(Panels.SubtitleDelay) },
                onDismissRequest = onDismissRequest,
            )
        }

        Sheets.AudioTracks -> {
            val audioPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) {
                if (it == null) return@rememberLauncherForActivityResult
                onAddAudio(it)
            }
            AudioTracksSheet(
                tracks = audioTracks,
                cinemaState = cinemaState,
                onCinemaSelect = onCinemaSelect,
                selectedId = selectedAudio,
                onSelect = onSelectAudio,
                onAddAudioTrack = { audioPicker.launch(arrayOf("*/*")) },
                onOpenDelayPanel = { onOpenPanel(Panels.AudioDelay) },
                onDismissRequest = onDismissRequest,
            )
        }

        Sheets.QualityTracks -> {
            QualitySheet(
                isLoadingHosters = isLoadingHosters,
                hosterState = hosterState,
                expandedState = expandedState,
                selectedVideoIndex = selectedVideoIndex,
                onClickHoster = onClickHoster,
                onClickVideo = onClickVideo,
                displayHosters = displayHosters,
                onDismissRequest = onDismissRequest,
                dismissSheet = dismissSheet,
            )
        }

        Sheets.Chapters -> {
            if (chapter == null) return
            ChaptersSheet(
                chapters = chapters,
                currentChapter = chapter,
                onClick = { onSeekToChapter(chapters.indexOf(it)) },
                onDismissRequest = onDismissRequest,
                dismissSheet = dismissSheet,
            )
        }

        Sheets.SleepTimer -> SleepTimerDialog(
            remainingTime = sleepTimerTimeRemaining,
            onStartTimer = onStartSleepTimer,
            onStartCustomTimer = onStartCustomSleepTimer,
            onEndTimer = onEndSleepTimer,
            atEpisodeEnd = sleepTimerAtEpisodeEnd,
            initialCustomMinutes = lastCustomTimerMinutes,
            onExtendTimer = onExtendSleepTimer,
            onDismissRequest = onDismissRequest,
            reduceMotion = reduceMotion,
        )
        Sheets.More -> {
            MoreSheet(
                anime = anime,
                onOpenAudio = { onOpenSheet(Sheets.AudioTracks) },
                onOpenQuality = { onOpenSheet(Sheets.QualityTracks) },
                onOpenScreenshot = { onOpenSheet(Sheets.Screenshot) },
                qualityAvailable = isEpisodeOnline,
                selectedDecoder = decoder,
                onSelectDecoder = onUpdateDecoder,
                remainingTime = sleepTimerTimeRemaining,
                timerAtEpisodeEnd = sleepTimerAtEpisodeEnd,
                onOpenSleepTimer = onOpenSleepTimer,
                onOpenWatchTogether = onOpenWatchTogether,
                onShareContent = onShareContent,
                onDismissRequest = onDismissRequest,
                onEnterFiltersPanel = { onOpenPanel(Panels.VideoFilters) },
                customButtons = buttons,
                onSelectAnime4KCustom = onSelectAnime4KCustom,
                anime4kAvailable = anime4kAvailable,
            )
        }

        Sheets.PlaybackSpeed -> {
            PlaybackSpeedSheet(
                speed,
                onSpeedChange = onSpeedChange,
                onDismissRequest = onDismissRequest,
            )
        }

        Sheets.Screenshot -> {
            ScreenshotSheet(
                isLocalSource = isLocalSource,
                hasSubTracks = subtitles.isNotEmpty(),
                showSubtitles = showSubtitles,
                onToggleShowSubtitles = onToggleShowSubtitles,
                cachePath = cachePath,
                onSetAsArt = onSetAsArt,
                onShare = onShare,
                onSave = onSave,
                takeScreenshot = takeScreenshot,
                onDismissRequest = onDismissScreenshot,
            )
        }
    }
}
