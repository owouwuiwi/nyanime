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

/**
 * Code is a mix between PlayerViewModel from mpvKt and the former
 * PlayerViewModel from Aniyomi.
 */

package eu.kanade.tachiyomi.ui.player

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.entries.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.items.episode.model.toDbEpisode
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.screen.player.custombutton.CustomButtonFetchState
import eu.kanade.presentation.more.settings.screen.player.custombutton.getButtons
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.TileInfo
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.data.database.models.anime.Episode
import eu.kanade.tachiyomi.data.database.models.anime.isRecognizedNumber
import eu.kanade.tachiyomi.data.database.models.anime.toDomainEpisode
import eu.kanade.tachiyomi.data.discovery.EpisodeEndingCue
import eu.kanade.tachiyomi.data.discovery.EpisodeEndingCueStore
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.track.AniListMediaLookup
import eu.kanade.tachiyomi.data.track.AutoTrackOnStart
import eu.kanade.tachiyomi.data.track.AutoTrackingAttempt
import eu.kanade.tachiyomi.data.track.AutoTrackingRetry
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.watch.WatchMedia
import eu.kanade.tachiyomi.data.watch.WatchPlayback
import eu.kanade.tachiyomi.data.watch.WatchPlayer
import eu.kanade.tachiyomi.data.watch.WatchProblem
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import eu.kanade.tachiyomi.ui.player.controls.components.IndexedSegment
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.getChangedAt
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.utils.AniSkipApi
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils.Companion.getStringRes
import eu.kanade.tachiyomi.ui.player.utils.TrackSelect
import eu.kanade.tachiyomi.ui.reader.SaveImageNotifier
import eu.kanade.tachiyomi.util.editBackground
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.editThumbnail
import eu.kanade.tachiyomi.util.episode.filterDownloadedEpisodes
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.lang.takeBytes
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.system.toast
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils
import it.lorelore.nyanime.syncplay.SyncplayManager
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.custombuttons.interactor.GetCustomButtons
import tachiyomi.domain.custombuttons.model.CustomButton
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.history.anime.interactor.GetNextEpisodes
import tachiyomi.domain.history.anime.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.interactor.UpdateEpisode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.items.episode.service.getEpisodeSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.source.local.entries.anime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class PlayerViewModelProviderFactory(
    private val activity: PlayerSession,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return PlayerViewModel(activity, extras.createSavedStateHandle()) as T
    }
}

class PlayerViewModel @JvmOverloads constructor(
    private val activity: PlayerSession,
    private val savedState: SavedStateHandle,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val downloadManager: AnimeDownloadManager = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackEpisode: TrackEpisode = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getNextEpisodes: GetNextEpisodes = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val getTracks: GetAnimeTracks = Injekt.get(),
    private val upsertHistory: UpsertAnimeHistory = Injekt.get(),
    private val updateEpisode: UpdateEpisode = Injekt.get(),
    private val endingCues: EpisodeEndingCueStore = Injekt.get(),
    private val setAnimeViewerFlags: SetAnimeViewerFlags = Injekt.get(),
    internal val playerPreferences: PlayerPreferences = Injekt.get(),
    internal val gesturePreferences: GesturePreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val getCustomButtons: GetCustomButtons = Injekt.get(),
    private val trackSelect: TrackSelect = Injekt.get(),
    private val getIncognitoState: GetAnimeIncognitoState = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    uiPreferences: UiPreferences = Injekt.get(),
) : ViewModel() {
    private val scrubCoordinator = PlaybackScrubCoordinator()
    internal val scrubState = scrubCoordinator.state
    private var finishingScrubPause: Boolean? = null

    private val _currentPlaylist = MutableStateFlow<List<Episode>>(emptyList())
    val currentPlaylist = _currentPlaylist.asStateFlow()

    private val _hasPreviousEpisode = MutableStateFlow(false)
    val hasPreviousEpisode = _hasPreviousEpisode.asStateFlow()

    private val _hasNextEpisode = MutableStateFlow(false)
    val hasNextEpisode = _hasNextEpisode.asStateFlow()

    private val _currentEpisode = MutableStateFlow<Episode?>(null)

    // Survives a remote disconnect until a fresh local file is loaded. onCleared must not save stale MPV progress.
    @Volatile var remoteProgressOwned = false
    val currentEpisode = _currentEpisode.asStateFlow()

    private val episodeGuides by lazy { eu.kanade.tachiyomi.data.episodeguide.EpisodeGuideRepository.get(activity) }
    private val _skippedFillers = MutableStateFlow(0)
    val skippedFillers = _skippedFillers.asStateFlow()
    fun clearSkippedFillers() {
        _skippedFillers.value = 0
    }

    private fun nextGuidedEpisode(): Pair<Episode?, Int> {
        val anime = currentAnime.value
        val skip =
            anime != null &&
                episodeGuides.shouldSkip(anime) &&
                (!watchTogether.active || watchTogether.state.value.host)
        return eu.kanade.tachiyomi.data.episodeguide.EpisodeGuidePolicy.next(
            currentPlaylist.value,
            getCurrentEpisodeIndex(),
            skip,
        ) {
            anime?.let { title ->
                episodeGuides.classification(title, it.url, it.episode_number.toDouble(), it.fillermark)
            }
                ?: eu.kanade.tachiyomi.data.episodeguide.EpisodeClassification.UNKNOWN
        }
    }
    private fun notifySkippedFillers() {
        _skippedFillers.value = nextGuidedEpisode().second
    }

    private val _currentAnime = MutableStateFlow<Anime?>(null)
    val currentAnime = _currentAnime.asStateFlow()

    private val _currentSource = MutableStateFlow<AnimeSource?>(null)
    val currentSource = _currentSource.asStateFlow()

    private val _isEpisodeOnline = MutableStateFlow(false)
    val isEpisodeOnline = _isEpisodeOnline.asStateFlow()

    private val _isLoadingEpisode = MutableStateFlow(false)
    val isLoadingEpisode = _isLoadingEpisode.asStateFlow()

    private val _currentDecoder = MutableStateFlow(getDecoderFromValue(MPVLib.getPropertyString("hwdec")))
    val currentDecoder = _currentDecoder.asStateFlow()

    val mediaTitle = MutableStateFlow("")
    val animeTitle = MutableStateFlow("")

    val isLoading = MutableStateFlow(true)
    val playbackLoad = PlaybackLoadMonitor(viewModelScope, { activity.onPlaybackFailure(PlaybackFailure.Timeout) })
    val playbackLoadState = playbackLoad.state
    val playbackSpeed = MutableStateFlow(playerPreferences.playerSpeed().get())

    private val holdSpeed = PlayerHoldSpeed(
        available = { !activity.player.isExiting && !activity.isDestroyed },
        readSpeed = { MPVLib.getPropertyDouble("speed") ?: playbackSpeed.value.toDouble() },
        writeSpeed = { MPVLib.setPropertyDouble("speed", it) },
    )

    val watchManager by lazy { WatchTogetherManager.get(activity) }
    val watchTogether get() = watchManager.controller

    private fun readWatchTiming(): PlayerWatchTiming {
        val load = playbackLoadState.value
        return PlayerWatchTiming.read(
            available = load.started && !load.opening && !isLoadingEpisode.value && !activity.player.isExiting,
            readDouble = MPVLib::getPropertyDouble,
        )
    }

    fun bindWatchPlayer() {
        watchManager.attachPlayback(
            activity,
            object : WatchPlayer {
                override fun sample(): WatchPlayback {
                    val anime = currentAnime.value
                    val episode = currentEpisode.value
                    var load = playbackLoadState.value
                    val timing = readWatchTiming()
                    val duration = timing.duration
                    val position = timing.position
                    val bufferedAhead = if (!watchTogether.active || activity.player.isExiting) {
                        null
                    } else if (!_isEpisodeOnline.value) {
                        120.0 // A downloaded episode does not need network prebuffering.
                    } else {
                        runCatching { MPVLib.getPropertyDouble("demuxer-cache-time") }
                            .getOrNull()
                            ?.takeIf { it.isFinite() && it >= position }
                            ?.let { (it - position).coerceIn(0.0, 120.0) }
                    }
                    if (timing.ready &&
                        !isLoadingEpisode.value &&
                        !isSeeking.value &&
                        playbackLoad.recoverPausedCache(
                            paused.value,
                            runCatching { MPVLib.getPropertyBoolean("seeking") }.getOrNull(),
                            bufferedAhead,
                            duration - position,
                        )
                    ) {
                        load = playbackLoadState.value
                        isLoading.value = load.loading
                    }
                    val media = if (anime != null && episode != null) {
                        WatchMedia(
                            anime.title.take(240),
                            episode.name.take(240),
                            episode.episode_number.toDouble(),
                            duration,
                            anime.source,
                            anime.url,
                            episode.url,
                        )
                    } else {
                        null
                    }
                    val scrub = scrubState.value
                    return WatchPlayback(
                        media = media,
                        position = if (scrub != null) scrubCoordinator.clockPosition().toDouble() else position,
                        paused = scrub?.paused ?: finishingScrubPause ?: paused.value,
                        ready = timing.ready &&
                            load.started &&
                            load.failure == null &&
                            !load.opening &&
                            !load.seeking &&
                            !isLoadingEpisode.value &&
                            (!isSeeking.value || scrub != null) &&
                            !activity.player.isExiting,
                        buffering = load.buffering || load.seeking || (isSeeking.value && scrub == null),
                        scrubbing = scrub != null,
                        speed = scrub?.speed?.toDouble() ?: holdSpeed.originalSpeed ?: playbackSpeed.value.toDouble(),
                        problem = if (load.failure != null) WatchProblem.SourceError else WatchProblem.None,
                        upcoming = if (timing.ready && duration - position <= 90) watchNextMedia() else null,
                        canAdvance = timing.ready && canWatchAdvance(),
                        ended = timing.ready &&
                            runCatching { MPVLib.getPropertyBoolean("eof-reached") == true }.getOrDefault(false),
                        bufferedAheadSeconds = bufferedAhead,
                    )
                }
                override fun pause(paused: Boolean) {
                    if (scrubCoordinator.active) {
                        scrubCoordinator.playbackIntent(paused)
                        return
                    }
                    if (finishingScrubPause != null) {
                        finishingScrubPause = paused
                        return
                    }
                    setScrubPaused(paused)
                }
                override fun seek(seconds: Double) {
                    cancelPlaybackScrub()
                    if (!activity.player.isExiting) {
                        completion.playbackRestarted()
                        MPVLib.command(arrayOf("seek", seconds.toString(), "absolute+exact"))
                    }
                }
                override fun speed(value: Double) {
                    if (!activity.player.isExiting) MPVLib.setPropertyDouble("speed", value)
                }
                override fun userResumed() {
                    sleepTimer.acknowledgeUserPlayback()
                }
                override fun advance(media: WatchMedia) {
                    val target = getAdjacentEpisodeId(previous = false)
                    if (watchTogether.state.value.host &&
                        canWatchAdvance() &&
                        watchNextMedia()?.key == media.key &&
                        target >= 0
                    ) {
                        notifySkippedFillers()
                        activity.changeEpisode(target, autoPlay = true, fromWatchRoom = true)
                    }
                }
            },
        ) { animeId, episodeId ->
            if (currentAnime.value?.id == animeId) {
                activity.changeEpisode(episodeId, autoPlay = true, fromWatchRoom = true)
            } else {
                activity.openWatchVideo(animeId, episodeId)
            }
        }
    }

    fun onNativePause(paused: Boolean) {
        val effective = paused || scrubCoordinator.active || watchTogether.active && watchTogether.expectsPaused
        if (effective) endHoldSpeed()
        _paused.value = effective
        if (effective != paused) activity.player.paused = effective
    }

    fun pauseByUser() {
        cancelPlaybackScrub(restorePlayback = false)
        it.lorelore.nyanime.attention.core.AttentionControl.externalCommand()
        if (SyncplayManager.existing()?.requestPause(true) == true) return
        if (!watchTogether.requestPause(true)) pause()
    }

    fun setPlaybackSpeedByUser(speed: Double) {
        if (SyncplayManager.isActive()) {
            activity.showToast(activity.getString(eu.kanade.tachiyomi.R.string.syncplay_speed_fixed))
            return
        }
        endHoldSpeed()
        if (!watchTogether.requestSpeed(speed)) MPVLib.setPropertyDouble("speed", speed)
    }

    fun beginHoldSpeed(speed: Double): Boolean {
        if (watchTogether.active || SyncplayManager.isActive()) {
            playerUpdate.value = PlayerUpdates.ShowTextResource(AYMR.strings.player_hold_speed_unavailable_in_room)
            return false
        }
        val cast = activity.castController.state.value
        if (paused.value ||
            isLoading.value ||
            isLoadingEpisode.value ||
            areControlsLocked.value ||
            cast.active ||
            cast.connecting ||
            sheetShown.value != Sheets.None ||
            dialogShown.value != Dialogs.None ||
            panelShown.value != Panels.None
        ) {
            return false
        }
        if (!holdSpeed.start(speed)) return false
        hideControls()
        playerUpdate.value = PlayerUpdates.HoldSpeed(speed)
        return true
    }

    fun endHoldSpeed() {
        holdSpeed.finish()
        playerUpdate.update { if (it is PlayerUpdates.HoldSpeed) PlayerUpdates.None else it }
    }

    private val _subtitleTracks = MutableStateFlow<List<VideoTrack>>(emptyList())
    val subtitleTracks = _subtitleTracks.asStateFlow()
    private val _selectedSubtitles = MutableStateFlow(Pair(-1, -1))
    val selectedSubtitles = _selectedSubtitles.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<VideoTrack>>(emptyList())
    val audioTracks = _audioTracks.asStateFlow()
    private val _selectedAudio = MutableStateFlow(-1)
    val selectedAudio = _selectedAudio.asStateFlow()

    val isLoadingTracks = MutableStateFlow(true)

    private val _hosterList = MutableStateFlow<List<Hoster>>(emptyList())
    val hosterList = _hosterList.asStateFlow()
    private val _isLoadingHosters = MutableStateFlow(true)
    val isLoadingHosters = _isLoadingHosters.asStateFlow()
    private val _hosterState = MutableStateFlow<List<HosterState>>(emptyList())
    val hosterState = _hosterState.asStateFlow()
    private val _hosterExpandedList = MutableStateFlow<List<Boolean>>(emptyList())
    val hosterExpandedList = _hosterExpandedList.asStateFlow()
    private val _selectedHosterVideoIndex = MutableStateFlow(Pair(-1, -1))
    val selectedHosterVideoIndex = _selectedHosterVideoIndex.asStateFlow()
    private val _currentVideo = MutableStateFlow<Video?>(null)
    val currentVideo = _currentVideo.asStateFlow()

    private val _chapters = MutableStateFlow<List<IndexedSegment>>(emptyList())
    val chapters = _chapters.asStateFlow()
    private val _currentChapter = MutableStateFlow<IndexedSegment?>(null)
    val currentChapter = _currentChapter.asStateFlow()
    private val _skipIntroText = MutableStateFlow<String?>(null)
    val skipIntroText = _skipIntroText.asStateFlow()

    private val _pos = MutableStateFlow(0f)
    val pos = _pos.asStateFlow()

    private val seekFeedback = PlaybackSeekFeedback(viewModelScope)
    val timelinePosition = seekFeedback.position

    private val _seekPosition = MutableStateFlow(0f)
    val seekPosition = _seekPosition.asStateFlow()

    private val _isSeeking = MutableStateFlow(false)
    val isSeeking = _isSeeking.asStateFlow()

    private val _thumbnailImage = MutableStateFlow<ImageBitmap?>(null)
    val thumbnailImage = _thumbnailImage.asStateFlow()

    private val thumbnailInfo = MutableStateFlow<ThumbnailInfo?>(null)
    internal val hasSeekThumbnails: Boolean get() = thumbnailInfo.value != null
    private val thumbnailTileCache =
        object : LinkedHashMap<Int, Bitmap>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) = size > 3
        }
    private var thumbnailFetchJob: Job? = null

    val duration = MutableStateFlow(0f)

    private val _readAhead = MutableStateFlow(0f)
    val readAhead = _readAhead.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()

    // False because the video shouldn't start paused
    private val _pausedState = MutableStateFlow<Boolean?>(false)
    val pausedState = _pausedState.asStateFlow()

    private val _controlsShown = MutableStateFlow(!playerPreferences.hideControls().get())
    val controlsShown = _controlsShown.asStateFlow()
    private val _seekBarShown = MutableStateFlow(!playerPreferences.hideControls().get())
    val seekBarShown = _seekBarShown.asStateFlow()
    private val _areControlsLocked = MutableStateFlow(false)
    val areControlsLocked = _areControlsLocked.asStateFlow()

    val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
    val isBrightnessSliderShown = MutableStateFlow(false)
    val isVolumeSliderShown = MutableStateFlow(false)
    val currentBrightness = MutableStateFlow(
        runCatching {
            Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                .normalize(0f, 255f, 0f, 1f)
        }.getOrElse { 0f },
    )
    private var volumeCurve = activity.audioManager.mediaVolumeCurve()
    val currentVolume = MutableStateFlow(activity.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    val currentMPVVolume = MutableStateFlow(MPVLib.getPropertyDouble("volume") ?: 100.0)
    val currentVolumeLevel = MutableStateFlow(volumeCurve.level(currentVolume.value, currentMPVVolume.value))
    val volumeBoostCap = MutableStateFlow((MPVLib.getPropertyInt("volume-max") - 100).coerceAtLeast(0))

    // Pair(startingPosition, seekAmount)
    val gestureSeekAmount = MutableStateFlow<Pair<Int, Int>?>(null)

    val sheetShown = MutableStateFlow(Sheets.None)
    val panelShown = MutableStateFlow(Panels.None)
    val dialogShown = MutableStateFlow<Dialogs>(Dialogs.None)

    private val _dismissSheet = MutableStateFlow(false)
    val dismissSheet = _dismissSheet.asStateFlow()

    private val _seekText = MutableStateFlow<String?>(null)
    val seekText = _seekText.asStateFlow()
    private val _doubleTapSeekAmount = MutableStateFlow(0)
    val doubleTapSeekAmount = _doubleTapSeekAmount.asStateFlow()
    private val _isSeekingForwards = MutableStateFlow(false)
    val isSeekingForwards = _isSeekingForwards.asStateFlow()

    private val completion = PlayerPlaybackCompletion(
        scope = viewModelScope,
        nowMillis = android.os.SystemClock::elapsedRealtime,
        canAdvance = ::canAutoAdvance,
        onPlayNext = { nextId ->
            if (getAdjacentEpisodeId(previous = false) == nextId) {
                notifySkippedFillers()
                activity.changeEpisode(nextId, autoPlay = true)
            }
        },
    )
    val nextEpisodePrompt = completion.prompt

    private val sleepTimer = PlayerSleepTimer(viewModelScope, android.os.SystemClock::elapsedRealtime) {
        completion.cancel()
        watchTogether.hold()
        pause()
        Injekt.get<Application>().toast(AYMR.strings.toast_sleep_timer_ended)
    }
    val remainingTime = sleepTimer.remainingTime
    val sleepTimerEndEpisode = sleepTimer.endEpisodeId

    private fun watchNextMedia(): WatchMedia? {
        val anime = currentAnime.value ?: return null
        val id = getAdjacentEpisodeId(previous = false)
        val next = currentPlaylist.value.firstOrNull { it.id == id } ?: return null
        return WatchMedia(
            anime.title.take(240),
            next.name.take(240),
            next.episode_number.toDouble(),
            0.0,
            anime.source,
            anime.url,
            next.url,
        )
    }

    private fun canWatchAdvance(): Boolean {
        val currentId = currentEpisode.value?.id ?: return false
        val cast = CastController.get(activity.applicationContext).state.value
        return !activity.player.isExiting &&
            !remoteProgressOwned &&
            !cast.active &&
            !cast.connecting &&
            !isLoadingEpisode.value &&
            sheetShown.value in listOf(Sheets.None, Sheets.WatchTogether) &&
            panelShown.value == Panels.None &&
            dialogShown.value == Dialogs.None &&
            sleepTimer.allowsAutoPlay(currentId) &&
            (!watchTogether.state.value.host || playerPreferences.autoplayEnabled().get())
    }

    private fun canAutoAdvance(): Boolean {
        val currentId = currentEpisode.value?.id ?: return false
        val cast = CastController.get(activity.applicationContext).state.value
        return !activity.player.isExiting &&
            (!watchTogether.active || watchTogether.state.value.host) &&
            !remoteProgressOwned &&
            !cast.active &&
            !cast.connecting &&
            !isLoadingEpisode.value &&
            playerPreferences.autoplayEnabled().get() &&
            sheetShown.value == Sheets.None &&
            panelShown.value == Panels.None &&
            dialogShown.value == Dialogs.None &&
            sleepTimer.allowsAutoPlay(currentId)
    }

    fun onPlaybackEof(reached: Boolean) {
        if (!reached) {
            completion.playbackRestarted()
            return
        }
        val currentId = currentEpisode.value?.id ?: return
        if (isLoadingEpisode.value || remoteProgressOwned || !playbackLoadState.value.canSaveProgress) return
        sleepTimer.onEpisodeEnded(currentId)
        if (watchTogether.active) {
            completion.cancel()
            hideControls()
            return
        }
        completion.onEnded(
            nextEpisodeId = getAdjacentEpisodeId(previous = false).takeIf { it >= 0 },
            autoPlay = playerPreferences.autoplayEnabled().get(),
        )
        if (nextEpisodePrompt.value != null) hideControls()
    }

    fun cancelNextEpisode() {
        if (watchTogether.active) watchTogether.cancelNext() else completion.cancel()
    }

    fun playNextEpisodeNow() {
        if (watchTogether.active) watchTogether.playNextNow() else completion.playNow()
    }

    internal fun mediaReloaded() {
        completion.playbackRestarted()
        if (!watchTogether.active) {
            updatePausedState()
            activity.player.paused = true
        }
        trackLoadingJob?.cancel()
        audioSelectionJob?.cancel()
        subtitleSelectionJob?.cancel()
        audioSelectionVersion++
        subtitleSelectionVersion++
        trackGeneration = -1
        nativeTracks = emptyList()
        trackCatalog = PlaybackTrackCatalog()
        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
        _selectedAudio.value = -1
        _selectedSubtitles.value = -1 to -1
        isLoadingTracks.value = true
    }

    fun prepareMediaChange(episodeId: Long?) {
        qualityGeneration++
        qualityJob?.cancel()
        seekFeedback.reset()
        completion.playbackRestarted()
        if (episodeId != null && episodeId != currentEpisode.value?.id) sleepTimer.onEpisodeChanged(episodeId)
    }

    val cachePath: String = activity.cacheDir.path

    private val _customButtons = MutableStateFlow<CustomButtonFetchState>(CustomButtonFetchState.Loading)
    val customButtons = _customButtons.asStateFlow()

    private val _primaryButtonTitle = MutableStateFlow("")
    val primaryButtonTitle = _primaryButtonTitle.asStateFlow()

    private val _primaryButton = MutableStateFlow<CustomButton?>(null)
    val primaryButton = _primaryButton.asStateFlow()

    init {
        viewModelScope.launchIO {
            try {
                val buttons = getCustomButtons.getAll()
                buttons.firstOrNull { it.isFavorite }?.let {
                    _primaryButton.update { _ -> it }
                    // If the button text is not empty, it has been set buy a lua script in which
                    // case we don't want to override it
                    if (_primaryButtonTitle.value.isEmpty()) {
                        setPrimaryCustomButtonTitle(it)
                    }
                }
                activity.setupCustomButtons(buttons)
                _customButtons.update { _ -> CustomButtonFetchState.Success(buttons.toImmutableList()) }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _customButtons.update { _ -> CustomButtonFetchState.Error(e.message ?: "Unable to fetch buttons") }
            }
        }
    }

    /**
     * Starts a sleep timer/cancels the current timer if [seconds] is less than 1.
     */
    fun startTimer(seconds: Int) {
        sleepTimer.start(seconds)
    }

    fun startCustomTimer(seconds: Int) {
        if (seconds % 60 != 0 || seconds / 60 !in 1..1439) return
        playerPreferences.lastSleepTimerMinutes().set(seconds / 60)
        startTimer(seconds)
    }

    fun stopAtEpisodeEnd() {
        val currentId = currentEpisode.value?.id ?: return
        sleepTimer.stopAtEpisodeEnd(currentId)
        completion.cancel()
        if (completion.atEnd) sleepTimer.onEpisodeEnded(currentId)
    }

    fun extendTimer(seconds: Int) {
        sleepTimer.extend(seconds)
    }

    fun isEpisodeOnline(): Boolean? {
        val anime = currentAnime.value ?: return null
        val episode = currentEpisode.value ?: return null
        val source = currentSource.value ?: return null
        return source is AnimeHttpSource &&
            !EpisodeLoader.isDownload(
                episode.toDomainEpisode()!!,
                anime,
            )
    }

    fun updateIsLoadingEpisode(value: Boolean) {
        if (value) cancelPlaybackScrub(restorePlayback = false)
        _isLoadingEpisode.update { _ -> value }
    }

    fun onPlaybackFailed(reason: PlaybackFailure) {
        cancelPlaybackScrub(restorePlayback = false)
        endHoldSpeed()
        playbackLoad.fail(reason)
        isLoading.value = false
        updateIsLoadingEpisode(false)
        updateIsLoadingHosters(false)
        isLoadingTracks.value = false
        cancelHosterVideoLinksJob()
        completion.cancel()
        showControls()
    }

    fun retryPlayback() {
        activity.changeEpisode(currentEpisode.value?.id)
    }

    private fun updateEpisodeList(episodeList: List<Episode>) {
        _currentPlaylist.update { _ -> filterEpisodeList(episodeList) }
    }

    fun getDecoder() {
        _currentDecoder.update { getDecoderFromValue(activity.player.hwdecActive) }
    }

    fun updateDecoder(decoder: Decoder) {
        MPVLib.setPropertyString("hwdec", decoder.value)
    }

    private var trackCatalog = PlaybackTrackCatalog()
    private var nativeTracks = emptyList<NativePlaybackTrack>()
    private var trackGeneration = -1L
    private var trackLoadingJob: Job? = null
    private var trackRefreshPending = false
    private var audioSelectionJob: Job? = null
    private var subtitleSelectionJob: Job? = null
    private var audioSelectionVersion = 0L
    private var subtitleSelectionVersion = 0L

    private fun tracksCurrent(generation: Long): Boolean = activity.available &&
        !activity.player.isExiting &&
        trackGeneration == generation &&
        activity.playbackGeneration == generation

    private fun publishTracks(tracks: List<NativePlaybackTrack>) {
        nativeTracks = tracks
        fun choices(kind: PlaybackTrackKind) = trackCatalog.choices(kind, tracks).mapIndexed { index, track ->
            VideoTrack(track.id, track.title, track.language, displayId = index + 1)
        }
        val audio = choices(PlaybackTrackKind.Audio)
        val offLabel = if (audio.isEmpty()) {
            activity.getString(R.string.playback_no_audio_tracks)
        } else {
            activity.stringResource(MR.strings.off)
        }
        _audioTracks.value = listOf(VideoTrack(-1, offLabel, null)) + audio
        _subtitleTracks.value = choices(PlaybackTrackKind.Subtitle)
    }

    /** Track-list events update metadata only; they never reset a manual language selection. */
    fun loadTracks() {
        trackRefreshPending = true
        if (trackLoadingJob?.isActive == true) return
        val generation = activity.playbackGeneration
        trackLoadingJob = viewModelScope.launch {
            do {
                trackRefreshPending = false
                if (!tracksCurrent(generation)) return@launch
                val snapshot = activity.readPlaybackTracks(generation) ?: return@launch
                if (!tracksCurrent(generation)) return@launch
                publishTracks(snapshot)
            } while (trackRefreshPending)
        }
    }

    /** Open the selected audio before starting; leave all unused remote alternatives unopened. */
    internal suspend fun preparePlaybackTracks(audio: List<Track>, subtitles: List<Track>) {
        val generation = activity.playbackGeneration
        trackLoadingJob?.cancelAndJoin()
        trackGeneration = generation
        trackCatalog = PlaybackTrackCatalog(
            audio.map { ExternalPlaybackTrack(it.url, it.lang) },
            subtitles.map { ExternalPlaybackTrack(it.url, it.lang) },
        )
        isLoadingTracks.value = true
        val audioVersion = audioSelectionVersion
        val subtitleVersion = subtitleSelectionVersion
        if (!watchTogether.active) {
            updatePausedState()
            activity.player.paused = true
        }
        val snapshot = activity.readPlaybackTracks(generation) ?: run {
            if (tracksCurrent(generation)) activity.onPlaybackFailure(PlaybackFailure.Unknown)
            return
        }
        if (!tracksCurrent(generation)) return
        publishTracks(snapshot)
        val preferredAudio = trackSelect.getPreferredTrackIndex(audioTracks.value, subtitle = false)
            ?: audioTracks.value.firstOrNull { it.id != -1 }
        if (preferredAudio != null) {
            val id = resolveTrack(PlaybackTrackKind.Audio, preferredAudio.id, generation)
            currentCoroutineContext().ensureActive()
            if (!tracksCurrent(generation)) return
            if (audioVersion == audioSelectionVersion) {
                if (id == null) {
                    activity.showToast(activity.getString(R.string.playback_track_unavailable))
                    activity.onPlaybackFailure(PlaybackFailure.Network)
                    return
                }
                if (activity.player.aid != id) activity.player.aid = id
            }
        }
        val preferredSubtitle = trackSelect.getPreferredTrackIndex(subtitleTracks.value)
            ?: subtitleTracks.value.firstOrNull()
        if (preferredSubtitle != null && subtitleVersion == subtitleSelectionVersion) {
            val id = resolveTrack(PlaybackTrackKind.Subtitle, preferredSubtitle.id, generation)
            currentCoroutineContext().ensureActive()
            if (!tracksCurrent(generation)) return
            if (subtitleVersion == subtitleSelectionVersion && id != null) {
                if (activity.player.sid != id) activity.player.sid = id
                if (activity.player.secondarySid != -1) activity.player.secondarySid = -1
            } else if (subtitleVersion == subtitleSelectionVersion && id == null) {
                activity.showToast(activity.getString(R.string.playback_track_unavailable))
            }
        }
        if (!tracksCurrent(generation)) return
        isLoadingTracks.value = false
        updateIsLoadingEpisode(false)
        setPausedState()
    }

    private suspend fun resolveTrack(kind: PlaybackTrackKind, id: Int, generation: Long): Int? {
        if (!tracksCurrent(generation)) return null
        if (id >= -1) return id
        val track = trackCatalog.external(kind, id) ?: return null
        trackCatalog.loadedId(kind, track.url, nativeTracks)?.let { return it }
        val snapshot = activity.loadPlaybackTrack(kind, track, generation) ?: return null
        currentCoroutineContext().ensureActive()
        if (!tracksCurrent(generation)) return null
        publishTracks(snapshot)
        return trackCatalog.loadedId(kind, track.url, snapshot)
    }

    @Immutable
    data class VideoTrack(
        val id: Int,
        val name: String,
        val language: String?,
        val displayId: Int = id,
    )

    fun loadChapters() {
        val chapters = mutableListOf<IndexedSegment>()
        val count = (MPVLib.getPropertyInt("chapter-list/count") ?: 0)
        for (i in 0 until count) {
            val title = MPVLib.getPropertyString("chapter-list/$i/title")
            val time = (MPVLib.getPropertyInt("chapter-list/$i/time") ?: continue)
            chapters.add(
                IndexedSegment(
                    name = title,
                    start = time.toFloat(),
                    index = 0,
                ),
            )
        }
        updateChapters(chapters.sortedBy { it.start })
    }

    fun updateChapters(chapters: List<IndexedSegment>) {
        _chapters.update { _ -> chapters }
    }

    fun selectChapter(index: Int) {
        val time = chapters.value.getOrNull(index)?.start ?: return
        seekTo(time.toInt())
    }

    fun updateChapter(index: Long) {
        if (chapters.value.isEmpty() || index == -1L) return
        _currentChapter.update { chapters.value.getOrNull(index.toInt()) ?: return }
    }

    fun addAudio(uri: Uri) {
        if (!activity.available) return
        val url = uri.toString()
        val isContentUri = url.startsWith("content://")
        val path = (if (isContentUri) uri.openContentFd(activity) else url)
            ?: return
        val name = if (isContentUri) uri.getFileName(activity) else null
        if (name == null) {
            MPVLib.command(arrayOf("audio-add", path, "cached"))
        } else {
            MPVLib.command(arrayOf("audio-add", path, "cached", name))
        }
    }

    fun selectAudio(id: Int) {
        val generation = activity.playbackGeneration
        val version = ++audioSelectionVersion
        audioSelectionJob?.cancel()
        audioSelectionJob = viewModelScope.launch {
            val nativeId = resolveTrack(PlaybackTrackKind.Audio, id, generation) ?: run {
                if (tracksCurrent(generation) && version == audioSelectionVersion) {
                    activity.showToast(activity.getString(R.string.playback_track_unavailable))
                }
                return@launch
            }
            if (!tracksCurrent(generation) || version != audioSelectionVersion) return@launch
            if (activity.player.aid != nativeId) activity.player.aid = nativeId
        }
    }

    fun updateAudio(id: Int) {
        _selectedAudio.update { id }
    }

    fun addSubtitle(uri: Uri) {
        if (!activity.available) return
        val url = uri.toString()
        val isContentUri = url.startsWith("content://")
        val path = (if (isContentUri) uri.openContentFd(activity) else url)
            ?: return
        val name = if (isContentUri) uri.getFileName(activity) else null
        if (name == null) {
            MPVLib.command(arrayOf("sub-add", path, "cached"))
        } else {
            MPVLib.command(arrayOf("sub-add", path, "cached", name))
        }
    }

    fun selectSub(id: Int) {
        val generation = activity.playbackGeneration
        val version = ++subtitleSelectionVersion
        subtitleSelectionJob?.cancel()
        subtitleSelectionJob = viewModelScope.launch {
            val nativeId = resolveTrack(PlaybackTrackKind.Subtitle, id, generation) ?: run {
                if (tracksCurrent(generation) && version == subtitleSelectionVersion) {
                    activity.showToast(activity.getString(R.string.playback_track_unavailable))
                }
                return@launch
            }
            if (!tracksCurrent(generation) || version != subtitleSelectionVersion) return@launch
            selectNativeSub(nativeId)
        }
    }

    private fun selectNativeSub(id: Int) {
        val selectedSubs = selectedSubtitles.value
        _selectedSubtitles.update {
            when (id) {
                selectedSubs.first -> Pair(selectedSubs.second, -1)
                selectedSubs.second -> Pair(selectedSubs.first, -1)
                else -> {
                    if (selectedSubs.first != -1) {
                        Pair(selectedSubs.first, id)
                    } else {
                        Pair(id, -1)
                    }
                }
            }
        }
        activity.player.secondarySid = _selectedSubtitles.value.second
        activity.player.sid = _selectedSubtitles.value.first
    }

    fun updateSubtitle(sid: Int, secondarySid: Int) {
        _selectedSubtitles.update { Pair(sid, secondarySid) }
    }

    fun updatePlayBackPos(pos: Float) {
        onSecondReached(pos.toInt(), duration.value.toInt())
        _pos.update { pos }
        seekFeedback.observe(pos)
    }

    internal fun nativeSeekStarted() = seekFeedback.nativeStarted()

    internal fun nativeSeekFinished(position: Float) = seekFeedback.nativeFinished(position)

    private var lastThumbnailFetch = 0L

    fun updateSeekPos(pos: Float) {
        _seekPosition.update { _ -> pos }

        val thumbInfo = thumbnailInfo.value ?: return
        val info = thumbInfo.tileInfo.lastOrNull { it.timeMs <= pos * 1000L }
        if (info != null) {
            val tileBitmap = thumbnailTileCache[info.imageIndex]
            if (tileBitmap != null) {
                createThumbnail(tileBitmap, info)
            } else {
                val now = System.currentTimeMillis()
                if (now - lastThumbnailFetch < 2.seconds.inWholeMilliseconds) return
                lastThumbnailFetch = now

                thumbnailFetchJob?.cancel()
                val requestedIdentity = scrubIdentity()
                thumbnailFetchJob = viewModelScope.launchIO {
                    val source = currentSource.value as? AnimeHttpSource ?: return@launchIO

                    try {
                        val tileUrl = thumbInfo.imageTileUrls[info.imageIndex]
                        val bitmap = source.getImageTile(tileUrl)
                        if (bitmap != null) {
                            withUIContext {
                                if (requestedIdentity != scrubIdentity()) return@withUIContext
                                thumbnailTileCache[info.imageIndex] = bitmap
                                if (scrubCoordinator.active &&
                                    kotlin.math.abs(seekPosition.value - pos) < .5f
                                ) {
                                    createThumbnail(bitmap, info)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "Failed to fetch thumbnails tiles" }
                    }
                }
            }
        }
    }

    private fun createThumbnail(tileBitmap: Bitmap, tileInfo: TileInfo) {
        if (tileBitmap.isRecycled ||
            tileInfo.x < 0 ||
            tileInfo.y < 0 ||
            tileInfo.width <= 0 ||
            tileInfo.height <= 0 ||
            tileInfo.x.toLong() + tileInfo.width > tileBitmap.width ||
            tileInfo.y.toLong() + tileInfo.height > tileBitmap.height
        ) {
            _thumbnailImage.value = null
            return
        }
        val thumbnail = Bitmap.createBitmap(tileBitmap, tileInfo.x, tileInfo.y, tileInfo.width, tileInfo.height)
        _thumbnailImage.update { _ -> thumbnail.asImageBitmap() }
    }

    private fun scrubIdentity(): String {
        val cast = CastController.get(activity.applicationContext).state.value
        val localOwner = !cast.active && !cast.connecting && !remoteProgressOwned
        return "${activity.sessionId}:${currentEpisode.value?.id}:$localOwner"
    }

    internal fun beginPlaybackScrub(): Boolean {
        if (scrubCoordinator.active) return true
        val cast = CastController.get(activity.applicationContext).state.value
        if (!activity.available ||
            activity.player.isExiting ||
            currentEpisode.value == null ||
            currentVideo.value == null ||
            isLoadingEpisode.value ||
            isLoadingTracks.value ||
            remoteProgressOwned ||
            activity.live.value != null ||
            cast.active ||
            cast.connecting
        ) {
            return false
        }
        val scrubSpeed = holdSpeed.originalSpeed?.toFloat() ?: playbackSpeed.value
        if (!scrubCoordinator.begin(
                scrubIdentity(),
                pos.value,
                duration.value,
                paused.value,
                scrubSpeed,
            )
        ) {
            return false
        }
        endHoldSpeed()
        completion.cancel()
        _isSeeking.value = true
        _thumbnailImage.value = null
        updateSeekPos(scrubState.value!!.target)
        setScrubPaused(true)
        return true
    }

    internal fun updatePlaybackScrub(target: Float) {
        if (!scrubCoordinator.active && !beginPlaybackScrub()) return
        if (scrubState.value?.identity != scrubIdentity()) {
            cancelPlaybackScrub(restorePlayback = false)
            return
        }
        scrubCoordinator.update(target)?.let(::updateSeekPos)
    }

    internal fun commitPlaybackScrub(precise: Boolean) = finishPlaybackScrub(commit = true, precise = precise)

    internal fun cancelPlaybackScrub(restorePlayback: Boolean = true) =
        finishPlaybackScrub(commit = false, restorePlayback = restorePlayback)

    private fun finishPlaybackScrub(commit: Boolean, precise: Boolean = true, restorePlayback: Boolean = true) {
        if (!scrubCoordinator.active) return
        val result = scrubCoordinator.finish(scrubIdentity(), commit, restorePlayback)
        updateIsSeeking(false)
        gestureSeekAmount.value = null
        hideSeekBar()
        thumbnailFetchJob?.cancel()
        if (result == null || !activity.available || activity.player.isExiting) return
        finishingScrubPause = result.paused
        try {
            result.target?.let { seekTo(it.toInt(), precise) }
            setScrubPaused(finishingScrubPause ?: result.paused)
        } finally {
            finishingScrubPause = null
        }
    }

    private fun setScrubPaused(value: Boolean) {
        val cast = CastController.get(activity.applicationContext).state.value
        val effective = value || cast.active || cast.connecting || remoteProgressOwned
        if (!activity.player.isExiting) activity.player.paused = effective
        _paused.value = effective
    }

    fun updateIsSeeking(value: Boolean) {
        _isSeeking.update { _ -> value }
        if (!value) {
            _thumbnailImage.update { _ -> null }
        }
    }

    fun updateReadAhead(value: Long) {
        _readAhead.update { value.toFloat() }
    }

    private fun updatePausedState() {
        if (pausedState.value == null) {
            _pausedState.update { _ -> paused.value }
        }
    }

    private fun setPausedState() {
        pausedState.value?.let {
            if (it) {
                pause()
            } else {
                unpause()
            }

            _pausedState.update { _ -> null }
        }
    }

    fun pauseUnpause() {
        completion.cancel()
        if (watchTogether.active) {
            if (watchTogether.state.value.wantsPlayback &&
                !watchTogether.state.value.localHold
            ) {
                pauseByUser()
            } else {
                resumeByUser()
            }
            return
        }
        if (paused.value) {
            resumeByUser()
        } else {
            pauseByUser()
        }
    }

    fun resumeByUser() {
        cancelPlaybackScrub(restorePlayback = false)
        it.lorelore.nyanime.attention.core.AttentionControl.externalCommand()
        completion.cancel()
        sleepTimer.acknowledgeUserPlayback()
        if (SyncplayManager.existing()?.requestPause(false) == true) return
        if (watchTogether.resumeByUser()) return
        unpause()
    }

    fun pause() {
        cancelPlaybackScrub(restorePlayback = false)
        endHoldSpeed()
        if (isLoadingTracks.value && !watchTogether.active) _pausedState.value = true
        activity.player.paused = true
        _paused.update { true }
        if (eu.kanade.tachiyomi.data.cloud.sync.CloudSyncManager.wantsProgress() &&
            playbackLoadState.value.canSaveProgress &&
            !isLoadingEpisode.value
        ) {
            saveCurrentEpisodeWatchingProgress()
        }
        runCatching {
            activity.setPictureInPictureParams(activity.createPipParams())
        }
    }

    fun unpause() {
        if (scrubCoordinator.active) return
        val cast = CastController.get(activity.applicationContext).state.value
        if (cast.active || cast.connecting) return
        if (remoteProgressOwned) {
            activity.resumeAfterCast()
            return
        }
        if (isLoadingTracks.value && !watchTogether.active) {
            _pausedState.value = false
            return
        }
        activity.player.paused = false
        _paused.update { false }
    }

    private val showStatusBar = playerPreferences.showSystemStatusBar().get()
    fun showControls() {
        if (sheetShown.value != Sheets.None ||
            panelShown.value != Panels.None ||
            dialogShown.value != Dialogs.None
        ) {
            return
        }
        if (showStatusBar) {
            activity.showStatusBars(true)
        }
        _controlsShown.update { true }
    }

    fun hideControls() {
        activity.showStatusBars(false)
        _controlsShown.update { false }
    }

    fun hideSeekBar() {
        _seekBarShown.update { false }
    }

    fun showSeekBar() {
        if (sheetShown.value != Sheets.None) return
        _seekBarShown.update { true }
    }

    fun lockControls() {
        cancelPlaybackScrub()
        _areControlsLocked.update { true }
    }

    fun unlockControls() {
        _areControlsLocked.update { false }
    }

    fun dismissSheet() {
        _dismissSheet.update { _ -> true }
    }

    private fun resetDismissSheet() {
        _dismissSheet.update { _ -> false }
    }

    fun showSheet(sheet: Sheets) {
        if (sheet != Sheets.None) cancelPlaybackScrub()
        if (sheet != Sheets.None) completion.cancel()
        sheetShown.update { sheet }
        if (sheet == Sheets.None) {
            resetDismissSheet()
            showControls()
        } else {
            hideControls()
            panelShown.update { Panels.None }
            dialogShown.update { Dialogs.None }
        }
    }

    fun showPanel(panel: Panels) {
        if (panel != Panels.None) cancelPlaybackScrub()
        if (panel != Panels.None) completion.cancel()
        panelShown.update { panel }
        if (panel == Panels.None) {
            showControls()
        } else {
            hideControls()
            sheetShown.update { Sheets.None }
            dialogShown.update { Dialogs.None }
        }
    }

    fun showDialog(dialog: Dialogs) {
        if (dialog != Dialogs.None) cancelPlaybackScrub()
        if (dialog != Dialogs.None) completion.cancel()
        dialogShown.update { dialog }
        if (dialog == Dialogs.None) {
            showControls()
        } else {
            hideControls()
            sheetShown.update { Sheets.None }
            panelShown.update { Panels.None }
        }
    }

    fun seekBy(offset: Int, precise: Boolean = false) {
        cancelPlaybackScrub()
        it.lorelore.nyanime.attention.core.AttentionControl.externalCommand()
        if (SyncplayManager.existing()?.requestSeek(readWatchTiming().position + offset) == true) return
        if (watchTogether.active) {
            val timing = readWatchTiming()
            if (timing.ready) watchTogether.requestSeek(timing.position + offset)
            return
        }
        completion.playbackRestarted()
        MPVLib.command(arrayOf("seek", offset.toString(), PlaybackSeekPolicy.flags(false, precise, hasExternalAudio())))
    }

    fun seekTo(position: Int, precise: Boolean = true) {
        cancelPlaybackScrub()
        it.lorelore.nyanime.attention.core.AttentionControl.externalCommand()
        if (SyncplayManager.existing()?.requestSeek(position.toDouble()) == true) return
        if (watchTogether.requestSeek(position.toDouble())) {
            seekFeedback.request(position.toFloat())
            return
        }
        if (position !in 0..(activity.player.duration ?: 0)) return
        seekFeedback.request(position.toFloat(), exact = precise || hasExternalAudio())
        completion.playbackRestarted()
        MPVLib.command(
            arrayOf("seek", position.toString(), PlaybackSeekPolicy.flags(true, precise, hasExternalAudio())),
        )
    }

    private fun hasExternalAudio(): Boolean = currentVideo.value?.audioTracks?.isNotEmpty() == true ||
        nativeTracks.any { it.type == "audio" && it.id == selectedAudio.value && it.externalFilename != null }

    fun changeBrightnessTo(
        brightness: Float,
    ) {
        currentBrightness.update { _ -> brightness.coerceIn(-0.75f, 1f) }
        activity.applyBrightness(brightness.coerceIn(0f, 1f))
    }

    fun displayBrightnessSlider() {
        isBrightnessSliderShown.update { true }
    }

    val maxVolume get() = activity.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun beginVolumeAdjustment(): Float {
        if (!activity.available) return currentVolumeLevel.value
        volumeCurve = activity.audioManager.mediaVolumeCurve()
        currentVolume.value = activity.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        currentMPVVolume.value = MPVLib.getPropertyDouble("volume") ?: currentMPVVolume.value
        currentVolumeLevel.value = volumeCurve.level(currentVolume.value, currentMPVVolume.value)
        return currentVolumeLevel.value
    }

    fun changeVolumeBy(change: Int) {
        val level = beginVolumeAdjustment()
        val step = if (level > 1.00001f || (level >= 0.99999f && change > 0)) 0.01f else 1f / maxVolume
        changeVolumeLevelTo(level + change * step)
    }

    fun changeVolumeLevelTo(level: Float) {
        if (!activity.available) return
        val setting = volumeCurve.setting(level, volumeBoostCap.value)
        val oldIndex = activity.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Attenuate before raising Android's level; lower Android before restoring software gain.
        // These APIs cannot be committed atomically, so neither order may produce a loud overshoot.
        if (setting.streamIndex > oldIndex) MPVLib.setPropertyDouble("volume", setting.softwareVolume)
        if (setting.streamIndex != oldIndex) {
            activity.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, setting.streamIndex, 0)
        }
        if (setting.streamIndex <= oldIndex) MPVLib.setPropertyDouble("volume", setting.softwareVolume)
        currentVolume.value = if (setting.streamIndex != oldIndex) {
            activity.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } else {
            oldIndex
        }
        setMPVVolume(MPVLib.getPropertyDouble("volume") ?: setting.softwareVolume)
    }

    fun setMPVVolume(volume: Double) {
        if (!volume.isFinite()) return
        if (volume != currentMPVVolume.value) displayVolumeSlider()
        currentMPVVolume.update { volume }
        currentVolumeLevel.value = volumeCurve.level(currentVolume.value, volume)
    }

    fun displayVolumeSlider() {
        isVolumeSliderShown.update { true }
    }

    fun setAutoPlay(value: Boolean) {
        if (!value) completion.cancel()
        val textRes = if (value) {
            AYMR.strings.enable_auto_play
        } else {
            AYMR.strings.disable_auto_play
        }
        playerUpdate.update { PlayerUpdates.ShowTextResource(textRes) }
        playerPreferences.autoplayEnabled().set(value)
    }

    @Suppress("DEPRECATION")
    fun changeVideoAspect(aspect: VideoAspect) {
        playerPreferences.aspectState().set(aspect)
        if (!activity.available) return
        activity.applySurfaceAspect()
        playerUpdate.update { PlayerUpdates.AspectRatio }
    }

    fun cycleScreenRotations() {
        val cast = activity.castController.state.value
        if (cast.active || cast.connecting) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            return
        }
        activity.requestedOrientation = when (activity.requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            -> {
                playerPreferences.defaultPlayerOrientationType().set(PlayerOrientation.SensorPortrait)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }

            else -> {
                playerPreferences.defaultPlayerOrientationType().set(PlayerOrientation.SensorLandscape)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    fun handleLuaInvocation(property: String, value: String) {
        val data = value
            .removePrefix("\"")
            .removeSuffix("\"")
            .ifEmpty { return }

        when (property.substringAfterLast("/")) {
            "show_text" -> playerUpdate.update { PlayerUpdates.ShowText(data) }
            "toggle_ui" -> {
                when (data) {
                    "show" -> showControls()
                    "toggle" -> {
                        if (controlsShown.value) hideControls() else showControls()
                    }
                    "hide" -> {
                        sheetShown.update { Sheets.None }
                        panelShown.update { Panels.None }
                        dialogShown.update { Dialogs.None }
                        hideControls()
                    }
                }
            }
            "show_panel" -> {
                when (data) {
                    "subtitle_settings" -> showPanel(Panels.SubtitleSettings)
                    "subtitle_delay" -> showPanel(Panels.SubtitleDelay)
                    "audio_delay" -> showPanel(Panels.AudioDelay)
                    "video_filters" -> showPanel(Panels.VideoFilters)
                }
            }
            "set_button_title" -> {
                _primaryButtonTitle.update { _ -> data }
            }
            "reset_button_title" -> {
                _customButtons.value.getButtons().firstOrNull { it.isFavorite }?.let {
                    setPrimaryCustomButtonTitle(it)
                }
            }
            "switch_episode" -> {
                when (data) {
                    "n" -> changeEpisode(false)
                    "p" -> changeEpisode(true)
                }
            }
            "launch_int_picker" -> {
                val (title, nameFormat, start, stop, step, pickerProperty) = data.split("|")
                val defaultValue = MPVLib.getPropertyInt(pickerProperty)
                showDialog(
                    Dialogs.IntegerPicker(
                        defaultValue = defaultValue,
                        minValue = start.toInt(),
                        maxValue = stop.toInt(),
                        step = step.toInt(),
                        nameFormat = nameFormat,
                        title = title,
                        onChange = { MPVLib.setPropertyInt(pickerProperty, it) },
                        onDismissRequest = { showDialog(Dialogs.None) },
                    ),
                )
            }
            "pause" -> {
                when (data) {
                    "pause" -> pause()
                    "unpause" -> unpause()
                    "pauseunpause" -> pauseUnpause()
                }
            }
            "seek_to_with_text" -> {
                val (seekValue, text) = data.split("|", limit = 2)
                seekToWithText(seekValue.toInt(), text)
            }
            "seek_by_with_text" -> {
                val (seekValue, text) = data.split("|", limit = 2)
                seekByWithText(seekValue.toInt(), text)
            }
            "seek_by" -> seekByWithText(data.toInt(), null)
            "seek_to" -> seekToWithText(data.toInt(), null)
            "toggle_button" -> {
                fun showButton() {
                    if (_primaryButton.value == null) {
                        _primaryButton.update {
                            customButtons.value.getButtons().firstOrNull { it.isFavorite }
                        }
                    }
                }

                when (data) {
                    "show" -> showButton()
                    "hide" -> _primaryButton.update { null }
                    "toggle" -> if (_primaryButton.value == null) showButton() else _primaryButton.update { null }
                }
            }

            "software_keyboard" -> when (data) {
                "show" -> forceShowSoftwareKeyboard()
                "hide" -> forceHideSoftwareKeyboard()
                "toggle" -> if (inputMethodManager.isActive) {
                    forceHideSoftwareKeyboard()
                } else {
                    forceShowSoftwareKeyboard()
                }
            }
        }

        MPVLib.setPropertyString(property, "")
    }

    private operator fun <T> List<T>.component6(): T = get(5)

    private val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun forceShowSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun forceHideSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    private val doubleTapToSeekDuration = gesturePreferences.skipLengthPreference().get()
    private val preciseSeek = gesturePreferences.playerSmoothSeek().get()
    private val showSeekBar = gesturePreferences.showSeekBar().get()

    private fun seekToWithText(seekValue: Int, text: String?) {
        _isSeekingForwards.value = seekValue > 0
        _doubleTapSeekAmount.value = seekValue - pos.value.toInt()
        _seekText.update { _ -> text }
        seekTo(seekValue, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    private fun seekByWithText(value: Int, text: String?) {
        _doubleTapSeekAmount.update { if (value < 0 && it < 0 || pos.value + value > duration.value) 0 else it + value }
        _seekText.update { text }
        _isSeekingForwards.value = value > 0
        seekBy(value, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    fun updateSeekAmount(amount: Int) {
        _doubleTapSeekAmount.update { _ -> amount }
    }

    fun updateSeekText(value: String?) {
        _seekText.update { _ -> value }
    }

    fun leftSeek() {
        if (pos.value > 0) {
            _doubleTapSeekAmount.value -= doubleTapToSeekDuration
        }
        _isSeekingForwards.value = false
        seekBy(-doubleTapToSeekDuration, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    fun rightSeek() {
        if (pos.value < duration.value) {
            _doubleTapSeekAmount.value += doubleTapToSeekDuration
        }
        _isSeekingForwards.value = true
        seekBy(doubleTapToSeekDuration, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    /**
     * Reset state when changing episodes
     */
    fun resetState() {
        _pausedState.update { _ -> false }
        _hosterState.update { _ -> emptyList() }
        _hosterList.update { _ -> emptyList() }
        _hosterExpandedList.update { _ -> emptyList() }
        _selectedHosterVideoIndex.update { _ -> Pair(-1, -1) }
        _currentVideo.value = null
        thumbnailTileCache.clear()
        thumbnailFetchJob?.cancel()
        lastThumbnailFetch = 0L
    }

    fun changeEpisode(previous: Boolean, autoPlay: Boolean = false) {
        if (previous && !hasPreviousEpisode.value) {
            activity.showToast(activity.stringResource(AYMR.strings.no_prev_episode))
            return
        }

        if (!previous && !hasNextEpisode.value) {
            activity.showToast(activity.stringResource(AYMR.strings.no_next_episode))
            return
        }

        if (!previous) notifySkippedFillers()
        activity.changeEpisode(
            episodeId = getAdjacentEpisodeId(previous = previous),
            autoPlay = autoPlay,
        )
    }

    fun handleLeftDoubleTap() {
        when (gesturePreferences.leftDoubleTapGesture().get()) {
            SingleActionGesture.Seek -> {
                leftSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Custom -> {
                MPVLib.command(arrayOf("keypress", CustomKeyCodes.DoubleTapLeft.keyCode))
            }
            SingleActionGesture.None -> {}
            SingleActionGesture.Switch -> changeEpisode(true)
        }
    }

    fun handleCenterDoubleTap() {
        when (gesturePreferences.centerDoubleTapGesture().get()) {
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Custom -> {
                MPVLib.command(arrayOf("keypress", CustomKeyCodes.DoubleTapCenter.keyCode))
            }
            SingleActionGesture.Seek -> {}
            SingleActionGesture.None -> {}
            SingleActionGesture.Switch -> {}
        }
    }

    fun handleRightDoubleTap() {
        when (gesturePreferences.rightDoubleTapGesture().get()) {
            SingleActionGesture.Seek -> {
                rightSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Custom -> {
                MPVLib.command(arrayOf("keypress", CustomKeyCodes.DoubleTapRight.keyCode))
            }
            SingleActionGesture.None -> {}
            SingleActionGesture.Switch -> changeEpisode(false)
        }
    }

    override fun onCleared() {
        cancelPlaybackScrub(restorePlayback = false)
        if (currentEpisode.value != null) {
            saveWatchingProgress(currentEpisode.value!!)
            episodeToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    // ====== OLD ======

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    internal val incognitoMode: Boolean by lazy { getIncognitoState.await(currentAnime.value?.source) }
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileWatching().get()

    internal val relativeTime = uiPreferences.relativeTime().get()
    internal val dateFormat = UiPreferences.dateFormat(uiPreferences.dateFormat().get())

    /**
     * The position in the current video. Used to restore from process kill.
     */
    private var episodePosition = savedState.get<Long>("episode_position")
        set(value) {
            savedState["episode_position"] = value
            field = value
        }

    /**
     * The current video's quality index. Used to restore from process kill.
     */
    private var qualityIndex = savedState.get<Pair<Int, Int>>("quality_index") ?: Pair(-1, -1)
        set(value) {
            savedState["quality_index"] = value
            field = value
        }

    /**
     * The episode id of the currently loaded episode. Used to restore from process kill.
     */
    private var episodeId = savedState.get<Long>("episode_id") ?: -1L
        set(value) {
            savedState["episode_id"] = value
            field = value
        }

    private var episodeToDownload: AnimeDownload? = null

    private fun filterEpisodeList(episodes: List<Episode>): List<Episode> {
        val anime = currentAnime.value ?: return episodes
        val selectedEpisode = episodes.find { it.id == episodeId }
            ?: error("Requested episode of id $episodeId not found in episode list")

        val episodesForPlayer = episodes.filterNot {
            anime.unseenFilterRaw == Anime.EPISODE_SHOW_SEEN &&
                !it.seen ||
                anime.unseenFilterRaw == Anime.EPISODE_SHOW_UNSEEN &&
                it.seen ||
                anime.downloadedFilterRaw == Anime.EPISODE_SHOW_DOWNLOADED &&
                !downloadManager.isEpisodeDownloaded(
                    it.name,
                    it.scanlator,
                    anime.title,
                    anime.source,
                ) ||
                anime.downloadedFilterRaw == Anime.EPISODE_SHOW_NOT_DOWNLOADED &&
                downloadManager.isEpisodeDownloaded(
                    it.name,
                    it.scanlator,
                    anime.title,
                    anime.source,
                ) ||
                anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_BOOKMARKED &&
                !it.bookmark ||
                anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_NOT_BOOKMARKED &&
                it.bookmark ||
                anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_FILLERMARKED &&
                !it.fillermark ||
                anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_NOT_FILLERMARKED &&
                it.fillermark
        }.toMutableList()

        if (episodesForPlayer.all { it.id != episodeId }) {
            episodesForPlayer += listOf(selectedEpisode)
        }

        return episodesForPlayer
    }

    fun getCurrentEpisodeIndex(): Int {
        return currentPlaylist.value.indexOfFirst { currentEpisode.value?.id == it.id }
    }

    private val guideObserver = viewModelScope.launch {
        combine(episodeGuides.states, episodeGuides.preferences.skip().changes()) { _, _ -> Unit }.collect {
            _hasNextEpisode.value = getAdjacentEpisodeId(false) >= 0
        }
    }

    private fun getAdjacentEpisodeId(previous: Boolean): Long {
        return if (previous) {
            currentPlaylist.value.getOrNull(getCurrentEpisodeIndex() - 1)?.id ?: -1L
        } else {
            nextGuidedEpisode().first?.id ?: -1L
        }
    }

    fun updateHasNextEpisode(value: Boolean) {
        _hasNextEpisode.update { _ -> value }
    }

    fun updateHasPreviousEpisode(value: Boolean) {
        _hasPreviousEpisode.update { _ -> value }
    }

    fun showEpisodeListDialog() {
        if (currentAnime.value != null) {
            showDialog(Dialogs.EpisodeList)
        }
    }

    /**
     * Called when the activity is saved and not changing configurations. It updates the database
     * to persist the current progress of the active episode.
     */
    fun onSaveInstanceStateNonConfigurationChange() {
        val currentEpisode = currentEpisode.value ?: return
        viewModelScope.launchNonCancellable {
            saveEpisodeProgress(currentEpisode)
        }
    }

    // ====== Initialize anime, episode, hoster, and video list ======

    fun updateIsLoadingHosters(value: Boolean) {
        _isLoadingHosters.update { _ -> value }
    }

    /**
     * Whether this viewModel is initialized with the correct episode.
     */
    private fun needsInit(animeId: Long, episodeId: Long): Boolean {
        return remoteProgressOwned || currentAnime.value?.id != animeId || currentEpisode.value?.id != episodeId
    }

    data class InitResult(
        val hosterList: List<Hoster>?,
        val videoIndex: Pair<Int, Int>,
        val position: Long?,
    )

    private var currentHosterList: List<Hoster>? = null

    class ExceptionWithStringResource(
        message: String,
        val stringResource: StringResource,
    ) : Exception(message)

    internal data class PreparedSelection(
        val anime: Anime,
        val episode: Episode,
        val playlist: List<Episode>,
        val source: AnimeSource,
        val hosters: List<Hoster>,
        val states: List<HosterState>,
        val selected: Pair<Int, Int>,
        val video: Video,
    )

    /** No playback state or native property is changed during network preparation. */
    internal suspend fun prepareSelection(
        animeId: Long,
        requestedEpisodeId: Long,
        hostList: String,
        hostIndex: Int,
        vidIndex: Int,
    ): PreparedSelection = withContext(Dispatchers.IO) {
        sourceManager.isInitialized.first { it }
        val anime = getAnime.await(animeId)
            ?: throw ExceptionWithStringResource("Title unavailable", AYMR.strings.no_episode_loaded)
        val playlist = getEpisodesByAnimeId.await(anime.id)
            .sortedWith(getEpisodeSort(anime, sortDescending = false))
            .run { if (basePreferences.downloadedOnly().get()) filterDownloadedEpisodes(anime) else this }
            .map { it.toDbEpisode() }
        val episode = playlist.firstOrNull { it.id == requestedEpisodeId }
            ?: throw ExceptionWithStringResource("Episode unavailable", AYMR.strings.no_episode_loaded)
        val source = sourceManager.getOrStub(anime.source)
        val hosters = if (hostList.isNotBlank()) {
            hostList.toHosterList()
        } else {
            EpisodeLoader.getHosters(requireNotNull(episode.toDomainEpisode()), anime, source)
        }
        if (hosters.isEmpty()) {
            throw ExceptionWithStringResource("Hoster list is empty", AYMR.strings.no_hosters)
        }
        val resolved = resolvePreparedHosters(source, hosters, hostIndex, vidIndex)
        PreparedSelection(anime, episode, playlist, source, hosters, resolved.first, resolved.second, resolved.third)
    }

    private suspend fun resolvePreparedHosters(
        source: AnimeSource,
        hosters: List<Hoster>,
        hostIndex: Int,
        videoIndex: Int,
    ): Triple<List<HosterState>, Pair<Int, Int>, Video> = coroutineScope {
        val states = hosters.map { HosterState.Idle(it.hosterName) as HosterState }.toMutableList()
        val updates = Channel<Pair<Int, HosterState>>(Channel.UNLIMITED)
        val jobs = hosters.mapIndexed { index, hoster ->
            launch {
                val force = index == hostIndex || hosters.all { it.lazy }
                val state = EpisodeLoader.loadHosterVideos(source, hoster, force = force)
                updates.send(index to state)
            }
        }
        suspend fun resolve(index: Pair<Int, Int>): Video? {
            val state = states.getOrNull(index.first) as? HosterState.Ready ?: return null
            val video = state.videoList.getOrNull(index.second) ?: return null
            val resolved = HosterLoader.getResolvedVideo(source, video)?.takeIf { it.videoUrl.isNotBlank() }
            currentCoroutineContext().ensureActive()
            states[index.first] = state.getChangedAt(
                index.second,
                resolved ?: video,
                if (resolved == null) Video.State.ERROR else Video.State.READY,
            )
            return resolved
        }
        try {
            repeat(hosters.size) {
                val (index, state) = updates.receive()
                states[index] = state
                val preferred = when {
                    index == hostIndex && state is HosterState.Ready && videoIndex in state.videoList.indices ->
                        index to videoIndex
                    hostIndex < 0 && state is HosterState.Ready ->
                        state.videoList.indexOfFirst { it.preferred }.takeIf { it >= 0 }?.let { index to it }
                    else -> null
                }
                if (preferred != null) {
                    resolve(preferred)?.let { video ->
                        return@coroutineScope Triple(states.toList(), preferred, video)
                    }
                }
            }
            while (true) {
                val selected = HosterLoader.selectBestVideo(states)
                if (selected.first < 0) break
                resolve(selected)?.let { video -> return@coroutineScope Triple(states.toList(), selected, video) }
            }
            throw ExceptionWithStringResource("No available videos", AYMR.strings.no_available_videos)
        } finally {
            jobs.forEach { it.cancel() }
            updates.close()
        }
    }

    /** Commit on the service dispatcher only after all required preparation has succeeded. */
    internal fun commitSelection(selection: PreparedSelection) {
        val (anime, episode, playlist, source, hosters, states, selected, video) = selection
        episodeId = requireNotNull(episode.id)
        episodePosition = null
        _currentAnime.value = anime
        animeTitle.value = anime.title
        _currentSource.value = source
        _currentEpisode.value = episode
        updateEpisodeList(playlist)
        updateEpisode(episode)
        _hasPreviousEpisode.value = getCurrentEpisodeIndex() > 0
        _hasNextEpisode.value = getAdjacentEpisodeId(false) >= 0
        currentHosterList = hosters
        _hosterList.value = hosters
        _hosterState.value = states
        _hosterExpandedList.value = List(hosters.size) { true }
        _selectedHosterVideoIndex.value = selected
        qualityIndex = selected
        _currentVideo.value = video
        updateIsLoadingEpisode(true)
        updateIsLoadingHosters(false)
        MPVLib.setPropertyString("user-data/current-anime/anime-title", anime.title)
        MPVLib.setPropertyInt("user-data/current-anime/intro-length", getAnimeSkipIntroLength())
        viewModelScope.launchIO {
            checkTrackers(anime)
            episodeGuides.refresh(anime)
            val categories = getAnimeCategories.await(anime.id).joinToString { it.name }
            withUIContext {
                if (currentAnime.value?.id == anime.id && !activity.player.isExiting) {
                    MPVLib.setPropertyString("user-data/current-anime/category", categories)
                }
            }
            loadThumbnails(video, source)
        }
    }

    private fun updateEpisode(episode: Episode) {
        mediaTitle.update { _ -> episode.name }
        _isEpisodeOnline.update { _ -> isEpisodeOnline() == true }
        MPVLib.setPropertyDouble("user-data/current-anime/episode-number", episode.episode_number.toDouble())
    }

    private fun initEpisodeList(anime: Anime): List<Episode> {
        val episodes = runBlocking { getEpisodesByAnimeId.await(anime.id) }

        return episodes
            .sortedWith(getEpisodeSort(anime, sortDescending = false))
            .run {
                if (basePreferences.downloadedOnly().get()) {
                    filterDownloadedEpisodes(anime)
                } else {
                    this
                }
            }
            .map { it.toDbEpisode() }
    }

    @Volatile private var hasTrackers: Boolean = false
    private var autoTrackAttemptedAnimeId: Long? = null
    private var autoTrackAttemptedAt: Long = 0L
    private var autoTrackJob: Job? = null

    fun startAutoTracking() {
        if (basePreferences.incognitoMode().get() || !trackPreferences.autoUpdateTrack().get()) return
        val anime = currentAnime.value ?: return
        val source = currentSource.value ?: return
        if (autoTrackJob?.isActive == true) {
            if (autoTrackAttemptedAnimeId == anime.id) return
            autoTrackJob?.cancel()
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (autoTrackAttemptedAnimeId == anime.id && now - autoTrackAttemptedAt < 60_000L) return
        autoTrackAttemptedAnimeId = anime.id
        autoTrackAttemptedAt = now
        val episodeNumber = currentEpisode.value?.episode_number?.toDouble() ?: 0.0
        autoTrackJob = viewModelScope.launchIO {
            val result = AutoTrackingRetry.run {
                if (currentAnime.value?.id != anime.id ||
                    getIncognitoState.await(anime.source) ||
                    !trackPreferences.autoUpdateTrack().get()
                ) {
                    AutoTrackingAttempt.Complete
                } else {
                    AutoTrackOnStart.animeAttempt(anime, source, episodeNumber)
                }
            }
            if (result.linked) {
                if (currentAnime.value?.id != anime.id) return@launchIO
                hasTrackers = true
                val episode = currentEpisode.value
                if (episode?.seen == true) {
                    AutoTrackingRetry.safely {
                        trackEpisode.await(Injekt.get<Application>(), anime.id, episode.episode_number.toDouble())
                        AutoTrackingAttempt.Complete
                    }
                }
            }
        }
    }
    private val checkTrackers: (Anime) -> Unit = { anime ->
        val tracks = runBlocking { getTracks.await(anime.id) }
        hasTrackers = tracks.isNotEmpty()
    }

    private var getHosterVideoLinksJob: Job? = null

    fun cancelHosterVideoLinksJob() {
        getHosterVideoLinksJob?.cancel()
    }

    /**
     * Set the video list for hosters.
     */
    fun loadHosters(source: AnimeSource, hosterList: List<Hoster>, hosterIndex: Int, videoIndex: Int) {
        val hasFoundPreferredVideo = AtomicBoolean(false)

        _hosterList.update { _ -> hosterList }
        _hosterExpandedList.update { _ ->
            List(hosterList.size) { true }
        }

        getHosterVideoLinksJob?.cancel()
        if (hosterList.any { !it.lazy }) {
            playbackLoad.begin()
            isLoading.value = true
        }
        getHosterVideoLinksJob = viewModelScope.launchIO {
            _hosterState.update { _ ->
                hosterList.map { hoster ->
                    if (hoster.lazy) {
                        HosterState.Idle(hoster.hosterName)
                    } else if (hoster.videoList == null) {
                        HosterState.Loading(hoster.hosterName)
                    } else {
                        val videoList = hoster.videoList!!
                        HosterState.Ready(
                            hoster.hosterName,
                            videoList,
                            List(videoList.size) { Video.State.QUEUE },
                        )
                    }
                }
            }

            try {
                coroutineScope {
                    hosterList.mapIndexed { hosterIdx, hoster ->
                        async {
                            val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)
                            currentCoroutineContext().ensureActive()
                            _hosterState.updateAt(hosterIdx, hosterState)

                            if (hosterState is HosterState.Ready) {
                                if (hosterIdx == hosterIndex) {
                                    hosterState.videoList.getOrNull(videoIndex)?.let {
                                        hasFoundPreferredVideo.set(true)
                                        val success = loadVideo(source, it, hosterIndex, videoIndex)
                                        if (!success) {
                                            hasFoundPreferredVideo.set(false)
                                        }
                                    }
                                }

                                val prefIndex = hosterState.videoList.indexOfFirst { it.preferred }
                                if (prefIndex != -1 && hosterIndex == -1) {
                                    if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                                        if (selectedHosterVideoIndex.value == Pair(-1, -1)) {
                                            val success =
                                                loadVideo(
                                                    source,
                                                    hosterState.videoList[prefIndex],
                                                    hosterIdx,
                                                    prefIndex,
                                                )
                                            if (!success) {
                                                hasFoundPreferredVideo.set(false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }.awaitAll()

                    if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                        if (selectedHosterVideoIndex.value == Pair(-1, -1)) {
                            val (hosterIdx, videoIdx) = HosterLoader.selectBestVideo(hosterState.value)
                            if (hosterIdx == -1) {
                                throw ExceptionWithStringResource(
                                    "No available videos",
                                    AYMR.strings.no_available_videos,
                                )
                            }

                            val video = (hosterState.value[hosterIdx] as HosterState.Ready).videoList[videoIdx]

                            loadVideo(source, video, hosterIdx, videoIdx)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // A newer episode owns the state now; cancellation must not overwrite its hosters.
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    currentCoroutineContext().ensureActive()
                    activity.onPlaybackFailure(PlaybackFailure.fromNative(e.message.orEmpty()))
                }
            }
        }
    }

    private var qualityGeneration = 0L
    private var qualityJob: Job? = null

    private suspend fun loadVideo(source: AnimeSource?, video: Video, hosterIndex: Int, videoIndex: Int): Boolean {
        val selected = _hosterState.value.getOrNull(hosterIndex) as? HosterState.Ready ?: return false
        val episode = episodeId
        val generation = qualityGeneration
        val resolved = if (selected.videoState.getOrNull(videoIndex) == Video.State.READY) {
            video
        } else {
            HosterLoader.getResolvedVideo(source, video)
        }
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.Main.immediate) {
            if (!activity.available || episodeId != episode || generation != qualityGeneration) return@withContext false
            if (resolved == null || resolved.videoUrl.isBlank()) {
                _hosterState.updateAt(hosterIndex, selected.getChangedAt(videoIndex, video, Video.State.ERROR))
                return@withContext false
            }
            activity.captureReplacement()
            if (!activity.available || episodeId != episode || generation != qualityGeneration) return@withContext false
            updatePausedState()
            updateIsLoadingEpisode(true)
            _hosterState.updateAt(hosterIndex, selected.getChangedAt(videoIndex, resolved, Video.State.READY))
            _selectedHosterVideoIndex.value = hosterIndex to videoIndex
            qualityIndex = hosterIndex to videoIndex
            _currentVideo.value = resolved
            activity.setVideo(resolved)
            viewModelScope.launchIO { loadThumbnails(resolved, source) }
            true
        }
    }

    fun updateVideo(video: Video) {
        _currentVideo.update { _ -> video }
    }

    fun onVideoClicked(hosterIndex: Int, videoIndex: Int) {
        val hoster = _hosterState.value.getOrNull(hosterIndex) as? HosterState.Ready ?: return
        val video = hoster.videoList.getOrNull(videoIndex) ?: return
        if (hoster.videoState.getOrNull(videoIndex) == Video.State.ERROR) return
        qualityGeneration++
        qualityJob?.cancel()
        qualityJob = viewModelScope.launchIO {
            try {
                val success = loadVideo(currentSource.value, video, hosterIndex, videoIndex)
                withContext(Dispatchers.Main.immediate) {
                    if (!activity.available) return@withContext
                    if (success && sheetShown.value == Sheets.QualityTracks) dismissSheet()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Quality selection failed; keeping current playback" }
            }
        }
    }

    fun onHosterClicked(index: Int) {
        when (hosterState.value.getOrNull(index)) {
            is HosterState.Ready -> {
                _hosterExpandedList.updateAt(index, !_hosterExpandedList.value[index])
            }
            is HosterState.Idle -> {
                val hoster = hosterList.value.getOrNull(index) ?: return
                val source = currentSource.value ?: return
                val episode = currentEpisode.value?.id ?: return
                val hosterName = hoster.hosterName
                _hosterState.updateAt(index, HosterState.Loading(hosterName))

                viewModelScope.launchIO {
                    val hosterState = EpisodeLoader.loadHosterVideos(
                        source = source,
                        hoster = hoster,
                        force = true,
                    )
                    currentCoroutineContext().ensureActive()
                    if (currentEpisode.value?.id == episode &&
                        currentSource.value === source &&
                        hosterList.value.getOrNull(index) === hoster
                    ) {
                        _hosterState.updateAt(index, hosterState)
                    }
                }
            }
            is HosterState.Loading, is HosterState.Error, null -> {}
        }
    }

    suspend fun loadThumbnails(video: Video, source: AnimeSource?) {
        if (source is AnimeHttpSource) {
            try {
                val thumbInfo = source.getVideoThumbnails(video)
                if (thumbInfo != null) {
                    thumbnailInfo.update { _ ->
                        ThumbnailInfo(
                            tileInfo = thumbInfo.tileInfo.sortedBy { it.timeMs },
                            imageTileUrls = thumbInfo.imageTileUrls,
                        )
                    }

                    // Preload first 2 tilemaps
                    thumbInfo.imageTileUrls.take(2).forEachIndexed { index, tileUrl ->
                        val bitmap = source.getImageTile(tileUrl)
                        if (bitmap != null) {
                            thumbnailTileCache[index] = bitmap
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to fetch thumbnails" }
            }
        }
    }

    private fun <T> MutableStateFlow<List<T>>.updateAt(index: Int, newValue: T) {
        this.update { values ->
            values.toMutableList().apply {
                this[index] = newValue
            }
        }
    }

    data class EpisodeLoadResult(
        val hosterList: List<Hoster>?,
        val episodeTitle: String,
        val source: AnimeSource,
    )

    suspend fun loadEpisode(episodeId: Long?): EpisodeLoadResult? {
        val anime = currentAnime.value ?: return null
        val source = sourceManager.getOrStub(anime.source)

        val chosenEpisode = currentPlaylist.value.firstOrNull { ep -> ep.id == episodeId } ?: return null

        _currentEpisode.update { _ -> chosenEpisode }
        updateEpisode(chosenEpisode)

        return withIOContext {
            currentHosterList = null // Never replay a previous episode's URLs after resolution fails.
            try {
                val currentEpisode =
                    currentEpisode.value
                        ?: throw ExceptionWithStringResource("No episode loaded", AYMR.strings.no_episode_loaded)
                currentHosterList = EpisodeLoader.getHosters(
                    currentEpisode.toDomainEpisode()!!,
                    anime,
                    source,
                )

                this@PlayerViewModel.episodeId = currentEpisode.id!!
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { e.message ?: "Error getting links" }
            }

            EpisodeLoadResult(
                hosterList = currentHosterList,
                episodeTitle = anime.title + " - " + chosenEpisode.name,
                source = source,
            )
        }
    }

    /**
     * Called every time a second is reached in the player. Used to mark the flag of episode being
     * seen, update tracking services, enqueue downloaded episode deletion and download next episode.
     */
    private fun onSecondReached(position: Int, duration: Int) {
        val cast = CastController.get(activity.applicationContext).state.value
        if (remoteProgressOwned || cast.active || cast.connecting) return
        if (isLoadingEpisode.value || !playbackLoadState.value.canSaveProgress) return
        val currentEp = currentEpisode.value ?: return
        if (episodeId == -1L) return
        if (duration == 0) return

        val seconds = position * 1000L
        val totalSeconds = duration * 1000L
        val seekFinished = kotlin.math.abs(seconds - currentEp.last_second_seen) > 3_000
        // Save last second seen and mark as seen if needed
        currentEp.last_second_seen = seconds
        currentEp.total_seconds = totalSeconds

        episodePosition = seconds

        val progress = playerPreferences.progressPreference().get()
        val shouldTrack = !incognitoMode || hasTrackers
        if (seconds >= totalSeconds * progress && shouldTrack) {
            viewModelScope.launchNonCancellable {
                updateEpisodeProgressOnComplete(currentEp)
            }
        }

        saveWatchingProgress(currentEp, urgent = paused.value || seekFinished)

        val inDownloadRange = seconds.toDouble() / totalSeconds > 0.35
        if (inDownloadRange) {
            downloadNextEpisodes()
        }
    }

    private suspend fun updateEpisodeProgressOnComplete(currentEp: Episode) {
        currentEp.seen = true
        updateTrackEpisodeSeen(currentEp)
        deleteEpisodeIfNeeded(currentEp)

        val markDuplicateAsSeen = libraryPreferences.markDuplicateSeenEpisodeAsSeen().get()
            .contains(LibraryPreferences.MARK_DUPLICATE_EPISODE_SEEN_EXISTING)
        if (!markDuplicateAsSeen) return

        val duplicateUnseenEpisodes = currentPlaylist.value
            .mapNotNull { episode ->
                if (
                    !episode.seen &&
                    episode.isRecognizedNumber &&
                    episode.episode_number == currentEp.episode_number &&
                    episode.anime_id == currentEp.anime_id &&
                    tachiyomi.domain.items.episode.model.EpisodeGroup.from(episode.memo)?.id ==
                    tachiyomi.domain.items.episode.model.EpisodeGroup.from(currentEp.memo)?.id
                ) {
                    EpisodeUpdate(id = episode.id!!, seen = true)
                } else {
                    null
                }
            }
        updateEpisode.awaitAll(duplicateUnseenEpisodes)
    }

    private fun downloadNextEpisodes() {
        if (downloadAheadAmount == 0) return
        val anime = currentAnime.value ?: return

        // Only download ahead if current + next episode is already downloaded too to avoid jank
        if (getCurrentEpisodeIndex() == currentPlaylist.value.lastIndex) return
        val currentEpisode = currentEpisode.value ?: return

        val nextEpisode = nextGuidedEpisode().first ?: return
        val episodesAreDownloaded =
            EpisodeLoader.isDownload(currentEpisode.toDomainEpisode()!!, anime) &&
                EpisodeLoader.isDownload(nextEpisode.toDomainEpisode()!!, anime)

        viewModelScope.launchIO {
            if (!episodesAreDownloaded) {
                return@launchIO
            }
            val episodesToDownload = getNextEpisodes.await(anime.id, nextEpisode.id!!)
                .take(downloadAheadAmount)
            downloadManager.downloadEpisodes(anime, episodesToDownload)
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last episode actually exists.
     * If both conditions are satisfied enqueues episode for delete
     * @param chosenEpisode current episode, which is going to be marked as seen.
     */
    private fun deleteEpisodeIfNeeded(chosenEpisode: Episode) {
        // Determine which episode should be deleted and enqueue
        val currentEpisodePosition = currentPlaylist.value.indexOf(chosenEpisode)
        val removeAfterSeenSlots = downloadPreferences.removeAfterReadSlots().get()
        val episodeToDelete = currentPlaylist.value.getOrNull(
            currentEpisodePosition - removeAfterSeenSlots,
        )
        // If episode is completely seen no need to download it
        episodeToDownload = null

        // Check if deleting option is enabled and episode exists
        if (removeAfterSeenSlots != -1 && episodeToDelete != null) {
            enqueueDeleteSeenEpisodes(episodeToDelete)
        }
    }

    fun saveCurrentEpisodeWatchingProgress() {
        currentEpisode.value?.let { saveWatchingProgress(it) }
    }

    fun recordEndingCue(episodeId: Long, durationSeconds: Int, stamps: List<TimeStamp>) {
        if (incognitoMode) return
        val cue = EpisodeEndingCue.from(stamps, durationSeconds)
        viewModelScope.launchNonCancellable {
            try {
                if (cue == null) endingCues.remove(episodeId) else endingCues.save(episodeId, cue)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Unable to retain episode ending marker" }
            }
        }
    }

    /**
     * Called when episode is changed in player or when activity is paused.
     */
    private fun saveWatchingProgress(episode: Episode, urgent: Boolean = true) {
        val cast = CastController.get(activity.applicationContext).state.value
        if (remoteProgressOwned || cast.active || cast.connecting) return
        viewModelScope.launchNonCancellable {
            saveEpisodeProgress(episode)
            saveEpisodeHistory(episode)
            if (urgent && !incognitoMode) eu.kanade.tachiyomi.data.cloud.sync.CloudSyncManager.progressSaved()
        }
    }

    /**
     * Saves this [episode] progress (last second seen and whether it's seen).
     * If incognito mode isn't on or has at least 1 tracker
     */
    private suspend fun saveEpisodeProgress(episode: Episode) {
        if (remoteProgressOwned) return
        if (!incognitoMode || hasTrackers) {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episode.id!!,
                    seen = episode.seen,
                    bookmark = episode.bookmark,
                    fillermark = episode.fillermark,
                    lastSecondSeen = episode.last_second_seen,
                    totalSeconds = episode.total_seconds,
                    localOnly = incognitoMode,
                ),
            )
        }
    }

    /**
     * Saves this [episode] last seen history if incognito mode isn't on.
     */
    private suspend fun saveEpisodeHistory(episode: Episode) {
        if (remoteProgressOwned) return
        if (!incognitoMode) {
            val episodeId = episode.id!!
            val seenAt = Date()
            upsertHistory.await(
                AnimeHistoryUpdate(episodeId, seenAt),
            )
        }
    }

    /**
     * Bookmarks the currently active episode.
     */
    fun bookmarkEpisode(episodeId: Long?, bookmarked: Boolean) {
        viewModelScope.launchNonCancellable {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episodeId!!,
                    bookmark = bookmarked,
                ),
            )
        }
    }

    /**
     * Fillermarks the currently active episode.
     */
    fun fillermarkEpisode(episodeId: Long?, fillermarked: Boolean) {
        currentAnime.value?.let { anime ->
            currentPlaylist.value.firstOrNull { it.id == episodeId }?.let {
                episodeGuides.setOverride(anime, it.url, fillermarked)
            }
        }
        viewModelScope.launchNonCancellable {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episodeId!!,
                    fillermark = fillermarked,
                ),
            )
        }
    }

    fun takeScreenshot(cachePath: String, showSubtitles: Boolean): InputStream? {
        val filename = cachePath + "/${System.currentTimeMillis()}_mpv_screenshot_tmp.png"
        val subtitleFlag = if (showSubtitles) "subtitles" else "video"

        MPVLib.command(arrayOf("screenshot-to-file", filename, subtitleFlag))
        val tempFile = File(filename).takeIf { it.exists() } ?: return null
        val newFile = File("$cachePath/mpv_screenshot.png")

        newFile.delete()
        tempFile.renameTo(newFile)
        return newFile.takeIf { it.exists() }?.inputStream()
    }

    /**
     * Saves the screenshot on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(imageStream: () -> InputStream, timePos: Int?) {
        val anime = currentAnime.value ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val seconds = timePos?.let { Utils.prettyTime(it) } ?: return
        val filename = generateFilename(anime, seconds) ?: return

        // Pictures directory.
        val relativePath = DiskUtil.buildValidFilename(anime.title)

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = imageStream,
                        name = filename,
                        location = Location.Pictures(relativePath),
                    ),
                )
                notifier.onComplete(uri)
                eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the screenshot and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(imageStream: () -> InputStream, timePos: Int?) {
        val anime = currentAnime.value ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val seconds = timePos?.let { Utils.prettyTime(it) } ?: return
        val filename = generateFilename(anime, seconds) ?: return

        try {
            viewModelScope.launchIO {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = imageStream,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(Event.ShareImage(uri, seconds))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the screenshot as art and notifies the UI of the result.
     */
    fun setAsArt(artType: ArtType, imageStream: () -> InputStream) {
        val anime = currentAnime.value ?: return
        val episode = currentEpisode.value ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                when (artType) {
                    ArtType.Cover -> anime.editCover(Injekt.get(), imageStream())
                    ArtType.Background -> anime.editBackground(Injekt.get(), imageStream())
                    ArtType.Thumbnail -> episode.editThumbnail(anime, Injekt.get(), imageStream())
                }

                if (anime.isLocal() || anime.favorite) {
                    SetAsArt.Success
                } else {
                    SetAsArt.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsArt.Error
            }
            eventChannel.send(Event.SetArtResult(result, artType))
        }
    }

    /**
     * Results of the save image feature.
     */
    sealed class SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult()
        class Error(val error: Throwable) : SaveImageResult()
    }

    private fun updateTrackEpisodeSeen(episode: Episode) {
        if (basePreferences.incognitoMode().get() || !hasTrackers) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        val anime = currentAnime.value ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackEpisode.await(context, anime.id, episode.episode_number.toDouble())
        }
    }

    /**
     * Enqueues this [episode] to be deleted when [deletePendingEpisodes] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteSeenEpisodes(episode: Episode) {
        if (!episode.seen) return
        val anime = currentAnime.value ?: return
        viewModelScope.launchNonCancellable {
            downloadManager.enqueueEpisodesToDelete(listOf(episode.toDomainEpisode()!!), anime)
        }
    }

    /**
     * Deletes all the pending episodes. This operation will run in a background thread and errors
     * are ignored.
     */
    fun deletePendingEpisodes() {
        val cast = CastController.get(activity.applicationContext).state.value
        if (cast.active || cast.connecting) return
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingEpisodes()
        }
    }

    /**
     * Returns the skipIntroLength used by this anime or the default one.
     */
    fun getAnimeSkipIntroLength(): Int {
        val default = gesturePreferences.defaultIntroLength().get()
        val anime = currentAnime.value ?: return default
        val skipIntroLength = anime.skipIntroLength
        val skipIntroDisable = anime.skipIntroDisable
        return when {
            skipIntroDisable -> 0
            skipIntroLength <= 0 -> default
            else -> anime.skipIntroLength
        }
    }

    /**
     * Updates the skipIntroLength for the open anime.
     */
    fun setAnimeSkipIntroLength(skipIntroLength: Long) {
        val anime = currentAnime.value ?: return
        if (!anime.favorite) return
        // Skip unnecessary database operation
        if (skipIntroLength == getAnimeSkipIntroLength().toLong()) return
        viewModelScope.launchIO {
            setAnimeViewerFlags.awaitSetSkipIntroLength(anime.id, skipIntroLength)
            _currentAnime.update { _ -> getAnime.await(anime.id) }
        }
    }

    /**
     * Generate a filename for the given [anime] and [timePos]
     */
    private fun generateFilename(
        anime: Anime,
        timePos: String,
    ): String? {
        val episode = currentEpisode.value ?: return null
        val filenameSuffix = " - $timePos"
        return DiskUtil.buildValidFilename(
            "${anime.title} - ${episode.name}".takeBytes(
                DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
            ),
        ) +
            filenameSuffix
    }

    /** Returns the AniSkip segments when a trustworthy catalog identity is available. */
    private val aniSkipApi by lazy { AniSkipApi() }

    suspend fun aniSkipResponse(playerDuration: Int?): List<TimeStamp>? {
        val animeId = currentAnime.value?.id ?: return null
        val episodeNumber = currentEpisode.value?.episode_number?.toDouble() ?: return null
        val duration = playerDuration?.takeIf { it > 0 }?.toLong() ?: return null
        if (!episodeNumber.isFinite() || episodeNumber < 0) return null
        val trackerManager = Injekt.get<TrackerManager>()
        val tracks = getTracks.await(animeId).sortedBy { trackerManager.get(it.trackerId) !is MyAnimeList }
        val requestedIds = mutableSetOf<Long>()
        for (track in tracks) {
            val malId = when (trackerManager.get(track.trackerId)) {
                is MyAnimeList -> track.remoteId.takeIf { it > 0 }
                is Anilist -> aniSkipApi.getMalIdFromAL(track.remoteId)
                else -> null
            } ?: continue
            if (!requestedIds.add(malId)) continue
            aniSkipApi.getResult(malId, episodeNumber, duration)?.let { return it }
        }
        val anime = currentAnime.value ?: return null
        val hints = currentSource.value?.let { AutoTrackOnStart.animeHints(anime, it) }
            ?: SourceTrackingHints.from(anime)
        val malId = try {
            hints?.malId
                ?: hints?.anilistId?.let { aniSkipApi.getMalIdFromAL(it) }
                ?: AniListMediaLookup.resolve(anime.title, AniListMediaLookup.Type.ANIME)
                    ?.takeIf { it.episodes == null || episodeNumber <= it.episodes }
                    ?.malId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        if (!requestedIds.add(malId)) return null
        return aniSkipApi.getResult(malId, episodeNumber, duration)
    }

    val introSkipEnabled = playerPreferences.enableSkipIntro().get()
    private val netflixStyle = playerPreferences.enableNetflixStyleIntroSkip().get()

    private val defaultWaitingTime = playerPreferences.waitingTimeIntroSkip().get()
    var waitingSkipIntro = defaultWaitingTime

    fun setChapter(position: Float) {
        if (watchTogether.active) {
            _skipIntroText.value = null
            if (watchTogether.state.value.host) {
                val segment = getCurrentChapter(position)
                if (introSkipEnabled && segment != null && segment.value.chapterType != ChapterType.Other) {
                    val target = ChapterUtils.skipTarget(chapters.value, segment.index, pos.value, duration.value)
                    val key = currentEpisode.value?.id.toString() + ":" + segment.value.start
                    watchTogether.offerSkip(
                        key,
                        "Salta " + segment.value.name,
                        target.toDouble(),
                        if (playerPreferences.shouldAutoSkipIntro(currentAnime.value) && netflixStyle) {
                            defaultWaitingTime
                        } else if (playerPreferences.shouldAutoSkipIntro(currentAnime.value)) {
                            3
                        } else {
                            null
                        },
                    )
                } else {
                    watchTogether.offerSkip(null)
                }
            }
            return
        }
        getCurrentChapter(position)?.let { (chapterIndex, chapter) ->
            if (currentChapter.value != chapter) {
                _currentChapter.update { _ -> chapter }
            }

            if (!introSkipEnabled) {
                return
            }

            if (chapter.chapterType == ChapterType.Other) {
                _skipIntroText.update { _ -> null }
                waitingSkipIntro = defaultWaitingTime
            } else {
                val nextChapterPos = ChapterUtils.skipTarget(chapters.value, chapterIndex, pos.value, duration.value)

                val autoSkip = playerPreferences.shouldAutoSkipIntro(currentAnime.value)
                if (autoSkip && netflixStyle) {
                    // show a toast with the seconds before the skip
                    if (waitingSkipIntro == defaultWaitingTime) {
                        activity.showToast(
                            "Skip Intro: ${activity.stringResource(
                                AYMR.strings.player_aniskip_dontskip_toast,
                                chapter.name,
                                waitingSkipIntro,
                            )}",
                        )
                    }
                    showSkipIntroButton(chapter, nextChapterPos, waitingSkipIntro)
                    waitingSkipIntro--
                } else if (autoSkip) {
                    seekToWithText(
                        seekValue = nextChapterPos.toInt(),
                        text = activity.stringResource(AYMR.strings.player_intro_skipped, chapter.name),
                    )
                } else {
                    updateSkipIntroButton(chapter.chapterType)
                }
            }
        }
    }

    private fun updateSkipIntroButton(chapterType: ChapterType) {
        val skipButtonString = chapterType.getStringRes()

        _skipIntroText.update { _ ->
            skipButtonString?.let {
                activity.stringResource(
                    AYMR.strings.player_skip_action,
                    activity.stringResource(skipButtonString),
                )
            }
        }
    }

    private fun showSkipIntroButton(chapter: IndexedSegment, nextChapterPos: Float, waitingTime: Int) {
        if (waitingTime > -1) {
            if (waitingTime > 0) {
                _skipIntroText.update { _ -> activity.stringResource(AYMR.strings.player_aniskip_dontskip) }
            } else {
                seekToWithText(
                    seekValue = nextChapterPos.toInt(),
                    text = activity.stringResource(AYMR.strings.player_aniskip_skip, chapter.name),
                )
            }
        } else {
            // when waitingTime is -1, it means that the user cancelled the skip
            updateSkipIntroButton(chapter.chapterType)
        }
    }

    fun onSkipIntro() {
        if (watchTogether.active) {
            watchTogether.requestSkip()
            return
        }
        getCurrentChapter()?.let { (chapterIndex, chapter) ->
            // this stops the counter
            if (waitingSkipIntro > 0 && netflixStyle) {
                waitingSkipIntro = -1
                return
            }

            val nextChapterPos = ChapterUtils.skipTarget(chapters.value, chapterIndex, pos.value, duration.value)

            seekToWithText(
                seekValue = nextChapterPos.toInt(),
                text = activity.stringResource(AYMR.strings.player_aniskip_skip, chapter.name),
            )
        }
    }

    private fun getCurrentChapter(position: Float? = null): IndexedValue<IndexedSegment>? {
        return chapters.value.withIndex()
            .filter { it.value.start <= (position ?: pos.value) }
            .maxByOrNull { it.value.start }
    }

    fun setPrimaryCustomButtonTitle(button: CustomButton) {
        _primaryButtonTitle.update { _ -> button.name }
    }

    sealed class Event {
        data class SetArtResult(val result: SetAsArt, val artType: ArtType) : Event()
        data class SavedImage(val result: SaveImageResult) : Event()
        data class ShareImage(val uri: Uri, val seconds: String) : Event()
    }
}

fun CustomButton.execute() {
    MPVLib.command(arrayOf("script-message", "call_button_$id"))
}

fun CustomButton.executeLongPress() {
    MPVLib.command(arrayOf("script-message", "call_button_${id}_long"))
}

fun Float.normalize(inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
    return (this - inMin) * (outMax - outMin) / (inMax - inMin) + outMin
}
