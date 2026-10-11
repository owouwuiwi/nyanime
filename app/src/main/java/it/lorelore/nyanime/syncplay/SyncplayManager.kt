package it.lorelore.nyanime.syncplay

import android.content.Context
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.data.watch.WatchDriftCorrector
import eu.kanade.tachiyomi.data.watch.WatchPlayback
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Video-only compatibility session. No source IDs, URLs, headers or credentials enter the protocol. */
class SyncplayManager private constructor(context: Context) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("syncplay_compatibility", Context.MODE_PRIVATE)
    val server get() = preferences.getString("server", "").orEmpty()
    val nickname get() = preferences.getString("nickname", "").orEmpty()
    val room get() = preferences.getString("room", "").orEmpty()
    val tls get() = preferences.getBoolean("tls", true)
    private var client: SyncplayClient? = null
    private var session: Job? = null
    private var controllerScope: CoroutineScope? = null
    private var timeline: SyncplayTimeline? = null
    private val drift = WatchDriftCorrector()
    private var suppressUntil = 0L
    private var lastLocal: WatchPlayback? = null
    private var lastLocalAt = 0L
    private var expectedSpeed = 1.0
    private var speedCaptured = false
    private var commandedPause: Boolean? = null
    private var localKey: String? = null
    private var pendingSeek = false
    private var receivedAt = 0L
    private var appliedExplicitAt = 0L
    private var approvedKey: String? = null
    private val mutableState = MutableStateFlow(SyncplayState())
    val state = mutableState.asStateFlow()
    private val mutableVideo = MutableStateFlow("")
    val video = mutableVideo.asStateFlow()
    private val mutablePaused = MutableStateFlow(true)
    val paused = mutablePaused.asStateFlow()
    private val mutableApproved = MutableStateFlow(false)
    val approved = mutableApproved.asStateFlow()
    private val mutableAligning = MutableStateFlow(false)
    val aligning = mutableAligning.asStateFlow()

    fun connect(options: SyncplayOptions) {
        check(!WatchTogetherManager.isNyanimeRoomActive())
        options.validate()
        leave()
        preferences.edit().putString(
            "server",
            options.endpoint.let { endpoint ->
                if (':' in endpoint.host) "[${endpoint.host}]:${endpoint.port}" else "${endpoint.host}:${endpoint.port}"
            },
        ).putString("nickname", options.nickname).putString("room", options.room).putBoolean("tls", options.tls).apply()
        val current = SyncplayClient(options)
        client = current
        activity.value = true
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        controllerScope = ownScope
        current.start(ownScope)
        ownScope.launch {
            current.timeline.collect { value ->
                timeline = value
                if (value != null) commandedPause = value.remote.paused
                if (value?.remote?.seek == true) pendingSeek = true
                receivedAt = SyncplayClient.now()
            }
        }
        ownScope.launch {
            current.state.collect { value ->
                mutableState.value = value
                if (value.phase == SyncplayPhase.Failed) {
                    restoreSpeed()
                    activity.value = false
                    session?.cancel()
                }
            }
        }
        session = ownScope.launch {
            while (client === current) {
                tick(current)
                delay(250)
            }
        }
    }

    private fun player() = WatchTogetherManager.existing()?.compatibilityPlayer()
    private fun sample(): WatchPlayback? {
        val cast = CastController.get(context).state.value
        if (cast.active || cast.connecting || WatchTogetherManager.isNyanimeRoomActive()) return null
        return player()?.sample()?.takeIf { it.media?.valid() == true && it.position.isFinite() }
    }

    private fun local(sample: WatchPlayback?): SyncplayLocal {
        val media = sample?.media
        return SyncplayLocal(
            position = sample?.position?.coerceIn(0.0, 86400.0) ?: 0.0,
            paused = commandedPause ?: sample?.paused ?: true,
            ready = sample?.ready == true && !sample.buffering && sample.media?.key == approvedKey && !aligning.value,
            file = if (media == null) "" else (media.title + " · " + media.episode).take(480),
            duration = media?.duration?.coerceIn(0.0, 86400.0) ?: 0.0,
        )
    }

    private fun tick(current: SyncplayClient) {
        if (!isActive()) return
        val cast = CastController.get(context).state.value
        if (cast.active || cast.connecting) {
            leave()
            return
        }
        val now = SyncplayClient.now()
        val sample = sample()
        if (sample != null && !speedCaptured) {
            expectedSpeed = sample.speed
            speedCaptured = true
        }
        mutablePaused.value = sample?.paused ?: true
        mutableVideo.value = local(sample).file
        if (sample?.media?.key != localKey) {
            localKey = sample?.media?.key
            lastLocal = null
            commandedPause = null
            approvedKey = null
            mutableApproved.value = false
            mutableAligning.value = false
            suppressUntil = now + 1500
            drift.reset()
        }
        // Buffering is readiness, not a user pause or a shared timeline change.
        if (sample != null &&
            approvedKey != null &&
            sample.media?.key == approvedKey &&
            (!sample.ready || sample.buffering) &&
            !aligning.value
        ) {
            mutableAligning.value = true
            pendingSeek = true
            suppressUntil = 0
            lastLocal = null
            drift.reset()
        }
        current.observe(local(sample))
        if (sample == null ||
            sample.media?.key != approvedKey ||
            !sample.ready ||
            sample.buffering ||
            sample.media?.duration?.let { it > 0 } != true
        ) {
            lastLocal = null
            drift.reset()
            return
        }
        if (state.value.phase != SyncplayPhase.Connected) return
        val previous = lastLocal.takeUnless { aligning.value }
        if (previous != null && now >= suppressUntil) {
            val predicted =
                previous.position + if (previous.paused) 0.0 else (now - lastLocalAt) / 1000.0 * previous.speed
            val seek = abs(sample.position - predicted) > 1.5
            if (sample.paused != previous.paused || seek) {
                commandedPause = sample.paused
                current.change(local(sample), seek)
                suppressUntil = now + 1000
            }
        }
        lastLocal = sample.takeUnless { aligning.value }
        lastLocalAt = now
        if (now < suppressUntil || now - receivedAt > 5000) return
        val remote = timeline ?: return
        val duration = sample.media!!.duration
        val target = (
            remote.remote.position +
                if (remote.remote.paused) 0.0 else (now - remote.receivedAtMillis).coerceAtLeast(0) / 1000.0
            )
            .coerceIn(0.0, duration)
        val port = player() ?: return
        // Joining is initially read-only: asynchronous local seek/pause must settle before
        // reporting a position that the server could use as the shared room clock.
        if (aligning.value &&
            !pendingSeek &&
            abs(sample.position - target) <= 1.0 &&
            sample.paused == remote.remote.paused
        ) {
            mutableAligning.value = false
            lastLocal = null
            suppressUntil = now + 500
            current.observe(local(sample))
            return
        }
        if (pendingSeek && remote.receivedAtMillis > appliedExplicitAt) {
            pendingSeek = false
            appliedExplicitAt = remote.receivedAtMillis
            if (abs(sample.position - target) > .3) port.seek(target)
            suppressUntil = now + 1000
            drift.reset()
        }
        if (sample.paused != remote.remote.paused) {
            port.pause(remote.remote.paused)
            commandedPause = remote.remote.paused
            suppressUntil = now + 1000
        }
        if (now < suppressUntil) return
        if (sample.scrubbing) {
            lastLocal = null
            drift.reset()
            return
        }
        val correction = drift.correct(sample.position, target, 1.0, remote.remote.paused, true, now)
        correction.seek?.let {
            port.seek(it)
            suppressUntil = now + 1000
        }
        if (abs(sample.speed - correction.speed) > .002) port.speed(correction.speed)
    }

    fun requestPause(paused: Boolean): Boolean {
        if (!isActive()) return false
        val sample = sample() ?: return true
        if (sample.media?.key != approvedKey) return false
        if (state.value.phase != SyncplayPhase.Connected || aligning.value) return true
        commandedPause = paused
        player()?.pause(paused)
        if (!paused) player()?.userResumed()
        client?.change(local(sample).copy(paused = paused))
        receivedAt = 0L
        suppressUntil = SyncplayClient.now() + 1000
        return true
    }

    fun requestSeek(seconds: Double): Boolean {
        if (!isActive()) return false
        val sample = sample() ?: return true
        if (sample.media?.key != approvedKey) return false
        val duration = sample.media?.duration ?: return true
        if (!seconds.isFinite() ||
            duration <= 0.0 ||
            !sample.ready ||
            state.value.phase != SyncplayPhase.Connected ||
            aligning.value
        ) {
            return true
        }
        val target = seconds.coerceIn(0.0, duration)
        player()?.seek(target)
        client?.change(local(sample).copy(position = target), seek = true)
        receivedAt = 0L
        suppressUntil = SyncplayClient.now() + 1000
        drift.reset()
        return true
    }

    fun chat(value: String) = client?.chat(value) == true

    fun approveVideo() {
        val sample = sample() ?: return
        if (!sample.ready || sample.buffering || state.value.phase != SyncplayPhase.Connected) return
        approvedKey = sample.media?.key
        mutableApproved.value = approvedKey != null
        mutableAligning.value = approvedKey != null
        pendingSeek = true
        appliedExplicitAt = 0
        suppressUntil = 0
        commandedPause = timeline?.remote?.paused
        lastLocal = null
        drift.reset()
    }

    private fun restoreSpeed() {
        val port = player()
        if (speedCaptured && port?.sample()?.media?.key == localKey) port?.speed(expectedSpeed.coerceIn(.25, 3.0))
        commandedPause = null
        lastLocal = null
        mutableAligning.value = false
        drift.reset()
    }

    fun leave() {
        client?.close()
        client = null
        session?.cancel()
        controllerScope?.cancel()
        controllerScope = null
        session = null
        restoreSpeed()
        speedCaptured = false
        timeline = null
        localKey = null
        pendingSeek = false
        appliedExplicitAt = 0
        approvedKey = null
        mutableApproved.value = false
        mutableState.value = SyncplayState()
        activity.value = false
    }

    companion object {
        private val activity = MutableStateFlow(false)
        val active = activity.asStateFlow()
        fun isActive() = activity.value

        @Volatile private var instance: SyncplayManager? = null
        fun existing() = instance
        fun get(context: Context): SyncplayManager = instance ?: synchronized(this) {
            instance ?: SyncplayManager(context).also { instance = it }
        }
    }
}
