package eu.kanade.tachiyomi.data.watch

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import nyanime.rooms.voice.AndroidRoomVoice
import nyanime.rooms.voice.RoomVoiceSignal
import nyanime.rooms.voice.RoomVoiceState

/** The application owns voice independently of activities, gaze and native video sessions. */
class WatchRoomVoiceCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val interactions: WatchInteractionSession,
) {
    private val mutable = MutableStateFlow(RoomVoiceState())
    val state = mutable.asStateFlow()
    private var engine: AndroidRoomVoice? = null
    private var collection: Job? = null
    private var session = ""
    private var members = emptyMap<String, WatchVoicePresence>()
    private var lastMembers = emptyMap<String, String>()
    private var lastMuted = true
    private var generation = 0L

    init {
        interactions.onVoicePeers = ::peersChanged
        interactions.onVoiceSignal = { peer, encoded ->
            runCatching { watchJson.decodeFromString<RoomVoiceSignal>(encoded) }.getOrNull()
                ?.takeIf { it.valid() }?.let { engine?.receive(peer, it) }
        }
    }

    fun requestJoin() {
        if (!interactions.state.value.supported || state.value.active) return
        request(WatchSessionService.JOIN_VOICE)
    }

    fun requestMicrophone() {
        if (!state.value.active ||
            ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        request(WatchSessionService.UNMUTE_VOICE)
    }

    private fun request(action: String) {
        runCatching {
            ContextCompat.startForegroundService(
                application,
                Intent(application, WatchSessionService::class.java).setAction(action),
            )
        }.onFailure { mutable.value = state.value.copy(available = false) }
    }

    fun serviceUnavailable() {
        leave()
        mutable.value = state.value.copy(available = false)
    }

    /** Called only after the Android foreground-service type has been established. */
    fun serviceJoin() {
        if (!interactions.state.value.supported || state.value.active) return
        interactions.setVoice(true, true)
        val own = interactions.state.value.localId
        session = interactions.state.value.voices[own]?.session.orEmpty()
        val token = ++generation
        val voice = AndroidRoomVoice(application, own, session, signal = { peer, value ->
            scope.launch {
                if (token == generation) interactions.voiceSignal(peer, watchJson.encodeToString(value))
            }
        }, microphoneLost = {
            scope.launch { if (token == generation) mute() }
        })
        engine = voice
        mutable.value = RoomVoiceState(active = true)
        collection = scope.launch {
            voice.state.collect { if (token == generation) mutable.value = it }
        }
        lastMembers = emptyMap()
        lastMuted = true
        updateEngine()
    }

    fun unmute() {
        if (!state.value.active ||
            ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        interactions.setVoice(true, false)
    }

    fun mute() {
        if (state.value.active) interactions.setVoice(true, true)
    }

    fun setGain(value: Float) = engine?.setGain(value)

    fun retry() {
        lastMembers = emptyMap()
        updateEngine()
        engine?.retry()
    }

    private fun peersChanged(value: Map<String, WatchVoicePresence>) {
        members = value
        val own = interactions.state.value.localId
        if (engine != null && value[own]?.joined != true) {
            release()
        } else {
            updateEngine()
        }
    }

    private fun updateEngine() {
        val own = interactions.state.value.localId
        val joined = members.filter { it.value.joined && it.key != own }.mapValues { it.value.session }
        val muted = members[own]?.muted != false
        if (joined != lastMembers ||
            muted != lastMuted ||
            state.value.peers.size != joined.size ||
            !state.value.available
        ) {
            lastMembers = joined
            lastMuted = muted
            engine?.update(joined, muted)
        }
    }

    fun leave() {
        interactions.setVoice(false)
        release()
    }

    private fun release() {
        generation++
        collection?.cancel()
        collection = null
        engine?.close()
        engine = null
        session = ""
        lastMembers = emptyMap()
        mutable.value = RoomVoiceState()
    }
}
