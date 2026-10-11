package eu.kanade.tachiyomi.ui.player.session

import android.os.SystemClock
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.tv.TvMpvOutput
import eu.kanade.tachiyomi.data.tv.TvNativeEvents
import eu.kanade.tachiyomi.data.tv.TvPlaybackFailure
import eu.kanade.tachiyomi.data.tv.TvPlayerSession
import eu.kanade.tachiyomi.data.tv.TvServices
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.tv.TvAnime4K
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvLiveAdjustment
import nyanime.tv.api.TvLiveObservation
import nyanime.tv.api.TvPlayback
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Channel playback uses the same renderer and surface host as episodes, with its existing live recovery. */
internal class LivePlayback(private val owner: PlayerSession, parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    val services: TvServices = Injekt.get()
    private val preferences: AdvancedPlayerPreferences = Injekt.get()
    private val base: BasePreferences = Injekt.get()
    private val rooms = WatchTogetherManager.get(owner)
    private val enhancement = TvAnime4K(owner, owner.player, scope) { owner.available }
    val output: TvMpvOutput = TvMpvOutput(
        scope,
        object : TvNativeEvents {
            override fun loaded(ticket: Long) {
                session.loaded(ticket)
                enhancement.loaded()
            }
            override fun buffering(ticket: Long, value: Boolean) {
                session.buffering(ticket, value)
            }
            override fun failed(
                ticket: Long,
                failure: TvPlaybackFailure,
            ) {
                owner.replacementReady()
                session.failed(ticket, failure)
            }
        },
        enhancement::enabled,
        {},
    )
    val session: TvPlayerSession = TvPlayerSession(
        scope,
        services.repository,
        output,
        SystemClock::elapsedRealtime,
        { preferences.anime4kSmartAutoStart().get() },
        { base.incognitoMode().get() },
        owner::requestAudioFocus,
    )

    fun start(reference: TvChannelRef, prepared: TvPlayback) {
        enhancement.prepare()
        output.attach()
        rooms.attachLivePlayback(this, ::observation, ::adjustment, owner::openLive)
        scope.launch { rooms.controller.state.collect { session.room(it.active) } }
        scope.launch {
            var remoteShown = false
            eu.kanade.tachiyomi.data.cast.CastController.get(owner).state.collect { cast ->
                val remote = cast.active && cast.media?.live?.reference == session.state.value.reference
                if (remote && !remoteShown) {
                    session.pause(true)
                    owner.startActivity(
                        android.content.Intent(owner, eu.kanade.tachiyomi.ui.cast.CastRemoteActivity::class.java),
                    )
                }
                remoteShown = remote
            }
        }
        scope.launch {
            var lastPip: Triple<String?, Boolean, Double?>? = null
            var lastPaused: Boolean? = null
            combine(session.state, output.status) { state, status -> state to status }.collect {
                if (it.first.ready && rooms.controller.observing) rooms.controller.setObserver(false)
                owner.publishSnapshot()
                if (lastPaused != it.first.paused) {
                    lastPaused = it.first.paused
                    owner.setKeepScreenOn(!it.first.paused)
                }
                rooms.tv.sourceMissing(
                    it.first.failed && services.registry.state.value[it.first.reference?.sourceId] == null,
                )
                val key = Triple(it.first.media?.channel?.name, it.first.paused, it.second.aspect)
                if (lastPip != key) {
                    lastPip = key
                    owner.setPictureInPictureParams(owner.createPipParams())
                }
            }
        }
        scope.launch {
            var previous = services.registry.state.value
            combine(services.registry.state, services.adultContent) { sources, adult ->
                sources to adult
            }.collect { (sources, adult) ->
                val state = session.state.value
                val reference = state.reference
                if (reference != null) {
                    if (sources[reference.sourceId] == null ||
                        !adult &&
                        state.media?.channel?.adult == true
                    ) {
                        session.unavailable()
                    } else if (previous[reference.sourceId] != null &&
                        previous[reference.sourceId] !== sources[reference.sourceId]
                    ) {
                        owner.openLive(reference)
                    }
                }
                previous = sources
            }
        }
        session.select(reference, prepared)
    }

    fun select(reference: TvChannelRef, prepared: TvPlayback) = session.select(reference, prepared)
    fun observation(): TvLiveObservation? {
        val state = session.state.value
        val position = output.status.value.position ?: return null
        return TvLiveObservation(
            position,
            state.stream?.clock,
            state.paused,
            state.loading || state.buffering,
            state.stream?.adjustableSpeed == true,
        )
    }
    fun adjustment(value: TvLiveAdjustment) = session.speed(
        when (value) {
            is TvLiveAdjustment.Aligned -> value.speed
            is TvLiveAdjustment.Gradual -> value.speed
            else -> 1.0
        },
    )

    fun close() {
        rooms.detachLivePlayback(this)
        enhancement.close()
        session.close()
        scope.cancel()
    }
}
