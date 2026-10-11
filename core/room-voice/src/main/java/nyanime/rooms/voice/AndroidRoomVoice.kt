package nyanime.rooms.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID

/**
 * Audio-only peer connections. All native ownership is serialized on one worker and every
 * callback checks its connection lease. Loading this class does not initialize libwebrtc.
 * Media, camera and screen-capture APIs are deliberately absent.
 */
class AndroidRoomVoice(
    private val context: Context,
    private val localId: String,
    private val sessionId: String,
    private val signal: (String, RoomVoiceSignal) -> Unit,
    private val microphoneLost: () -> Unit,
) : AutoCloseable {
    private class Peer(val id: String, var connectionId: String, var revision: Long) {
        lateinit var connection: PeerConnection
        var remoteSet = false
        val remoteCandidates = ArrayDeque<IceCandidate>()
        val localCandidates = ArrayDeque<RoomVoiceCandidate>()
        var remoteAudio: AudioTrack? = null
        var retries = 0
        var recoveryScheduled = false
        var timeoutToken = 0L
    }
    private val mutable = MutableStateFlow(RoomVoiceState(active = true))
    val state = mutable.asStateFlow()
    private val thread = HandlerThread("NyanimeRoomVoice").apply { start() }
    private val worker = Handler(thread.looper)
    private val peers = linkedMapOf<String, Peer>()
    private var memberSessions = emptyMap<String, String>()
    private val earlyCandidates = mutableMapOf<String, MutableList<RoomVoiceSignal>>()
    private var factory: PeerConnectionFactory? = null
    private var module: JavaAudioDeviceModule? = null
    private val captureClocks = ThreadLocal<RoomVoiceCaptureClock>()
    private var source: AudioSource? = null
    private var microphone: AudioTrack? = null
    private var muted = true

    @Volatile private var closed = false

    fun update(members: Map<String, String>, microphoneMuted: Boolean) = dispatch {
        if (!initialize()) return@dispatch
        peers.keys.filter { it !in members || memberSessions[it] != members[it] }.toList().forEach(::remove)
        memberSessions = members
        earlyCandidates.keys.retainAll(members.keys)
        setMutedInternal(microphoneMuted)
        members.keys.filter { it != localId && it !in peers }.take(7).forEachIndexed { index, id ->
            val peer = create(id, UUID.randomUUID().toString().replace("-", ""), 1) ?: return@forEachIndexed
            if (localId < id) worker.postDelayed({ if (owns(peer)) offer(peer, false) }, index * 350L)
            timeout(peer)
        }
    }

    fun receive(id: String, value: RoomVoiceSignal) = dispatch {
        if (!value.valid() ||
            id == localId ||
            value.senderSession != memberSessions[id] ||
            value.targetSession != sessionId ||
            !initialize()
        ) {
            return@dispatch
        }
        var peer = peers[id] ?: return@dispatch
        if (value.type == RoomVoiceSignalType.Candidates &&
            id < localId &&
            (peer.connectionId != value.connection || value.revision > peer.revision)
        ) {
            val pending = earlyCandidates.getOrPut(id) { mutableListOf() }
            if (pending.size < 8) pending.add(value)
            return@dispatch
        }
        if (value.type == RoomVoiceSignalType.Offer) {
            if (id >= localId) return@dispatch
            if (peer.connectionId != value.connection) {
                remove(id)
                peer = create(id, value.connection, value.revision) ?: return@dispatch
            } else if (value.revision < peer.revision) {
                return@dispatch
            }
            if (peer.revision != value.revision) {
                peer.localCandidates.clear()
                peer.remoteCandidates.clear()
            }
            peer.revision = value.revision
            peer.remoteSet = false
            earlyCandidates.remove(id)?.filter { it.connection == value.connection && it.revision == value.revision }
                ?.flatMap { it.candidates }?.take(64)?.forEach {
                    peer.remoteCandidates.addLast(IceCandidate(it.mid, it.line, it.candidate))
                }
            val current = peer
            current.connection.setRemoteDescription(
                observer(current, set = {
                    remoteReady(current)
                    current.connection.createAnswer(
                        observer(current, created = { answer ->
                            current.connection.setLocalDescription(
                                observer(current, set = {
                                    emit(current, RoomVoiceSignalType.Answer, answer.description)
                                }),
                                answer,
                            )
                        }),
                        constraints(),
                    )
                }),
                SessionDescription(SessionDescription.Type.OFFER, value.description),
            )
            timeout(current)
            return@dispatch
        }
        if (value.connection != peer.connectionId || value.revision != peer.revision) return@dispatch
        when (value.type) {
            RoomVoiceSignalType.Answer -> if (localId < id) {
                val current = peer
                current.connection.setRemoteDescription(
                    observer(current, set = { remoteReady(current) }),
                    SessionDescription(SessionDescription.Type.ANSWER, value.description),
                )
            }
            RoomVoiceSignalType.Candidates -> value.candidates.forEach {
                val candidate = IceCandidate(it.mid, it.line, it.candidate)
                if (peer.remoteSet) {
                    peer.connection.addIceCandidate(candidate)
                } else if (peer.remoteCandidates.size < 64) {
                    peer.remoteCandidates.addLast(candidate)
                }
            }
            RoomVoiceSignalType.Restart -> if (localId < id) recover(peer)
            else -> Unit
        }
    }

    fun setGain(value: Float) = dispatch {
        val gain = value.coerceIn(0f, 1f)
        mutable.value = state.value.copy(gain = gain)
        peers.values.forEach { it.remoteAudio?.setVolume(gain.toDouble()) }
    }

    fun retry() = dispatch {
        peers.values.filter { state.value.peers[it.id]?.phase == RoomVoicePhase.Unavailable }.forEach {
            it.retries = 0
            recover(it)
        }
    }

    private fun initialize(): Boolean {
        if (factory != null) return true
        return try {
            synchronized(initializationLock) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .setEnableInternalTracer(false).createInitializationOptions(),
                    )
                    initialized = true
                }
            }
            module = JavaAudioDeviceModule.builder(context.applicationContext)
                .setAudioBufferCallback { _, _, _, _, bytesRead, captureTimeNs ->
                    val clock = captureClocks.get() ?: RoomVoiceCaptureClock().also(captureClocks::set)
                    clock.captureTimestamp(bytesRead, captureTimeNs)
                }
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(error: String?) = microphoneError()
                    override fun onWebRtcAudioRecordStartError(
                        code: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                        error: String?,
                    ) = microphoneError()
                    override fun onWebRtcAudioRecordError(error: String?) = microphoneError()
                }).createAudioDeviceModule().apply { setAudioRecordEnabled(false) }
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
            source = factory!!.createAudioSource(
                MediaConstraints().apply {
                    optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                },
            )
            microphone = factory!!.createAudioTrack("room-microphone", source).apply { setEnabled(false) }
            true
        } catch (_: Exception) {
            unavailable()
            false
        } catch (_: LinkageError) {
            unavailable()
            false
        }
    }

    private fun create(id: String, token: String, revision: Long): Peer? {
        if (id == localId || !id.matches(Regex("[0-9a-f]{64}")) || peers.size >= 7) return null
        val entry = Peer(id, token, revision)
        val configuration = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            ),
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory?.createPeerConnection(
            configuration,
            object : PeerConnection.Observer {
                override fun onSignalingChange(value: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(value: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(value: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onIceCandidate(candidate: IceCandidate) = dispatch {
                    if (!owns(entry) || entry.localCandidates.size >= 64) return@dispatch
                    entry.localCandidates.addLast(
                        RoomVoiceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp),
                    )
                    worker.postDelayed({ if (owns(entry)) flushCandidates(entry) }, 250)
                }
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = dispatch {
                    if (!owns(entry)) return@dispatch
                    (receiver.track() as? AudioTrack)?.let {
                        entry.remoteAudio = it
                        it.setVolume(state.value.gain.toDouble())
                    }
                }
                override fun onConnectionChange(value: PeerConnection.PeerConnectionState) = dispatch {
                    if (!owns(entry)) return@dispatch
                    when (value) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            entry.timeoutToken++
                            entry.retries = 0
                            updatePeer(entry, RoomVoicePhase.Connected)
                        }
                        PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            updatePeer(entry, RoomVoicePhase.Reconnecting)
                            scheduleRecovery(entry)
                        }
                        else -> Unit
                    }
                }
            },
        ) ?: return null
        entry.connection = pc
        peers[id] = entry
        pc.addTrack(microphone, listOf("room-voice"))
        updatePeer(entry, RoomVoicePhase.Connecting)
        return entry
    }

    private fun offer(peer: Peer, restart: Boolean) {
        if (!owns(peer)) return
        val constraints = constraints().apply {
            if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peer.connection.createOffer(
            observer(peer, created = { offer ->
                peer.connection.setLocalDescription(
                    observer(peer, set = {
                        emit(peer, RoomVoiceSignalType.Offer, offer.description)
                    }),
                    offer,
                )
            }),
            constraints,
        )
    }

    private fun remoteReady(peer: Peer) {
        peer.remoteSet = true
        while (peer.remoteCandidates.isNotEmpty()) peer.connection.addIceCandidate(peer.remoteCandidates.removeFirst())
    }

    private fun flushCandidates(peer: Peer) {
        if (peer.localCandidates.isEmpty()) return
        val values = mutableListOf<RoomVoiceCandidate>()
        repeat(minOf(8, peer.localCandidates.size)) { values.add(peer.localCandidates.removeFirst()) }
        signal(
            peer.id,
            RoomVoiceSignal(
                peer.connectionId,
                peer.revision,
                RoomVoiceSignalType.Candidates,
                candidates = values,
                senderSession = sessionId,
                targetSession = memberSessions[peer.id].orEmpty(),
            ),
        )
        if (peer.localCandidates.isNotEmpty()) worker.postDelayed({ if (owns(peer)) flushCandidates(peer) }, 250)
    }

    private fun emit(peer: Peer, type: RoomVoiceSignalType, description: String = "") {
        val value = RoomVoiceSignal(
            peer.connectionId,
            peer.revision,
            type,
            description,
            senderSession = sessionId,
            targetSession = memberSessions[peer.id].orEmpty(),
        )
        if (value.valid()) signal(peer.id, value) else updatePeer(peer, RoomVoicePhase.Unavailable)
    }

    private fun observer(peer: Peer, created: (SessionDescription) -> Unit = {}, set: () -> Unit = {}): SdpObserver =
        object : SdpObserver {
            override fun onCreateSuccess(value: SessionDescription) = dispatch { if (owns(peer)) created(value) }
            override fun onSetSuccess() = dispatch { if (owns(peer)) set() }
            override fun onCreateFailure(error: String?) = dispatch { if (owns(peer)) scheduleRecovery(peer) }
            override fun onSetFailure(error: String?) = dispatch { if (owns(peer)) scheduleRecovery(peer) }
        }

    private fun scheduleRecovery(peer: Peer) {
        if (peer.recoveryScheduled) return
        peer.timeoutToken++
        peer.recoveryScheduled = true
        worker.postDelayed({
            if (owns(peer)) {
                peer.recoveryScheduled = false
                if (state.value.peers[peer.id]?.phase != RoomVoicePhase.Connected) recover(peer)
            }
        }, 3000)
    }

    private fun recover(peer: Peer) {
        if (peer.retries++ >= 3) {
            updatePeer(peer, RoomVoicePhase.Unavailable)
            return
        }
        updatePeer(peer, RoomVoicePhase.Reconnecting)
        peer.remoteCandidates.clear()
        peer.localCandidates.clear()
        if (localId < peer.id) {
            peer.revision++
            peer.remoteSet = false
            offer(peer, true)
        } else {
            emit(peer, RoomVoiceSignalType.Restart)
        }
        timeout(peer)
    }

    private fun timeout(peer: Peer) {
        val revision = peer.revision
        val token = ++peer.timeoutToken
        worker.postDelayed({
            if (owns(peer) &&
                peer.revision == revision &&
                peer.timeoutToken == token &&
                state.value.peers[peer.id]?.phase != RoomVoicePhase.Connected &&
                state.value.peers[peer.id]?.phase != RoomVoicePhase.Unavailable
            ) {
                recover(peer)
            }
        }, 20_000)
    }

    private fun setMutedInternal(value: Boolean) {
        if (muted != value) {
            muted = value
            // Voice accompanies media playback. Changing the global audio mode here would
            // reroute the video to the phone earpiece and disrupt the user's media output.
            // The device module uses USAGE_MEDIA and leaves routing to Android.
            if (!value) mutable.value = state.value.copy(microphoneUnavailable = false)
            module?.setAudioRecordEnabled(!value)
            microphone?.setEnabled(!value)
        }
        mutable.value = state.value.copy(muted = value)
    }

    private fun microphoneError() = dispatch {
        setMutedInternal(true)
        mutable.value = state.value.copy(microphoneUnavailable = true)
        microphoneLost()
    }

    private fun updatePeer(peer: Peer, phase: RoomVoicePhase) {
        mutable.value = state.value.copy(peers = state.value.peers + (peer.id to RoomVoicePeer(phase)))
    }

    private fun unavailable() {
        muted = true
        mutable.value = state.value.copy(available = false, muted = true)
        release()
        microphoneLost()
    }

    private fun remove(id: String) {
        peers.remove(id)?.let {
            it.connection.close()
            it.connection.dispose()
        }
        mutable.value = state.value.copy(peers = state.value.peers - id)
    }

    private fun owns(peer: Peer): Boolean = !closed && peers[peer.id] === peer
    private fun dispatch(action: () -> Unit) {
        if (!closed) {
            worker.post {
                if (!closed) {
                    try {
                        action()
                    } catch (
                        _: Exception,
                    ) {
                        unavailable()
                    } catch (_: LinkageError) {
                        unavailable()
                    }
                }
            }
        }
    }

    private fun release() {
        peers.keys.toList().forEach(::remove)
        earlyCandidates.clear()
        microphone?.dispose()
        microphone = null
        source?.dispose()
        source = null
        factory?.dispose()
        factory = null
        module?.release()
        module = null
    }

    override fun close() {
        if (closed) return
        closed = true
        mutable.value = RoomVoiceState()
        worker.post {
            try {
                release()
            } finally {
                worker.removeCallbacksAndMessages(null)
                thread.quitSafely()
            }
        }
    }

    companion object {
        private val initializationLock = Any()
        private var initialized = false
        private fun constraints() = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
    }
}
