package eu.kanade.tachiyomi.data.watch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.cast.liveObservation
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import it.lorelore.nyanime.syncplay.SyncplayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.source.interactor.UpdateAnimeFromRemote
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference
import java.net.URI

data class WatchOpeningState(
    val loading: Boolean = false,
    val error: String? = null,
    val problem: WatchProblem = WatchProblem.None,
)

private class WatchResolveFailure(val problem: WatchProblem, message: String) : IllegalArgumentException(message)

/**
 * A room outlives individual player screens. The host selects catalog entries; every guest resolves
 * them using their own installed extension, existing database and normal player loader.
 */
class WatchTogetherManager private constructor(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val text = RoomText.from(application)
    private val roomArchive = WatchRoomArchive(application)
    private val mutableRecoverable = MutableStateFlow(roomArchive.load() != null)
    val recoverable = mutableRecoverable.asStateFlow()
    val roomSaveFailed = roomArchive.saveFailed
    private var foreground = WeakReference<Activity>(null)
    private var playerOwner = WeakReference<Any>(null)
    private var persistentPlayback = false
    private var observerPlayer: WatchObserverPlayer? = null
    private val playback = WatchPlayerAttachment()
    private val delegate: WatchPlayer? get() = playback.player
    private var openInPlayer: ((Long, Long) -> Unit)? = null
    private var pendingOpen: Triple<String, Long, Long>? = null
    private var pendingNavigationToken = 0L
    private var selection: WatchSelection? = null
    private var resolutionGeneration = 0L
    val navigation = eu.kanade.tachiyomi.data.reading.RoomNavigationGeneration()
    fun foregroundActivity(): Activity? = foreground.get()?.takeUnless { it.isDestroyed || it.isFinishing }
    private var prepared: WatchSelection? = null
    private var prepareJob: kotlinx.coroutines.Job? = null
    private val mutablePreparation = MutableStateFlow(WatchOpeningState())
    val preparation = mutablePreparation.asStateFlow()
    private val mutableOpening = MutableStateFlow(WatchOpeningState())
    val opening = mutableOpening.asStateFlow()
    private val preferences = application.getSharedPreferences("watch_together", Context.MODE_PRIVATE)
    var displayName: String
        get() = preferences.getString("name", "").orEmpty()
        set(value) {
            preferences.edit().putString("name", value.trim().take(32)).apply()
        }

    val shortRooms = WatchShortRooms(scope, text, ::joinResolvedInvite)
    private fun joinResolvedInvite(invite: String, name: String) {
        playback.forgetDetached()
        controller.join(invite, name)
    }

    val interactions: WatchInteractionSession =
        WatchInteractionSession(scope, SystemClock::elapsedRealtime) { target, envelope ->
            controller.sendInteraction(target, envelope)
        }
    val voice = WatchRoomVoiceCoordinator(application, scope, interactions)

    val controller: WatchRoomController = WatchRoomController(
        scope,
        text,
        object : WatchPlayer {
            override fun sample(): WatchPlayback = currentPlayback()
            override fun pause(paused: Boolean) {
                delegate?.pause(paused)
            }
            override fun seek(seconds: Double) {
                delegate?.seek(seconds)
            }
            override fun speed(value: Double) {
                delegate?.speed(value)
            }
            override fun advance(media: WatchMedia) {
                delegate?.advance(media)
            }
            override fun userResumed() {
                delegate?.userResumed()
            }
        },
        SystemClock::elapsedRealtime,
        onSessionStarted = { invite, identity, name ->
            SyncplayManager.existing()?.leave()
            interactions.start(WatchInteractionCrypto(invite, identity))
            roomArchive.begin(invite, identity, name)
            mutableRecoverable.value = false
        },
        onSessionEnded = {
            interactions.close()
            eu.kanade.tachiyomi.data.reading.ReadingTogetherManager.existing()?.forgetRoom()
            shortRooms.close()
            roomArchive.clear()
            mutableRecoverable.value = false
        },
    )

    private var liveOwner = WeakReference<eu.kanade.tachiyomi.ui.tv.TvPlayerActivity>(null)
    private var livePlaybackOwner = WeakReference<Any>(null)
    private var liveObservation: (() -> nyanime.tv.api.TvLiveObservation?)? = null
    private var liveAdjustment: ((nyanime.tv.api.TvLiveAdjustment) -> Unit)? = null
    private var openLivePlayback: ((nyanime.tv.api.TvChannelRef) -> Unit)? = null

    fun attachLivePlayback(
        owner: Any,
        observation: () -> nyanime.tv.api.TvLiveObservation?,
        adjustment: (nyanime.tv.api.TvLiveAdjustment) -> Unit,
        open: (nyanime.tv.api.TvChannelRef) -> Unit,
    ) {
        livePlaybackOwner = WeakReference(owner)
        liveObservation = observation
        liveAdjustment = adjustment
        openLivePlayback = open
        persistentPlayback = true
    }
    fun detachLivePlayback(owner: Any) {
        if (livePlaybackOwner.get() !== owner) return
        livePlaybackOwner.clear()
        liveObservation = null
        liveAdjustment = null
        openLivePlayback = null
        persistentPlayback = false
    }

    private var pendingTv: nyanime.tv.api.TvChannel? = null
    val tv: eu.kanade.tachiyomi.data.tv.TvRoomController = eu.kanade.tachiyomi.data.tv.TvRoomController(
        scope,
        SystemClock::elapsedRealtime,
        System::currentTimeMillis,
        controller::sendTv,
        ::openLiveSelection,
        {
            eu.kanade.tachiyomi.data.cast.CastController.get(application).state.value
                .liveObservation(tv.state.value.selection?.channel) ?: liveObservation?.invoke()
                ?: liveOwner.get()?.liveObservation()
        },
        { if (liveAdjustment != null) liveAdjustment?.invoke(it) else liveOwner.get()?.liveAdjustment(it) },
        { controller.setLiveMode(false) },
    )

    fun selectTv(channel: nyanime.tv.api.TvChannel): Boolean {
        if (!controller.active || !controller.state.value.host) return false
        tv.roomChanged(controller.tvContext())
        if (!tv.select(channel)) return false
        return true
    }

    fun roomForTv(channel: nyanime.tv.api.TvChannel, context: Context) {
        if (controller.active) {
            if (!selectTv(
                    channel,
                )
            ) {
                android.widget.Toast.makeText(
                    context,
                    text(R.string.tv_room_host_only),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        pendingTv = channel
        context.startActivity(Intent(context, eu.kanade.tachiyomi.ui.watch.WatchTogetherActivity::class.java))
    }

    fun attachLive(activity: eu.kanade.tachiyomi.ui.tv.TvPlayerActivity) {
        liveOwner = WeakReference(activity)
        present(activity)
    }
    fun detachLive(activity: Activity) {
        if (liveOwner.get() === activity) liveOwner.clear()
    }

    private fun openLiveSelection(value: nyanime.tv.api.TvRoomSelection) {
        if (!controller.active) return
        if (!controller.state.value.host && readingSession.state.value.personal) return
        navigation.select(controller.state.value.invite, "live")
        controller.setLiveMode(true)
        reading.suspendReading()
        resolutionGeneration++
        pendingOpen = null
        mutableOpening.value = WatchOpeningState()
        val cast = eu.kanade.tachiyomi.data.cast.CastController.get(application)
        val receiver = cast.state.value
        if (receiver.active && receiver.device != null) {
            if (receiver.media?.live?.reference != value.channel) {
                cast.playLive(
                    eu.kanade.tachiyomi.data.cast.TvCastRequest(value.channel),
                    receiver.device,
                    replaceOnFailure = true,
                )
            }
            foreground.get()?.let {
                if (it !is eu.kanade.tachiyomi.ui.cast.CastRemoteActivity) {
                    it.startActivity(
                        Intent(it, eu.kanade.tachiyomi.ui.cast.CastRemoteActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                }
            }
            return
        }
        if (openLivePlayback != null) {
            openLivePlayback?.invoke(value.channel)
            return
        }
        if (liveOwner.get()?.let {
                it.openLive(value.channel)
                true
            } == true
        ) {
            return
        }
        val activity = foreground.get() ?: return
        activity.startActivity(eu.kanade.tachiyomi.ui.tv.TvPlayerActivity.intent(activity, value.channel))
    }

    fun openSelectedLive() {
        readingSession.personal(false)
        tv.state.value.selection?.let(::openLiveSelection)
    }

    val reading = eu.kanade.tachiyomi.data.reading.ReadingRoomController(
        scope,
        text,
        SystemClock::elapsedRealtime,
        roomArchive::update,
        controller::sendReading,
    )

    val readingSession = eu.kanade.tachiyomi.data.reading.ReadingSessionController(
        scope,
        SystemClock::elapsedRealtime,
        controller::readingHostClock,
        controller::sendReadingSession,
    ) { selection ->
        if (selection != null) {
            navigation.select(controller.state.value.invite, "reading:${selection.revision}:${selection.chapter.key}")
            controller.sharedReadingSelected()
            eu.kanade.tachiyomi.data.reading.ReadingTogetherManager.get(application)
        }
    }

    fun resumePendingRoom(): Boolean {
        if (controller.active) return false
        val saved = roomArchive.load() ?: run {
            mutableRecoverable.value = false
            return false
        }
        val sequenceFloor = runCatching { roomArchive.reserveSequenceEpoch() }.getOrElse { return false }
        controller.resume(saved.invite, saved.identity.watchBytes(), saved.name, sequenceFloor)
        if (!controller.active) {
            mutableRecoverable.value = roomArchive.load() != null
            return false
        }
        reading.roomChanged(controller.state.value)
        reading.restore(saved.edits)
        readingSession.roomChanged(controller.readingSessionContext())
        readingSession.personal(saved.personalReading)
        if (controller.state.value.host) saved.reading?.let(readingSession::restore)
        tv.roomChanged(controller.tvContext())
        if (controller.state.value.host) saved.live?.let(tv::restore)
        if (controller.state.value.host) shortRooms.host(controller.state.value.invite, saved.name)
        mutableRecoverable.value = false
        return true
    }

    fun discardPendingRoom() {
        eu.kanade.tachiyomi.data.reading.ReadingTogetherManager.existing()?.forgetRoom()
        roomArchive.clear()
        mutableRecoverable.value = false
    }

    init {
        controller.onInteractionMessage = { sender, value ->
            interactions.roomChanged(controller.interactionContext())
            interactions.receive(sender, value)
        }
        controller.onReadingMessage = reading::receive
        controller.onReadingSessionMessage = { sender, value ->
            readingSession.roomChanged(controller.readingSessionContext())
            readingSession.receive(sender, value)
        }
        controller.onTvMessage = { sender, value ->
            tv.roomChanged(controller.tvContext())
            tv.receive(sender, value)
        }
        scope.launch {
            controller.state.collect { room ->
                interactions.roomChanged(controller.interactionContext())
                readingSession.roomChanged(controller.readingSessionContext())
                if (room.host && !room.sharedReadingMode) readingSession.exitReading()
                tv.roomChanged(controller.tvContext())
                if (!room.liveMode && tv.state.value.selection != null) {
                    if (room.host) tv.exit() else tv.suspendLive()
                }
                if (room.active && room.host && pendingTv != null) {
                    val channel = pendingTv!!
                    pendingTv = null
                    selectTv(channel)
                }
            }
        }
        scope.launch { tv.state.collect { if (controller.active) roomArchive.updateLive(it.selection) } }
        scope.launch {
            readingSession.state.collect {
                if (controller.active) roomArchive.updatePersonalReading(it.personal)
                if (controller.active &&
                    controller.state.value.host
                ) {
                    roomArchive.updateReading(readingSession.snapshot())
                }
            }
        }
        scope.launch { controller.state.collect { reading.roomChanged(it) } }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                present(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (foreground.get() === activity) foreground.clear()
            }
            override fun onActivityStopped(activity: Activity) {
                if (!activity.isChangingConfigurations) {
                    scope.launch {
                        delay(1000)
                        if (foreground.get() == null &&
                            (playerOwner.get() as? Activity)?.isInPictureInPictureMode != true &&
                            !persistentPlayback &&
                            !controller.observing &&
                            !controller.liveMode
                        ) {
                            controller.hold()
                        }
                    }
                }
            }
            override fun onActivityDestroyed(activity: Activity) {
                detach(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
        scope.launch {
            controller.state
                .distinctUntilChanged { old, new ->
                    old.active == new.active &&
                        old.host == new.host &&
                        old.invite == new.invite &&
                        old.media?.key == new.media?.key &&
                        old.sharedReadingMode == new.sharedReadingMode &&
                        old.liveMode == new.liveMode
                }
                .collectLatest { room ->
                    resolutionGeneration++
                    if (!room.host && !room.sharedReadingMode && !readingSession.state.value.personal) {
                        controller.setReadingMode(false)
                    }
                    if (!room.sharedReadingMode) {
                        navigation.select(room.invite, if (room.liveMode) "live" else "video:${room.media?.key}")
                    }
                    pendingOpen = null
                    selection = null
                    mutableOpening.value = WatchOpeningState()
                    if (!room.active) {
                        mutableRecoverable.value = roomArchive.load() != null
                        resolutionGeneration++
                        playback.forgetDetached()
                        application.stopService(Intent(application, WatchSessionService::class.java))
                    } else {
                        if (foreground.get() != null) {
                            runCatching {
                                application.startForegroundService(Intent(application, WatchSessionService::class.java))
                            }
                        }
                        if (!room.host &&
                            room.media != null &&
                            !controller.readingMode &&
                            !room.sharedReadingMode &&
                            !readingSession.state.value.personal &&
                            !room.liveMode &&
                            !controller.observing
                        ) {
                            liveOwner.get()?.finish()
                            resolve(room.media)
                        }
                    }
                }
        }
        scope.launch {
            controller.state.distinctUntilChanged { old, new ->
                old.active == new.active && old.host == new.host && old.upcoming?.key == new.upcoming?.key
            }.collectLatest { prepareUpcoming(it.upcoming.takeIf { _ -> it.active }) }
        }
    }

    private fun prepareUpcoming(media: WatchMedia?) {
        prepareJob?.cancel()
        if (media != null ||
            prepared?.remote?.key != controller.state.value.media?.key ||
            !controller.active
        ) {
            prepared = null
        }
        mutablePreparation.value = WatchOpeningState()
        if (media == null ||
            controller.state.value.host ||
            !controller.active ||
            controller.readingMode ||
            controller.liveMode ||
            controller.observing
        ) {
            return
        }
        prepareJob = scope.launch {
            mutablePreparation.value = WatchOpeningState(loading = true)
            try {
                val result = resolveCatalog(media)
                if (controller.active &&
                    !controller.state.value.host &&
                    controller.state.value.upcoming?.key == media.key
                ) {
                    prepared = result
                    mutablePreparation.value = WatchOpeningState()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                mutablePreparation.value =
                    WatchOpeningState(error = text(R.string.room_prepare_timeout), problem = WatchProblem.SourceError)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutablePreparation.value = WatchOpeningState(
                    error = e.message ?: text(R.string.room_next_prepare_failed),
                    problem = (e as? WatchResolveFailure)?.problem ?: WatchProblem.SourceError,
                )
            }
        }
    }

    fun retryPreparation() {
        if (!preparation.value.loading) prepareUpcoming(controller.state.value.upcoming)
    }

    fun present(activity: Activity) {
        foreground = WeakReference(activity)
        launchPending()
        eu.kanade.tachiyomi.data.reading.ReadingTogetherManager.existing()?.present(activity)
    }

    fun createRoom(name: String) {
        playback.forgetDetached()
        shortRooms.close()
        controller.create(name)
        if (controller.state.value.host) shortRooms.host(controller.state.value.invite, name)
    }

    fun joinRoom(code: String, name: String) {
        if (controller.active) controller.leave()
        shortRooms.close()
        if (code.matches(Regex("[0-9]{8}"))) {
            shortRooms.join(code, name)
        } else {
            joinResolvedInvite(code, name)
        }
    }

    fun attach(activity: Activity, player: WatchPlayer, open: (Long, Long) -> Unit) {
        attachPlayback(activity, player, persistent = false, open = open)
        present(activity)
    }

    fun attachPlayback(owner: Any, player: WatchPlayer, persistent: Boolean = true, open: (Long, Long) -> Unit) {
        if (controller.liveMode) {
            if (controller.state.value.host) tv.exit() else tv.suspendLive()
            controller.setLiveMode(false)
        }
        controller.setReadingMode(false)
        reading.suspendReading()
        playerOwner = WeakReference(owner)
        persistentPlayback = persistent
        playback.attach(player)
        openInPlayer = open
        controller.playerAttached()
    }

    fun becomeObserver(): Boolean {
        if (!controller.active || !controller.supportsObserver) return false
        val virtual = WatchObserverPlayer(currentPlayback(), SystemClock::elapsedRealtime)
        if (!controller.setObserver(true)) return false
        observerPlayer = virtual
        playback.attach(virtual)
        playerOwner.clear()
        openInPlayer = null
        persistentPlayback = false
        return true
    }

    fun detach(owner: Any) {
        if (playerOwner.get() !== owner) return
        playback.detach()
        if (!controller.active) playback.forgetDetached()
        openInPlayer = null
        playerOwner.clear()
        persistentPlayback = false
    }

    /** Compatibility clients can control only the attached ordinary video, never live/reader/Cast. */
    fun compatibilityPlayer(): WatchPlayer? = delegate.takeIf {
        !controller.active && !controller.liveMode && it !is WatchObserverPlayer
    }

    fun retryOpening() {
        val media = controller.state.value.media ?: return
        if (controller.state.value.host || !controller.active || opening.value.loading) return
        scope.launch { resolve(media) }
    }

    fun openSelectedVideo() {
        readingSession.personal(false)
        if (controller.liveMode) {
            openSelectedLive()
            return
        }
        val room = controller.state.value
        val media = room.media ?: return
        if (!room.active || opening.value.loading) return
        if (controller.liveMode) {
            if (controller.state.value.host) tv.exit() else tv.suspendLive()
            controller.setLiveMode(false)
        }
        controller.setReadingMode(false)
        reading.suspendReading()
        val activity = foreground.get() ?: return
        val owner = playerOwner.get()
        if (owner === activity && currentPlayback().media?.key == media.key) return
        scope.launch {
            resolve(media, openExisting = true)
            if (pendingOpen != null) {
                launchPending(allowObserver = true)
            }
        }
    }

    private fun currentPlayback(): WatchPlayback {
        if (controller.observing && !controller.state.value.host) observerPlayer?.follow(controller.state.value.media)
        val sample = playback.sample()
        val mapped = selection?.applyTo(sample) ?: sample
        return mapped.copy(
            problem = if (opening.value.loading) {
                WatchProblem.Opening
            } else {
                opening.value.problem.takeUnless {
                    it ==
                        WatchProblem.None
                }
                    ?: sample.problem
            },
            preparedNextKey = prepared?.remote?.key,
            nextProblem = if (preparation.value.loading) WatchProblem.Opening else preparation.value.problem,
        )
    }

    private suspend fun resolve(media: WatchMedia, openExisting: Boolean = false) {
        val generation = ++resolutionGeneration
        val navigationToken = navigation.select(controller.state.value.invite, "video:${media.key}")
        val wasHost = controller.state.value.host
        if (!openExisting && currentPlayback().media?.key == media.key) {
            mutableOpening.value = WatchOpeningState()
            return
        }
        mutableOpening.value = WatchOpeningState(loading = true)
        try {
            val cached = prepared?.takeIf {
                it.remote.key == media.key &&
                    Injekt.get<AnimeSourceManager>().get(media.sourceId) != null
            }
            val result = cached ?: resolveCatalog(media)
            if (generation == resolutionGeneration &&
                navigation.current(navigationToken) &&
                !controller.state.value.sharedReadingMode &&
                controller.active &&
                controller.state.value.host == wasHost &&
                controller.state.value.media?.key == media.key
            ) {
                selection = result.takeUnless { wasHost }
                pendingOpen = Triple(media.key, result.animeId, result.episodeId)
                pendingNavigationToken = navigationToken
                mutableOpening.value = WatchOpeningState()
                launchPending()
            }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                if (generation ==
                    resolutionGeneration
                ) {
                    mutableOpening.value =
                        WatchOpeningState(
                            error = text(R.string.room_source_timeout),
                            problem = WatchProblem.SourceError,
                        )
                }
            } else {
                throw e
            }
        } catch (e: Exception) {
            if (generation == resolutionGeneration) {
                mutableOpening.value = WatchOpeningState(
                    error =
                    (e as? IllegalArgumentException)?.message
                        ?: text(R.string.room_episode_load_failed),
                    problem = (e as? WatchResolveFailure)?.problem ?: WatchProblem.SourceError,
                )
            }
        }
    }

    private suspend fun resolveCatalog(media: WatchMedia): WatchSelection = withTimeout(45_000) {
        withContext(Dispatchers.IO) {
            val sourceManager = Injekt.get<AnimeSourceManager>()
            sourceManager.isInitialized.first { it }
            val source = sourceManager.get(media.sourceId)
                ?: throw WatchResolveFailure(
                    WatchProblem.MissingSource,
                    text(R.string.room_extension_required),
                )
            require(media.animeUrl.isNotBlank() && media.episodeUrl.isNotBlank()) {
                text(R.string.room_reference_missing)
            }
            require(
                source is AnimeHttpSource && WatchCatalogReference.isAllowed(media.animeUrl, source.baseUrl),
            ) {
                text(R.string.room_title_reference_error)
            }
            val entry = SAnime.create().apply {
                url = media.animeUrl
                title = media.title
            }
            val anime = Injekt.get<NetworkToLocalAnime>().await(entry.toDomainAnime(source.id))
            val getEpisodes = Injekt.get<GetEpisodesByAnimeId>()
            var episodes = getEpisodes.await(anime.id)
            var episode = episodes.singleOrNull { it.url == media.episodeUrl }
            if (episode == null) {
                Injekt.get<UpdateAnimeFromRemote>().awaitEpisodesUpdate(
                    source = source,
                    anime = anime,
                    fetchDetails = !anime.initialized,
                    fetchEpisodes = true,
                ).getOrThrow()
                episodes = getEpisodes.await(anime.id)
                episode = episodes.singleOrNull { it.url == media.episodeUrl }
                    ?: episodes.singleOrNull {
                        media.number > 0 &&
                            it.episodeNumber == media.number &&
                            WatchMedia.normalize(it.name) == WatchMedia.normalize(media.episode)
                    }
            }
            requireNotNull(episode) { text(R.string.room_episode_missing) }
            val local = media.copy(sourceId = source.id, animeUrl = anime.url, episodeUrl = episode.url)
            WatchSelection(media, local.key, anime.id, episode.id)
        }
    }

    private fun launchPending(allowObserver: Boolean = false) {
        val target = pendingOpen ?: return
        if (controller.observing && !allowObserver) return
        val activity = foreground.get() ?: return
        if (activity.isFinishing ||
            activity.isDestroyed ||
            !controller.active ||
            controller.readingMode ||
            !navigation.current(pendingNavigationToken) ||
            readingSession.state.value.personal ||
            controller.state.value.media?.key != target.first
        ) {
            return
        }
        pendingOpen = null
        if ((persistentPlayback || activity === playerOwner.get()) && openInPlayer != null) {
            openInPlayer?.invoke(target.second, target.third)
        } else {
            activity.startActivity(PlayerActivity.newIntent(activity, target.second, target.third))
        }
    }

    companion object {
        fun isNyanimeRoomActive(): Boolean = instance?.controller?.active == true
        fun isRoomActive(): Boolean = isNyanimeRoomActive() || SyncplayManager.isActive()
        fun existing(): WatchTogetherManager? = instance

        @Volatile private var instance: WatchTogetherManager? = null
        fun get(context: Context): WatchTogetherManager = instance ?: synchronized(this) {
            instance ?: WatchTogetherManager(context.applicationContext as Application).also { instance = it }
        }
    }
}

object WatchCatalogReference {
    fun isAllowed(reference: String, baseUrl: String): Boolean = runCatching {
        require(reference.isNotBlank() && !reference.startsWith("//") && !reference.contains('\\'))
        val base = URI(baseUrl)
        val uri = base.resolve(reference)
        uri.scheme in listOf("http", "https") &&
            uri.scheme.equals(base.scheme, ignoreCase = true) &&
            uri.host.equals(base.host, ignoreCase = true) &&
            uri.port == base.port &&
            uri.userInfo == null
    }.getOrDefault(false)
}
