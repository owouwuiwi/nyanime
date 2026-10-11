package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.reading.ReadingEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The creator orders commands; followers never publish player observations as commands.
 * Explicit user intent, group buffering and local safety holds have separate ownership.
 * A lost creator ends in a paused room, never in a competing leader/split-brain session.
 */
class WatchRoomController(
    private val scope: CoroutineScope,
    private val text: RoomText,
    private val player: WatchPlayer,
    private val now: () -> Long,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val transportFactory: (WatchInvite, WatchIdentity) -> WatchTransport = { invite, identity ->
        NostrWatchTransport(invite, identity)
    },
    private val onSessionStarted: (WatchInvite, WatchIdentity, String) -> Unit = { _, _, _ -> },
    private val onSessionEnded: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(WatchRoomState())
    val state = mutableState.asStateFlow()
    val active: Boolean get() = state.value.active
    private var transport: WatchTransport? = null
    private var invite: WatchInvite? = null
    private var ticker: Job? = null
    private var generation = 0L
    private var sequence = 0L
    private var sessionSequenceFloor = 0L
    private var started = 0L
    private var lastHostMessage = 0L
    private var lastBroadcast = -10_000L
    private var lastStatus = -10_000L
    private var lastPing = -10_000L
    private var lastSeek = -10_000L
    private var lastRetry = 0L
    private var pendingSince = 0L
    private var pendingCommand: WatchMessage? = null
    private var lastStatusValue: WatchPeerStatus? = null
    private var clock = WatchClock()
    private var timeline: WatchMessage? = null
    private var ownerKey = ""
    private var interactionEpoch = watchRandom(16).watchHex()
    private var ownerInteractionEpoch = ""
    private var ownerModeSequence = 0L
    private val pings = mutableSetOf<Long>()
    private val peers = linkedMapOf<String, Pair<WatchPeerStatus, Long>>()
    private val peerLoadingSince = mutableMapOf<String, Long>()
    private val sequences = mutableMapOf<String, Long>()
    private val acknowledgements = mutableMapOf<String, Long>()
    private val departed = linkedMapOf<String, Long>()
    private val removedMembers = linkedSetOf<String>()
    private val memberRequests = linkedMapOf<String, WatchPeerStatus>()
    private var name = ""
    private var desiredPaused = true
    private var hasPlayedCurrentMedia = false
    private var baseSpeed = 1.0
    private var originalSpeed = 1.0
    private var appliedSpeed: Double? = null
    private val managedSpeeds = ArrayDeque<Double>()
    private var seekRevision = 0L
    private var appliedSeekRevision = -1L
    private var pendingSeek: Double? = null
    private var pendingSeekSince = 0L
    private var acceptedMedia: String? = null
    private var closedMessage = ""
    private var failedCommand: WatchMessage? = null
    private var waitingSince: Long? = null
    private var feedbackMediaKey: String? = null
    private var lastManualRetry = -10_000L
    private var activitySequence = 0L
    private var lastSeenActivity = 0L
    private var activityUntil = 0L
    private var publishedActivity: WatchActivity? = null
    private val startGate = WatchStartGate()
    private val prebufferGate = WatchPrebufferGate()
    private val driftCorrector = WatchDriftCorrector()
    private val hostTimeline = WatchPlaybackTimeline()
    private val durationVerifier = WatchDurationVerifier()
    private var pausedBy = ""
    private var cueSequence = 0L
    private var skipKey: String? = null
    private var skipCue: WatchSkip? = null
    private var skipWait: Long? = null
    private val suppressedSkips = linkedSetOf<String>()
    private var nextCue: WatchNext? = null
    private var suppressedNext: String? = null
    private var advancedFrom: String? = null
    var observing: Boolean = false
        private set

    val supportsObserver: Boolean get() = active &&
        if (state.value.host) {
            peers.values.all { it.first.observerVersion == 1 }
        } else {
            timeline?.observerVersion == 1 && timeline?.peers?.values?.all { it.observerVersion == 1 } == true
        }

    fun setObserver(enabled: Boolean): Boolean {
        if (enabled && !supportsObserver) return false
        if (observing == enabled) return true
        observing = enabled
        mutableState.value = state.value.copy(observer = enabled, localHold = false)
        driftCorrector.reset()
        lastStatus = -10_000
        lastBroadcast = -10_000
        return true
    }

    var readingMode: Boolean = false
        private set
    var onReadingMessage: ((String, ReadingEnvelope, Boolean) -> Unit)? = null
    var onReadingSessionMessage: ((String, eu.kanade.tachiyomi.data.reading.ReadingSessionEnvelope) -> Unit)? = null
    var onInteractionMessage: ((String, WatchInteractionSealed) -> Unit)? = null

    fun interactionContext(): WatchInteractionContext {
        val own = transport?.publicKey.orEmpty()
        val members = if (state.value.host) {
            peers.filterValues { it.first.interactionVersion == 1 }.mapValues { it.value.first.name } + (own to name)
        } else {
            state.value.members.filter { it.interactionVersion == 1 }.associate { it.id to it.name }
        }
        return WatchInteractionContext(
            active,
            state.value.invite,
            own,
            if (state.value.host) own else ownerKey,
            members,
            state.value.relayCount > 0,
            if (state.value.host) interactionEpoch else ownerInteractionEpoch,
        )
    }

    fun sendInteraction(target: String, value: WatchInteractionSealed) {
        if (active && value.valid() && target in interactionContext().members) {
            send(message(WatchMessageType.Status).copy(target = target, interaction = value))
        }
    }

    fun removeMember(id: String) {
        if (!active || !state.value.host || id !in peers) return
        removedMembers.add(id)
        while (removedMembers.size > 32) removedMembers.remove(removedMembers.first())
        peers.remove(id)
        acknowledgements.remove(id)
        memberRequests.remove(id)
        publishMembership()
        lastBroadcast = -10_000
    }

    fun approveMember(id: String) {
        if (!active || !state.value.host || peers.size >= 7) return
        val pending = memberRequests.remove(id) ?: return
        peers[id] = pending to now()
        publishMembership()
        lastBroadcast = -10_000
    }

    fun rejectMember(id: String) {
        if (!active || !state.value.host) return
        if (memberRequests.remove(id) == null) return
        removedMembers.add(id)
        while (removedMembers.size > 32) removedMembers.remove(removedMembers.first())
        lastBroadcast = -10_000
        publishMembership()
    }

    private fun publishMembership() {
        val own = transport?.publicKey
        mutableState.value = state.value.copy(
            members = state.value.members.filter { it.id == own } +
                peers.map { (id, entry) ->
                    val peer = entry.first
                    WatchMember(
                        id,
                        peer.name,
                        peer.ready,
                        peer.buffering,
                        reading = peer.reading,
                        observer = peer.observer,
                        interactionVersion = peer.interactionVersion,
                    )
                },
            memberRequests = memberRequests.map { (id, peer) -> WatchMember(id, peer.name, false, false) },
        )
    }

    fun readingSessionContext(): eu.kanade.tachiyomi.data.reading.ReadingSessionContext {
        val own = transport?.publicKey.orEmpty()
        val members = if (state.value.host) {
            peers.mapValues { it.value.first.readingSessionVersion }
        } else {
            timeline?.peers?.mapValues { it.value.readingSessionVersion }.orEmpty()
        }
        return eu.kanade.tachiyomi.data.reading.ReadingSessionContext(
            active,
            state.value.invite,
            own,
            if (state.value.host) own else ownerKey,
            state.value.host,
            members + (own to 1),
            state.value.relayCount > 0,
            requestFloor = sessionSequenceFloor,
        )
    }

    fun readingHostClock(): Long? = if (state.value.host) {
        now()
    } else if (clock.ready) {
        now() + clock.offset
    } else {
        null
    }

    fun sendReadingSession(value: eu.kanade.tachiyomi.data.reading.ReadingSessionEnvelope, target: String) {
        if (active && value.valid() && target.isNotEmpty()) {
            send(message(WatchMessageType.Status).copy(readingSession = value, target = target))
        }
    }

    var liveMode: Boolean = false
        private set
    var onTvMessage: ((String, eu.kanade.tachiyomi.data.tv.TvRoomEnvelope) -> Unit)? = null

    fun tvContext(): eu.kanade.tachiyomi.data.tv.TvRoomContext {
        val own = transport?.publicKey.orEmpty()
        val members = if (state.value.host) {
            peers.mapValues { it.value.first.tvVersion }
        } else {
            timeline?.peers?.mapValues { it.value.tvVersion }.orEmpty()
        }
        return eu.kanade.tachiyomi.data.tv.TvRoomContext(
            active,
            state.value.host,
            own,
            if (state.value.host) own else ownerKey,
            members + (own to 1),
            state.value.relayCount > 0,
            state.value.invite,
        )
    }

    fun setLiveMode(enabled: Boolean) {
        if (liveMode == enabled) return
        if (enabled && !readingMode && active) {
            player.pause(true)
            player.speed(originalSpeed)
        }
        liveMode = enabled
        if (enabled) readingMode = false
        pendingCommand = null
        pendingSeek = null
        skipCue = null
        nextCue = null
        startGate.reset()
        prebufferGate.reset()
        driftCorrector.reset()
        mutableState.value = state.value.copy(
            liveMode = enabled, media = null, upcoming = null,
            sharedReadingMode = if (enabled) false else state.value.sharedReadingMode,
            skip = null, next = null, resumeSeconds = null, skipSeconds = null, nextSeconds = null,
            playRequested = false, pendingPlaybackPaused = null, prebuffering = false, localHold = false,
        )
        lastStatus = -10_000
        lastBroadcast = -10_000
    }

    fun sendTv(value: eu.kanade.tachiyomi.data.tv.TvRoomEnvelope, target: String) {
        if (active && value.valid() && target.isNotEmpty()) {
            send(message(WatchMessageType.Status).copy(tv = value, target = target))
        }
    }

    fun setReadingMode(enabled: Boolean) {
        if (enabled && liveMode) setLiveMode(false)
        if (readingMode == enabled) return
        if (enabled && active) {
            if (state.value.host) {
                hold()
            } else {
                player.pause(true)
                pendingCommand = null
                mutableState.value = state.value.copy(localHold = true, pendingPlaybackPaused = null)
            }
        }
        readingMode = enabled
        prebufferGate.reset()
        mutableState.value = state.value.copy(
            prebuffering = false,
            sharedReadingMode = if (state.value.host) enabled else state.value.sharedReadingMode,
        )
        lastStatus = -10_000
        lastBroadcast = -10_000
    }

    fun sharedReadingSelected() {
        // Pausing video can synchronously notify the mode coordinator. Mark the chosen
        // medium first, so that intermediate playback state cannot discard this selection.
        mutableState.value = state.value.copy(sharedReadingMode = true)
        setReadingMode(true)
        mutableState.value = state.value.copy(sharedReadingMode = true, media = null, upcoming = null)
    }

    fun sendReading(value: ReadingEnvelope, target: String = "") {
        if (active && (state.value.host || state.value.readingSupported) && value.valid()) {
            send(message(WatchMessageType.Status).copy(reading = value, target = target))
        }
    }
    var expectsPaused: Boolean = true
        private set

    fun create(displayName: String, relays: List<String> = WatchInvite.defaultRelays) = start(displayName) { identity ->
        WatchInvite.create(identity.publicKey, wallMillis(), relays)
    }

    fun join(code: String, displayName: String) = start(displayName) { WatchInvite.parse(code, wallMillis()) }

    fun resume(room: WatchInvite, identitySecret: ByteArray, displayName: String, sequenceFloor: Long = 0) =
        start(displayName, WatchIdentity(identitySecret), sequenceFloor) { room.validate(wallMillis()) }

    private fun start(
        displayName: String,
        restoredIdentity: WatchIdentity? = null,
        sequenceFloor: Long = 0,
        makeInvite: (WatchIdentity) -> WatchInvite,
    ) {
        if (active) {
            restoredIdentity?.clear()
            return
        }
        var identity: WatchIdentity? = restoredIdentity
        try {
            val sample = if (readingMode ||
                liveMode
            ) {
                WatchPlayback(null, 0.0, true, false, false, 1.0)
            } else {
                player.sample()
            }
            identity = identity ?: WatchIdentity()
            val room = makeInvite(identity)
            val network = transportFactory(room, identity)
            reset()
            require(sequenceFloor in 0..Long.MAX_VALUE - 1_000_000)
            sequence = sequenceFloor
            sessionSequenceFloor = sequenceFloor
            name = displayName.trim().take(32).ifBlank { text(R.string.room_viewer) }
            invite = room
            transport = network
            originalSpeed = sample.speed.takeIf { it.isFinite() }?.coerceIn(0.25, 3.0) ?: 1.0
            baseSpeed = originalSpeed
            started = now()
            lastHostMessage = started
            val host = room.owns(network.publicKey)
            mutableState.value = WatchRoomState(
                phase = WatchPhase.Connecting,
                active = true,
                host = host,
                invite = room.encode(),
                media = if (host) sample.media else null,
                message = text(R.string.room_connection_start),
                localMemberId = network.publicKey,
                readingSupported = host,
                readingVersion = if (host) 2 else 0,
            )
            onSessionStarted(room, identity, name)
            if (!readingMode && !liveMode) player.pause(true)
            val token = ++generation
            network.start(
                onMessage = { sender, message -> scope.launch { if (token == generation) receive(sender, message) } },
                onConnection = { count ->
                    scope.launch {
                        if (token == generation && active) {
                            mutableState.value = state.value.copy(relayCount = count)
                            if (count > 0) {
                                lastStatus = -10_000
                                lastBroadcast = -10_000
                                lastPing = -10_000
                            }
                        }
                    }
                },
            )
            ticker = scope.launch {
                while (active && token == generation) {
                    tick()
                    delay(250)
                }
            }
        } catch (e: Exception) {
            identity?.clear()
            transport?.close()
            transport = null
            mutableState.value = WatchRoomState(
                phase = WatchPhase.Failed,
                message = (e as? IllegalArgumentException)?.roomMessage(text) ?: text(R.string.room_open_failed),
            )
        }
    }

    private fun reset() {
        observing = false
        sequence = 0
        sessionSequenceFloor = 0
        lastBroadcast = -10_000
        lastStatus = -10_000
        lastPing = -10_000
        lastSeek = -10_000
        clock = WatchClock()
        startGate.reset()
        prebufferGate.reset()
        driftCorrector.reset()
        pausedBy = ""
        hostTimeline.reset()
        durationVerifier.reset()
        cueSequence = 0L
        skipKey = null
        skipCue = null
        skipWait = null
        suppressedSkips.clear()
        nextCue = null
        suppressedNext = null
        advancedFrom = null
        timeline = null
        ownerKey = ""
        ownerModeSequence = 0L
        pings.clear()
        peers.clear()
        peerLoadingSince.clear()
        sequences.clear()
        acknowledgements.clear()
        departed.clear()
        removedMembers.clear()
        interactionEpoch = watchRandom(16).watchHex()
        ownerInteractionEpoch = ""
        memberRequests.clear()
        lastStatusValue = null
        pendingCommand = null
        pendingSeek = null
        acceptedMedia = null
        desiredPaused = true
        hasPlayedCurrentMedia = false
        expectsPaused = true
        appliedSpeed = null
        managedSpeeds.clear()
        seekRevision = 0
        appliedSeekRevision = -1
        closedMessage = ""
        failedCommand = null
        waitingSince = null
        feedbackMediaKey = null
        lastManualRetry = -10_000
        activitySequence = 0
        lastSeenActivity = 0
        activityUntil = 0
        publishedActivity = null
    }

    fun leave() {
        if (!active) {
            mutableState.value = WatchRoomState()
            return
        }
        send(message(if (state.value.host) WatchMessageType.Closed else WatchMessageType.Leave))
        onSessionEnded()
        stop(WatchPhase.Idle, "")
    }

    private fun stop(phase: WatchPhase, reason: String) {
        if (phase == WatchPhase.Closed) onSessionEnded()
        generation++
        ticker?.cancel()
        ticker = null
        transport?.close()
        transport = null
        invite = null
        pendingCommand = null
        if (!readingMode && !liveMode) {
            player.pause(true)
            player.speed(originalSpeed)
        }
        appliedSpeed = null
        liveMode = false
        mutableState.value = WatchRoomState(phase = phase, message = reason)
    }

    /** Timers, audio-focus loss and backgrounding can only be cleared by an explicit local Play. */
    fun hold() {
        if (!active || readingMode || liveMode || observing) return
        expectsPaused = true
        cancelNext()
        if (state.value.waitForEveryone) startGate.reset()
        mutableState.value = state.value.copy(localHold = true)
        player.pause(true)
        if (state.value.waitForEveryone) requestPause(true)
        lastStatus = -10_000
    }

    fun playerAttached() {
        // A newly loaded native player must not start ahead of the room readiness barrier.
        expectsPaused = true
        appliedSpeed = null
        driftCorrector.reset()
        if (!observing && (!hasPlayedCurrentMedia || state.value.waitForEveryone)) {
            startGate.reset()
            prebufferGate.reset()
        }
        appliedSeekRevision = -1
        lastStatus = -10_000
        lastBroadcast = -10_000
    }

    fun resumeByUser(): Boolean {
        if (!active || readingMode || liveMode) return false
        player.userResumed()
        mutableState.value = state.value.copy(localHold = false)
        lastStatus = -10_000
        return requestPause(false)
    }

    fun requestPause(paused: Boolean): Boolean = command(if (paused) "pause" else "play")
    fun requestSeek(seconds: Double): Boolean = command("seek", seconds)
    fun requestSpeed(speed: Double): Boolean = command("speed", speed)

    fun offerSkip(key: String?, label: String = "", target: Double = 0.0, autoSeconds: Int? = null) {
        if (!active || !state.value.host || readingMode || liveMode) return
        if (player.sample().ended) {
            dismissSkip()
            return
        }
        if (key == null) {
            if (skipCue != null) lastBroadcast = -10_000
            skipCue = null
            skipKey = null
            return
        }
        if (key == skipKey || key in suppressedSkips || !target.isFinite() || target <= 0) return
        skipKey = key
        skipWait = autoSeconds?.coerceIn(3, 30)?.times(1000L)
        skipCue = WatchSkip(++cueSequence, label.take(100).ifBlank { "Salta sigla" }, target)
        lastBroadcast = -10_000
    }

    fun requestSkip() = command("skip", cue = state.value.skip?.id ?: 0)
    fun cancelSkip() = command("cancel_skip", cue = state.value.skip?.id ?: 0)
    fun playNextNow() = command("next", cue = state.value.next?.id ?: 0)
    fun cancelNext() = command("cancel_next", cue = state.value.next?.id ?: 0)

    private fun dismissSkip() {
        skipKey?.let {
            suppressedSkips.add(it)
            if (suppressedSkips.size > 16) suppressedSkips.remove(suppressedSkips.first())
        }
        skipCue = null
    }

    private fun command(action: String, value: Double = 0.0, cue: Long = 0): Boolean {
        if (!active || readingMode || liveMode) return false
        if (!value.isFinite()) return true
        if (!state.value.host && !state.value.sharedControls) {
            mutableState.value = state.value.copy(message = text(R.string.room_host_controls))
            return true
        }
        if (action == "play" && state.value.localHold) return true
        if (action !in listOf("pause", "cancel_skip", "cancel_next") &&
            state.value.phase == WatchPhase.DifferentVideo
        ) {
            return true
        }
        val sample = player.sample()
        failedCommand = null
        closedMessage = ""
        mutableState.value = state.value.copy(commandFailed = false)
        val request = message(WatchMessageType.Command).copy(
            command = action,
            cueId = cue,
            media = sample.media,
            position = if (action ==
                "seek"
            ) {
                value.coerceIn(0.0, sample.media?.duration?.takeIf { it > 0 } ?: 86_400.0)
            } else {
                sample.position.coerceAtLeast(0.0)
            },
            speed = if (action == "speed") value.coerceIn(0.25, 3.0) else baseSpeed,
        )
        if (state.value.host) {
            applyCommand(request, name)
        } else {
            pendingCommand = request
            pendingSince = now()
            lastRetry = now()
            mutableState.value = state.value.copy(
                pendingPlaybackPaused = when (action) {
                    "play" -> false
                    "pause" -> true
                    else -> null
                },
                resumeSeconds = if (action == "pause") null else state.value.resumeSeconds,
            )
            send(request)
            // Pausing is safe immediately. Play/seek wait for the creator's authoritative state.
            if (action == "pause") player.pause(true)
        }
        return true
    }

    private fun applyCommand(
        request: WatchMessage,
        actor: String,
        actorId: String = transport?.publicKey.orEmpty(),
        announce: Boolean = true,
    ) {
        when (request.command) {
            "pause" -> {
                desiredPaused = true
                hostTimeline.update(false, baseSpeed, now())
                mutableState.value = state.value.copy(playRequested = false, prebuffering = false, resumeSeconds = null)
                pausedBy = actor
                startGate.reset()
                prebufferGate.reset()
                suppressedNext = player.sample().media?.key
                nextCue = null
                skipCue = skipCue?.copy(deadline = null)
            }
            "play" -> {
                desiredPaused = false
                mutableState.value =
                    state.value.copy(playRequested = true, message = text(R.string.room_prepare_playback))
                pausedBy = ""
            }
            "skip" -> {
                val cue = skipCue?.takeIf { it.id == request.cueId } ?: return
                dismissSkip()
                applyCommand(request.copy(command = "seek", position = cue.target), actor, actorId, announce)
            }
            "cancel_skip" -> {
                if (skipCue?.id != request.cueId) return
                dismissSkip()
            }
            "next" -> {
                val cue = nextCue?.takeIf { it.id == request.cueId } ?: return
                // Preparation and safety checks still gate the actual episode change.
                nextCue = cue.copy(deadline = now())
            }
            "cancel_next" -> {
                if (nextCue?.id != request.cueId) return
                suppressedNext = player.sample().media?.key
                nextCue = null
            }
            "seek" -> {
                startGate.reset()
                prebufferGate.reset()
                driftCorrector.reset()
                nextCue = null
                suppressedNext = null
                advancedFrom = null
                val duration = player.sample().media?.duration ?: return
                pendingSeek = request.position.coerceIn(0.0, duration.takeIf { it > 0 } ?: 86_400.0)
                pendingSeekSince = now()
                seekRevision++
                hostTimeline.seek(pendingSeek!!, now())
                hostTimeline.update(false, baseSpeed, now())
                player.seek(pendingSeek!!)
            }
            "speed" -> {
                hostTimeline.speed(request.speed, now())
                baseSpeed = request.speed
            }
            else -> return
        }
        if (announce && request.command in listOf("pause", "play", "seek", "speed")) {
            player.sample().media?.let { media ->
                val activity = WatchActivity(
                    ++activitySequence,
                    now(),
                    actorId,
                    actor.ifBlank { text(R.string.room_viewer) }.take(32),
                    request.command,
                    media.key,
                    if (request.command == "speed") request.speed else request.position,
                )
                publishedActivity = activity
                activityUntil = now() + 3500
                mutableState.value = state.value.copy(activity = activity)
            }
        }
        lastBroadcast = -10_000
    }

    fun setSharedControls(value: Boolean) {
        if (active && state.value.host) {
            mutableState.value = state.value.copy(sharedControls = value)
            lastBroadcast = -10_000
        }
    }

    fun setWaitForEveryone(value: Boolean) {
        if (active && state.value.host) {
            mutableState.value = state.value.copy(waitForEveryone = value)
            lastBroadcast = -10_000
        }
    }

    fun setPrebufferOnStart(value: Boolean) {
        if (active && state.value.host) {
            mutableState.value =
                state.value.copy(prebufferOnStart = value, prebuffering = value && state.value.prebuffering)
            prebufferGate.reset()
            lastBroadcast = -10_000
        }
    }

    fun confirmSameVideo() {
        if (!active || readingMode || liveMode) return
        val local = player.sample().media ?: return
        val remote = state.value.media ?: return
        if (local.compatibleDuration(remote)) {
            acceptedMedia = local.key + "|" + remote.key
            lastStatus = -10_000
            appliedSeekRevision = -1
        }
    }

    fun resync() {
        if (!active) return
        lastSeek = -10_000
        driftCorrector.reset()
        appliedSeekRevision = -1
        lastPing = -10_000
        lastStatus = -10_000
        lastBroadcast = -10_000
    }

    /** Explicit retry keeps episode identity, command ordering and the existing local safety hold. */
    fun retryFailedCommand() {
        if (!active || readingMode || liveMode) return
        val failed = failedCommand ?: return
        if (failed.media?.key != state.value.media?.key || failed.media?.key != player.sample().media?.key) {
            failedCommand = null
            mutableState.value = state.value.copy(commandFailed = false)
            return
        }
        if (now() - lastManualRetry < 2000) return
        lastManualRetry = now()
        transport?.retryUnavailable()
        resync()
        if (failed.command == "play" && state.value.localHold) return
        command(failed.command, if (failed.command == "speed") failed.speed else failed.position, failed.cueId)
    }

    fun retryConnection() {
        if (!active || now() - lastManualRetry < 2000) return
        lastManualRetry = now()
        waitingSince = now()
        mutableState.value = state.value.copy(waitingSeconds = 0)
        transport?.retryUnavailable()
        resync()
    }

    fun isManagedSpeed(value: Double): Boolean = active && managedSpeeds.any { abs(value - it) < 0.0001 }
    fun preferredSpeed(): Double = if (active) baseSpeed else originalSpeed

    private fun receive(sender: String, incoming: WatchMessage) {
        if (!active || sender == transport?.publicKey || !incoming.valid()) return
        if (sender in removedMembers) return
        val isOwner = invite?.owns(sender) == true
        if (isOwner) ownerKey = sender
        incoming.interaction?.let {
            if (incoming.target == transport?.publicKey && sender in interactionContext().members) {
                onInteractionMessage?.invoke(sender, it)
            }
            return
        }
        if (!state.value.host && !isOwner) return
        incoming.readingSession?.let { session ->
            if (incoming.target == transport?.publicKey && (isOwner || sender in peers)) {
                if (isOwner && session.kind == eu.kanade.tachiyomi.data.reading.ReadingSessionKind.Snapshot) {
                    if (incoming.sequence < ownerModeSequence) return
                    ownerModeSequence = incoming.sequence
                    lastHostMessage = now()
                }
                onReadingSessionMessage?.invoke(sender, session)
            }
            return
        }
        incoming.reading?.let {
            if ((incoming.target.isEmpty() || incoming.target == transport?.publicKey) &&
                (isOwner || sender in peers)
            ) {
                onReadingMessage?.invoke(sender, it, isOwner)
            }
            return
        }
        if (incoming.coordinationVersion != 2) {
            stop(WatchPhase.Failed, text(R.string.room_update_required))
            return
        }
        if (incoming.sequence <= (departed[sender] ?: 0L)) return
        if (state.value.host && sender !in peers && peers.size >= 7) return
        val lane = sender + "|" + incoming.type.name
        if (incoming.sequence <= (sequences[lane] ?: 0L)) {
            if (state.value.host && incoming.type == WatchMessageType.Command) lastBroadcast = -10_000
            return
        }
        sequences[lane] = incoming.sequence
        if (sequences.size >
            128
        ) {
            sequences.remove(sequences.keys.first { it != lane && it.substringBefore('|') !in peers })
        }
        if (incoming.tv != null) {
            if (incoming.target == transport?.publicKey && (isOwner || sender in peers)) {
                if (isOwner) {
                    lastHostMessage = now()
                    if (incoming.tv.kind == eu.kanade.tachiyomi.data.tv.TvRoomKind.Selection ||
                        incoming.tv.kind == eu.kanade.tachiyomi.data.tv.TvRoomKind.Exit
                    ) {
                        if (incoming.sequence < ownerModeSequence) return
                        ownerModeSequence = incoming.sequence
                    }
                }
                onTvMessage?.invoke(sender, incoming.tv)
            }
            return
        }
        if (isOwner && incoming.type == WatchMessageType.Timeline) {
            if (incoming.sequence < ownerModeSequence) return
            ownerModeSequence = incoming.sequence
            lastHostMessage = now()
            setLiveMode(incoming.liveMode && incoming.tvVersion == 1)
        }
        when (incoming.type) {
            WatchMessageType.Hello, WatchMessageType.Status -> if (state.value.host) {
                val same = !readingMode && !liveMode && incoming.media?.key == player.sample().media?.key
                val status = WatchPeerStatus(
                    incoming.name,
                    incoming.ready && same,
                    incoming.buffering,
                    incoming.media,
                    incoming.problem,
                    incoming.canAdvance,
                    incoming.preparedNextKey,
                    incoming.nextProblem,
                    incoming.readingMode,
                    incoming.position.takeIf { incoming.media != null && !incoming.readingMode },
                    incoming.bufferedAheadSeconds,
                    incoming.tvVersion,
                    incoming.observer,
                    incoming.observerVersion,
                    incoming.readingSessionVersion,
                    incoming.interactionVersion,
                )
                if (removedMembers.isNotEmpty() && sender !in peers) {
                    if (memberRequests.size < 8 || sender in memberRequests) memberRequests[sender] = status
                } else {
                    peers[sender] = status to now()
                }
                lastBroadcast = -10_000
            }
            WatchMessageType.Ping -> if (state.value.host) {
                send(message(WatchMessageType.Pong).copy(target = sender, ping = incoming.ping))
            }
            WatchMessageType.Pong -> if (isOwner &&
                incoming.target == transport?.publicKey &&
                pings.remove(incoming.ping)
            ) {
                clock.accept(incoming.ping, now(), incoming.at)
            }
            WatchMessageType.Timeline -> if (isOwner) {
                if (transport?.publicKey in incoming.removedMembers) {
                    stop(WatchPhase.Closed, text(R.string.room_removed))
                    return
                }
                if (incoming.peers.size >= 8 && transport?.publicKey !in incoming.peers) {
                    stop(WatchPhase.Failed, text(R.string.room_room_full))
                    return
                }
                val alreadyConnected = timeline != null
                timeline = incoming
                ownerInteractionEpoch = incoming.interactionSession
                mutableState.value = state.value.copy(
                    readingSupported = incoming.readingVersion in 1..2,
                    readingVersion = incoming.readingVersion,
                    sharedReadingMode = incoming.readingMode,
                )
                baseSpeed = incoming.speed
                val own = transport?.publicKey
                pendingCommand?.let { pending ->
                    if ((incoming.acknowledgements[own] ?: 0) >= pending.sequence) {
                        pendingCommand = null
                        failedCommand = null
                        closedMessage = ""
                        mutableState.value = state.value.copy(commandFailed = false)
                    }
                }
                failedCommand?.let { failed ->
                    if ((incoming.acknowledgements[own] ?: 0) >= failed.sequence) {
                        failedCommand = null
                        closedMessage = ""
                        mutableState.value = state.value.copy(commandFailed = false)
                    }
                }
                incoming.activity?.takeIf { it.id > lastSeenActivity }?.let { activity ->
                    lastSeenActivity = activity.id
                    val age = now() + clock.offset - activity.at
                    if (alreadyConnected &&
                        clock.ready &&
                        age in -500..3500 &&
                        activity.mediaKey == incoming.media?.key
                    ) {
                        activityUntil = now() + (3500 - age.coerceAtLeast(0))
                        mutableState.value = state.value.copy(activity = activity)
                    }
                }
                mutableState.value = state.value.copy(
                    media = incoming.media,
                    sharedControls = incoming.sharedControls,
                    waitForEveryone = incoming.waitForEveryone,
                    prebufferOnStart = incoming.prebufferOnStart,
                    prebuffering = incoming.prebuffering,
                    playRequested = incoming.playRequested,
                    pendingPlaybackPaused = pendingCommand?.command?.let {
                        when (it) {
                            "play" -> false
                            "pause" -> true
                            else -> null
                        }
                    },
                    skip = incoming.skip,
                    upcoming = incoming.upcoming,
                    next = incoming.next,
                    members = incoming.peers.map { (id, peer) ->
                        WatchMember(
                            id,
                            peer.name,
                            peer.ready,
                            peer.buffering,
                            peer.problem,
                            peer.nextProblem,
                            peer.positionSeconds,
                            peer.reading,
                            peer.bufferedAheadSeconds,
                            peer.observer,
                            peer.interactionVersion,
                        )
                    },
                )
            }
            WatchMessageType.Command -> if (state.value.host && sender in peers) {
                val allowed = !readingMode &&
                    !liveMode &&
                    state.value.sharedControls &&
                    (
                        incoming.command == "pause" ||
                            (incoming.command == "cancel_skip" && incoming.cueId == skipCue?.id) ||
                            (incoming.command == "cancel_next" && incoming.cueId == nextCue?.id) ||
                            incoming.media?.key == player.sample().media?.key
                        )
                if (allowed &&
                    !readingMode &&
                    !liveMode
                ) {
                    applyCommand(incoming, peers.getValue(sender).first.name, sender)
                }
                acknowledgements[sender] = incoming.sequence
                lastBroadcast = -10_000
            }
            WatchMessageType.Leave -> if (state.value.host) {
                departed[sender] = incoming.sequence
                if (departed.size > 16) departed.remove(departed.keys.first())
                peers.remove(sender)
                peerLoadingSince.remove(sender)
                acknowledgements.remove(sender)
                lastBroadcast = -10_000
            }
            WatchMessageType.Closed -> if (isOwner) {
                stop(
                    WatchPhase.Closed,
                    if (readingMode) {
                        text(R.string.room_host_closed_reading)
                    } else {
                        text(R.string.room_host_closed_video)
                    },
                )
            }
        }
    }

    private fun tick() {
        if (wallMillis() >= (invite?.expires ?: 0)) {
            send(message(if (state.value.host) WatchMessageType.Closed else WatchMessageType.Leave))
            stop(WatchPhase.Closed, text(R.string.room_expired_room))
            return
        }
        val time = now()
        mutableState.value = state.value.copy(observerSupported = supportsObserver)
        if (observing && player.sample().ready) setObserver(false)
        if (liveMode) {
            tickLive(time)
            return
        }
        if (readingMode) {
            if (state.value.relayCount == 0) {
                mutableState.value = state.value.copy(
                    phase = if (time - started < 12_000) WatchPhase.Connecting else WatchPhase.Reconnecting,
                    message = connectionMessage(time),
                )
                updateFeedback(time)
                return
            }
            tickReading(time)
            return
        }
        val sample = player.sample()
        if (sample.media?.valid() == false) {
            stop(WatchPhase.Failed, text(R.string.room_content_unshareable))
            return
        }
        // Expiry also runs when no relay is connected; a spinner must not conceal a lost command.
        pendingCommand?.takeIf { time - pendingSince > 8000 }?.let {
            failedCommand = it
            pendingCommand = null
            closedMessage = text(R.string.room_command_unconfirmed)
            mutableState.value = state.value.copy(pendingPlaybackPaused = null, commandFailed = true)
        }
        if (state.value.relayCount == 0) {
            hostTimeline.update(false, baseSpeed, time)
            startGate.reset()
            prebufferGate.reset()
            driftCorrector.reset()
            // Reconnection must receive a new host snapshot before reusing its playback intent.
            if (!state.value.host) timeline = null
            skipCue = skipCue?.copy(deadline = null)
            nextCue = nextCue?.copy(deadline = null)
            expectsPaused = true
            player.pause(true)
            setSpeed(baseSpeed)
            mutableState.value = state.value.copy(
                phase = if (time - started < 12_000) WatchPhase.Connecting else WatchPhase.Reconnecting,
                resumeSeconds = null,
                skipSeconds = null,
                nextSeconds = null,
                skip = state.value.skip?.copy(deadline = null),
                next = state.value.next?.copy(deadline = null),
                message = connectionMessage(time),
            )
            updateFeedback(time)
            return
        }
        if (state.value.host) tickHost(sample, time) else tickGuest(sample, time)
        updateFeedback(time)
    }

    private fun connectionMessage(time: Long): String {
        val connecting = time - started < 12_000
        return when (transport?.relayFailure()) {
            WatchRelayFailure.Rejected -> text(R.string.room_relay_rejected)
            WatchRelayFailure.RateLimited -> text(R.string.room_relay_busy)
            else -> if (connecting) text(R.string.room_connection_start) else text(R.string.room_connection_lost)
        }
    }

    private fun tickLive(time: Long) {
        expectsPaused = false
        peers.filterValues { time - it.second > 30_000 }.keys.toList().forEach {
            peers.remove(it)
            acknowledgements.remove(it)
            peerLoadingSince.remove(it)
        }
        val connected = state.value.relayCount > 0
        if (!state.value.host && time - lastHostMessage > 45_000 && connected) {
            stop(WatchPhase.Closed, text(R.string.tv_room_owner_unavailable))
            return
        }
        mutableState.value =
            state.value.copy(
                liveMode = true,
                phase = if (connected) WatchPhase.Playing else WatchPhase.Reconnecting,
                message = text(R.string.tv_room_local_controls),
                media = null,
                playRequested = false,
            )
        if (!connected) return
        if (state.value.host) {
            val all = linkedMapOf(
                transport!!.publicKey to
                    WatchPeerStatus(
                        name,
                        !observing,
                        false,
                        tvVersion = 1,
                        observer = observing,
                        observerVersion = 1,
                        readingSessionVersion = 1,
                        interactionVersion = 1,
                    ),
            )
            peers.forEach { (id, peer) -> all[id] = peer.first }
            mutableState.value =
                state.value.copy(
                    members = all.map { (id, peer) ->
                        WatchMember(
                            id,
                            peer.name,
                            peer.ready,
                            false,
                            observer = peer.observer,
                            interactionVersion = peer.interactionVersion,
                        )
                    },
                )
            if (time - lastBroadcast >= 4000) {
                send(message(WatchMessageType.Timeline).copy(peers = all))
                lastBroadcast = time
            }
        } else if (time - lastStatus >= 4000) {
            send(message(WatchMessageType.Status).copy(name = name, ready = !observing))
            lastStatus = time
        }
    }

    private fun tickReading(time: Long) {
        expectsPaused = true
        if (!state.value.host) probeClock(time)
        peers.filterValues { time - it.second > 30_000 }.keys.toList().forEach {
            peers.remove(it)
            peerLoadingSince.remove(it)
            acknowledgements.remove(it)
        }
        if (state.value.host) {
            val all =
                linkedMapOf(
                    transport!!.publicKey to
                        WatchPeerStatus(
                            name,
                            false,
                            false,
                            reading = true,
                            tvVersion = 1,
                            observer = observing,
                            observerVersion = 1,
                            readingSessionVersion = 1,
                            interactionVersion = 1,
                        ),
                )
            peers.forEach { (id, peer) -> all[id] = peer.first }
            mutableState.value = state.value.copy(
                media = null,
                playRequested = false,
                phase = WatchPhase.Waiting,
                members = all.map { (id, peer) ->
                    WatchMember(
                        id,
                        peer.name,
                        peer.ready,
                        peer.buffering,
                        reading = peer.reading,
                        observer = peer.observer,
                        interactionVersion = peer.interactionVersion,
                    )
                },
                message = text(R.string.room_shared_reading),
                upcoming = null,
                skipSeconds = null,
                nextSeconds = null,
                resumeSeconds = null,
                skip = null,
                next = null,
            )
            if (peers.isNotEmpty() && time - lastBroadcast >= 4000) {
                send(
                    message(WatchMessageType.Timeline).copy(
                        peers = all,
                        readingMode = true,
                        sharedControls = state.value.sharedControls,
                        waitForEveryone = state.value.waitForEveryone,
                        prebufferOnStart = state.value.prebufferOnStart,
                    ),
                )
                lastBroadcast = time
            }
        } else if (time - lastStatus >= 4000) {
            send(message(WatchMessageType.Status).copy(name = name, readingMode = true))
            lastStatus = time
        }
    }

    private fun updateFeedback(time: Long) {
        val room = state.value
        if (room.media?.key != feedbackMediaKey) {
            feedbackMediaKey = room.media?.key
            waitingSince = null
            failedCommand = null
            closedMessage = ""
        }
        val waiting = room.preparingPlayback ||
            room.phase in listOf(WatchPhase.Connecting, WatchPhase.Reconnecting, WatchPhase.DifferentVideo)
        if (!waiting) {
            waitingSince = null
        } else if (waitingSince == null) {
            waitingSince = time
        }
        mutableState.value = room.copy(
            waitingSeconds = waitingSince?.let { ((time - it) / 1000).toInt().coerceAtLeast(0) } ?: 0,
            commandFailed = failedCommand != null,
            memberRequests = memberRequests.map { (id, peer) -> WatchMember(id, peer.name, false, false) },
            activity = room.activity?.takeIf { time < activityUntil && it.mediaKey == room.media?.key },
        )
    }

    private fun tickHost(sample: WatchPlayback, time: Long) {
        peers.filterValues { time - it.second > 30_000 }.keys.toList().forEach {
            peers.remove(it)
            peerLoadingSince.remove(it)
            acknowledgements.remove(it)
        }
        val media = sample.media
        hostTimeline.bind(media?.key, sample.position, time)
        if (sample.ended) dismissSkip()
        if (state.value.media?.key != media?.key) {
            seekRevision++
            pendingSeek = null
            startGate.reset()
            prebufferGate.reset()
            skipCue = null
            skipKey = null
            suppressedSkips.clear()
            nextCue = null
            suppressedNext = null
            advancedFrom = null
            peers.replaceAll { _, entry -> entry.first.copy(ready = false) to entry.second }
            peerLoadingSince.clear()
            hasPlayedCurrentMedia = false
            lastBroadcast = -10_000
        }
        pendingSeek?.let {
            if ((sample.ready && !sample.buffering && abs(sample.position - it) < 1.5) ||
                time - pendingSeekSince > 10_000
            ) {
                pendingSeek = null
            }
        }
        val waiting = state.value.waitForEveryone &&
            peers.map { (id, entry) ->
                val (peer, lastSeen) = entry
                if (peer.reading || peer.observer) {
                    peerLoadingSince.remove(id)
                    false
                } else {
                    val stale = time - lastSeen > 12_000
                    val loading = !peer.ready || peer.buffering
                    if (!loading) peerLoadingSince.remove(id)
                    stale || loading
                }
            }.any { it }
        val localBuffering = !observing && (!sample.ready || sample.buffering || pendingSeek != null)
        val buffering = media == null ||
            waiting ||
            (localBuffering && (state.value.waitForEveryone || !hasPlayedCurrentMedia))
        val prebuffering = prebufferGate.waiting(
            enabled = state.value.prebufferOnStart &&
                peers.values.any { !it.first.reading && !it.first.observer } &&
                (media?.duration ?: 0.0) - sample.position > WatchPrebufferGate.TARGET_SECONDS,
            firstStart = !hasPlayedCurrentMedia,
            ready = !buffering && !desiredPaused && !sample.ended,
            now = time,
            buffers = (if (observing) emptyList() else listOf(sample.bufferedAheadSeconds?.toInt())) +
                peers.values.filter { !it.first.reading && !it.first.observer }.map { it.first.bufferedAheadSeconds },
        )
        val previousDeadline = startGate.deadline
        val reachedEnd = sample.ended ||
            (
                media != null &&
                    media.duration > 0 &&
                    hostTimeline.position(time, media.duration) >= media.duration
                )
        val running = startGate.update(
            !buffering &&
                !prebuffering &&
                !reachedEnd &&
                (!state.value.waitForEveryone || !state.value.localHold),
            !desiredPaused,
            time,
        )
        if (previousDeadline != startGate.deadline) lastBroadcast = -10_000
        val paused = !running
        hostTimeline.update(running, baseSpeed, time)
        val position = hostTimeline.position(time, media?.duration ?: 0.0)
        if (running) hasPlayedCurrentMedia = true
        val localPaused = paused || localBuffering || state.value.localHold
        expectsPaused = localPaused
        if (!localBuffering && !state.value.localHold && !observing) {
            val correction = driftCorrector.correct(
                sample.position,
                position,
                baseSpeed,
                paused,
                time - lastSeek >= 15_000,
                time,
            )
            correction.seek?.let {
                player.seek(it)
                lastSeek = time
            }
            setSpeed(correction.speed)
        } else {
            driftCorrector.reset()
            setSpeed(baseSpeed)
        }
        if (sample.paused != localPaused) player.pause(localPaused)

        skipCue?.let { cue ->
            if (paused) {
                skipCue = cue.copy(deadline = null)
            } else if (cue.deadline == null && skipWait != null) {
                skipCue = cue.copy(deadline = time + skipWait!!)
                lastBroadcast = -10_000
            } else if (cue.deadline != null && time >= cue.deadline) {
                applyCommand(
                    message(WatchMessageType.Command).copy(command = "skip", cueId = cue.id),
                    name,
                    announce = false,
                )
                tickHost(player.sample(), time)
                return
            }
        }

        val upcoming = sample.upcoming
        if (!sample.ended) {
            nextCue = null
            suppressedNext = null
        } else if (upcoming != null && media != null && suppressedNext != media.key && advancedFrom != media.key) {
            if (nextCue?.media?.key != upcoming.key && sample.canAdvance) {
                nextCue = WatchNext(++cueSequence, upcoming)
                lastBroadcast = -10_000
            }
            val everyonePrepared = peers.values.all {
                it.first.reading ||
                    it.first.observer ||
                    (
                        it.first.preparedNextKey == upcoming.key &&
                            it.first.canAdvance &&
                            it.first.nextProblem == WatchProblem.None &&
                            time - it.second <= 6000
                        )
            }
            nextCue?.let { cue ->
                if (!sample.canAdvance || state.value.localHold || (state.value.waitForEveryone && !everyonePrepared)) {
                    if (cue.deadline != null) lastBroadcast = -10_000
                    nextCue = cue.copy(deadline = null)
                } else if (cue.deadline == null) {
                    nextCue = cue.copy(deadline = time + 10_000)
                    lastBroadcast = -10_000
                } else if (time >= cue.deadline) {
                    advancedFrom = media.key
                    nextCue = null
                    desiredPaused = false
                    startGate.reset()
                    player.advance(upcoming)
                }
            }
        } else {
            nextCue = null
        }

        val problem = when {
            observing -> WatchProblem.None
            state.value.localHold -> WatchProblem.LocalPause
            sample.problem != WatchProblem.None -> sample.problem
            sample.buffering -> WatchProblem.Buffering
            !sample.ready -> WatchProblem.Opening
            else -> WatchProblem.None
        }
        val all = linkedMapOf(
            transport!!.publicKey to WatchPeerStatus(
                name,
                sample.ready && !state.value.localHold,
                sample.buffering,
                media,
                problem,
                sample.canAdvance,
                upcoming?.key,
                reading = readingMode,
                positionSeconds = sample.position.takeIf { media != null && !readingMode },
                bufferedAheadSeconds = sample.bufferedAheadSeconds?.toInt(),
                tvVersion = 1,
                observer = observing,
                observerVersion = 1,
                readingSessionVersion = 1,
                interactionVersion = 1,
            ),
        )
        peers.forEach { (id, peer) ->
            all[id] = if (time - peer.second > 12_000) {
                peer.first.copy(ready = false, problem = WatchProblem.Connection, bufferedAheadSeconds = null)
            } else {
                peer.first
            }
        }
        val blocking = all.entries.firstOrNull {
            it.key != transport!!.publicKey &&
                !it.value.reading &&
                !it.value.observer &&
                (!it.value.ready || it.value.buffering)
        }?.value
        val prebufferingMember = all.values.firstOrNull {
            !it.reading && !it.observer && (it.bufferedAheadSeconds ?: 0) < WatchPrebufferGate.TARGET_SECONDS
        }
        val nextBlocking = all.entries.firstOrNull {
            it.key != transport!!.publicKey &&
                !it.value.reading &&
                !it.value.observer &&
                (
                    it.value.nextProblem != WatchProblem.None ||
                        it.value.preparedNextKey != upcoming?.key ||
                        !it.value.canAdvance ||
                        it.value.problem == WatchProblem.Connection
                    )
        }?.value
        val countdown = remainingSeconds(startGate.deadline, time)?.takeIf { it > 0 }
        val phase = when {
            peers.isEmpty() && desiredPaused -> WatchPhase.Waiting
            buffering || localBuffering || prebuffering -> WatchPhase.Buffering
            countdown != null -> WatchPhase.Starting
            paused -> WatchPhase.Paused
            else -> WatchPhase.Playing
        }
        val statusText = when {
            state.value.localHold -> text(R.string.room_local_pause_hint)
            nextCue != null && nextBlocking != null ->
                nextBlocking.name +
                    ": " +
                    if (nextBlocking.problem == WatchProblem.Connection) {
                        nextBlocking.problem.description(text)
                    } else if (nextBlocking.nextProblem != WatchProblem.None) {
                        nextBlocking.nextProblem.description(text)
                    } else if (!nextBlocking.canAdvance) {
                        text(R.string.room_next_not_ready)
                    } else {
                        text(R.string.room_next_preparing)
                    }
            nextCue != null -> text(R.string.room_next_ready)
            sample.ended -> text(R.string.room_episode_ended)
            waiting && blocking != null ->
                blocking.name +
                    ": " +
                    if (blocking.problem !=
                        WatchProblem.None
                    ) {
                        blocking.problem.description(text)
                    } else {
                        text(R.string.room_waiting_short)
                    }
            prebuffering && prebufferingMember != null ->
                text(
                    R.string.room_member_prebuffering,
                    prebufferingMember.name,
                    prebufferingMember.bufferedAheadSeconds?.let { "$it/15 s" } ?: text(R.string.room_preparing_short),
                )
            buffering || localBuffering -> problem.description(text)
            running && abs(position - sample.position) > 3.0 -> text(R.string.room_catching_up)
            countdown != null -> text(R.string.room_resume_seconds, countdown)
            paused && !desiredPaused -> text(R.string.room_checking_ready)
            paused && pausedBy.isNotBlank() -> text(R.string.room_paused_by, pausedBy)
            paused -> text(R.string.room_all_ready)
            else -> text(R.string.room_watching_together)
        }
        mutableState.value = state.value.copy(
            phase = phase, media = media, playRequested = !desiredPaused && !reachedEnd,
            driftMs = ((position - sample.position) * 1000).roundToLong(),
            prebuffering = prebuffering,
            members = all.map { (id, peer) ->
                WatchMember(
                    id,
                    peer.name,
                    peer.ready,
                    peer.buffering,
                    peer.problem,
                    peer.nextProblem,
                    peer.positionSeconds,
                    peer.reading,
                    peer.bufferedAheadSeconds,
                    peer.observer,
                    peer.interactionVersion,
                )
            },
            message = if (peers.isEmpty() && media == null) text(R.string.room_share_code_hint) else statusText,
            resumeSeconds = countdown, skip = skipCue,
            memberRequests = memberRequests.map { (id, peer) -> WatchMember(id, peer.name, false, false) },
            skipSeconds = remainingSeconds(skipCue?.deadline, time),
            upcoming = upcoming, next = nextCue, nextSeconds = remainingSeconds(nextCue?.deadline, time),
        )
        // Deadlines are shared once and counted down locally; repeating snapshots more often
        // wastes relay quota without making a pause, seek or page change arrive faster.
        val interval = if (countdown != null ||
            prebuffering ||
            skipCue?.deadline != null ||
            nextCue != null
        ) {
            2000
        } else {
            4000
        }
        if (peers.isNotEmpty() && time - lastBroadcast >= interval) {
            send(
                message(WatchMessageType.Timeline).copy(
                    media = media, position = position, paused = paused, playRequested = !desiredPaused && !reachedEnd,
                    speed = baseSpeed, seekRevision = seekRevision, resumeAt = startGate.deadline,
                    buffering = buffering, sharedControls = state.value.sharedControls,
                    waitForEveryone = state.value.waitForEveryone,
                    prebufferOnStart = state.value.prebufferOnStart,
                    peers = all.mapValues { it.value.copy(media = null, preparedNextKey = null) },
                    acknowledgements = acknowledgements.toMap(), pausedBy = pausedBy,
                    skip = skipCue, upcoming = upcoming, next = nextCue,
                    prebuffering = prebuffering,
                    activity = publishedActivity?.takeIf { time - it.at <= 3500 && it.mediaKey == media?.key },
                ),
            )
            lastBroadcast = time
        }
    }

    private fun tickGuest(sample: WatchPlayback, time: Long) {
        val remote = timeline
        val localMedia = sample.media
        val same = localMedia != null &&
            remote?.media?.let {
                (localMedia.key == it.key && !durationVerifier.incompatible(localMedia, it, time)) ||
                    (acceptedMedia == localMedia.key + "|" + it.key && localMedia.compatibleDuration(it))
            } == true
        val admitted = remote == null || !remote.admissionLocked || transport?.publicKey in remote.peers
        val localReady = sample.ready && same && !state.value.localHold && admitted
        val problem = when {
            observing -> WatchProblem.None
            state.value.localHold -> WatchProblem.LocalPause
            sample.problem != WatchProblem.None -> sample.problem
            !same &&
                sample.ready &&
                localMedia?.key == remote?.media?.key &&
                (remote?.media?.duration ?: 0.0) > 0.0 -> WatchProblem.DifferentEdition
            !same -> WatchProblem.Opening
            sample.buffering -> WatchProblem.Buffering
            !sample.ready -> WatchProblem.Opening
            else -> WatchProblem.None
        }
        val reportedBuffer = if (remote != null && remote.prebufferOnStart && remote.playRequested && remote.paused) {
            sample.bufferedAheadSeconds?.toInt()?.coerceIn(0, 120)?.let { (it / 3) * 3 }
        } else {
            null
        }
        val status = WatchPeerStatus(
            name,
            localReady,
            sample.buffering,
            localMedia,
            problem,
            sample.canAdvance && !state.value.localHold,
            sample.preparedNextKey,
            sample.nextProblem,
            readingMode,
            sample.position.takeIf { localMedia != null && !readingMode }?.let { (it / 5).toInt() * 5.0 },
            reportedBuffer,
            tvVersion = 1,
            observer = observing,
            observerVersion = 1,
            interactionVersion = 1,
        )
        if (status != lastStatusValue || time - lastStatus >= 4000) {
            send(
                message(if (lastStatusValue == null) WatchMessageType.Hello else WatchMessageType.Status).copy(
                    media = localMedia,
                    ready = localReady,
                    buffering = sample.buffering,
                    name = name,
                    problem = problem,
                    canAdvance = status.canAdvance,
                    preparedNextKey = sample.preparedNextKey,
                    nextProblem = sample.nextProblem,
                    readingMode = readingMode,
                    position = status.positionSeconds ?: 0.0,
                    bufferedAheadSeconds = status.bufferedAheadSeconds,
                ),
            )
            lastStatusValue = status
            lastStatus = time
        }
        if (readingMode) return
        if (!admitted) {
            expectsPaused = true
            player.pause(true)
            mutableState.value =
                state.value.copy(phase = WatchPhase.Waiting, message = text(R.string.room_waiting_approval))
            return
        }
        probeClock(time)
        pendingCommand?.let {
            if (time - lastRetry >= 1500) {
                send(it)
                lastRetry = time
            }
        }
        val hostTime = time + clock.offset
        val stale = time - lastHostMessage > 12_000
        mutableState.value = state.value.copy(
            skipSeconds = remainingSeconds(remote?.skip?.deadline, hostTime).takeUnless { stale || !clock.ready },
            nextSeconds = remainingSeconds(remote?.next?.deadline, hostTime).takeUnless { stale || !clock.ready },
            resumeSeconds = remainingSeconds(remote?.resumeAt, hostTime)?.takeIf {
                it > 0 && !stale && clock.ready && localReady && !sample.buffering && pendingCommand?.command != "pause"
            },
            skip = remote?.skip?.let { if (stale) it.copy(deadline = null) else it },
            next = remote?.next?.let { if (stale) it.copy(deadline = null) else it },
        )
        if (observing) {
            val scheduled = remote?.resumeAt
            val moving = remote != null &&
                !stale &&
                clock.ready &&
                (
                    !remote.paused ||
                        scheduled != null &&
                        hostTime >= scheduled &&
                        remote.playRequested &&
                        !remote.buffering
                    )
            val elapsed = if (remote ==
                null
            ) {
                0.0
            } else {
                ((hostTime - maxOf(remote.at, scheduled ?: remote.at)) / 1000.0).coerceIn(0.0, 12.0)
            }
            val target = ((remote?.position ?: 0.0) + if (moving) elapsed * (remote?.speed ?: 1.0) else 0.0)
                .coerceIn(0.0, remote?.media?.duration?.coerceAtLeast(0.0) ?: 0.0)
            player.seek(target)
            player.pause(!moving)
            setSpeed(baseSpeed)
            expectsPaused = !moving
            mutableState.value = state.value.copy(
                phase = if (stale) {
                    WatchPhase.Reconnecting
                } else if (moving) {
                    WatchPhase.Playing
                } else {
                    WatchPhase.Paused
                },
                latencyMs = clock.latency,
                driftMs = null,
                message = text(R.string.playback_observing),
            )
            return
        }
        if (remote == null || stale || !same || !clock.ready || !localReady) {
            expectsPaused = true
            driftCorrector.reset()
            player.pause(true)
            setSpeed(baseSpeed)
            mutableState.value = state.value.copy(
                phase = when {
                    stale -> WatchPhase.Reconnecting
                    remote == null || !clock.ready -> WatchPhase.Waiting
                    remote.media == null -> WatchPhase.Waiting
                    !same -> WatchPhase.DifferentVideo
                    else -> WatchPhase.Paused
                },
                latencyMs = clock.latency,
                message = when {
                    stale -> text(R.string.room_waiting_host)
                    remote == null -> text(R.string.room_waiting_friend_room)
                    remote.media == null -> text(R.string.room_friend_choosing_video)
                    !same &&
                        sample.ready &&
                        localMedia?.key == remote.media.key &&
                        localMedia.duration > 0 &&
                        remote.media.duration > 0 ->
                        text(R.string.room_duration_mismatch)
                    !same -> text(R.string.room_prepare_friend_episode)
                    state.value.localHold -> text(R.string.room_local_pause_hint)
                    else -> text(R.string.room_prepare_sync)
                },
            )
            return
        }
        val scheduled = remote.resumeAt
        val scheduledPlay = scheduled != null && hostTime >= scheduled && remote.playRequested && !remote.buffering
        val moving = !remote.paused || scheduledPlay
        val elapsed = ((hostTime - maxOf(remote.at, scheduled ?: remote.at)) / 1000.0).coerceIn(0.0, 12.0)
        val target = (remote.position + if (moving) elapsed * remote.speed else 0.0).coerceIn(
            0.0,
            localMedia!!.duration.takeIf { it > 0 } ?: remote.media?.duration?.takeIf { it > 0 } ?: 86_400.0,
        )
        val pausePending = pendingCommand?.command == "pause"
        val atEnd = sample.ended && localMedia.duration > 0 && target >= localMedia.duration - .5
        val shouldPause = !moving || sample.buffering || pausePending || atEnd
        expectsPaused = shouldPause
        if (sample.paused != shouldPause) player.pause(shouldPause)
        if (!sample.buffering && sample.ready) {
            val forced = remote.seekRevision != appliedSeekRevision
            val correction = driftCorrector.correct(
                sample.position,
                target,
                baseSpeed,
                shouldPause,
                forced || time - lastSeek >= 15_000,
                time,
            )
            if (forced || correction.seek != null) {
                if (abs(sample.position - target) > 0.18) {
                    player.seek(target)
                    lastSeek = time
                }
                appliedSeekRevision = remote.seekRevision
            }
            setSpeed(correction.speed)
        } else {
            setSpeed(baseSpeed)
        }
        mutableState.value = state.value.copy(
            phase = when {
                remote.buffering || sample.buffering || remote.prebuffering -> WatchPhase.Buffering
                state.value.resumeSeconds != null -> WatchPhase.Starting
                shouldPause -> WatchPhase.Paused
                else -> WatchPhase.Playing
            },
            latencyMs = clock.latency,
            driftMs = ((target - sample.position) * 1000).roundToLong(),
            message = when {
                closedMessage.isNotEmpty() -> closedMessage
                remote.buffering || sample.buffering -> {
                    val blocked = remote.peers.values.firstOrNull { !it.ready || it.buffering }
                    blocked?.let { it.name + ": " + it.problem.description(text) } ?: text(R.string.room_prepare_video)
                }
                remote.prebuffering -> text(R.string.room_initial_prebuffer)
                state.value.resumeSeconds != null -> text(R.string.room_resume_seconds, state.value.resumeSeconds!!)
                shouldPause && remote.pausedBy.isNotBlank() -> text(R.string.room_paused_by, remote.pausedBy)
                shouldPause -> text(R.string.room_paused_sentence)
                abs(target - sample.position) > 3.0 -> text(R.string.room_catching_up)
                else -> text(R.string.room_watching_together)
            },
        )
    }

    private fun probeClock(time: Long) {
        if (time - lastPing >= if (clock.ready) 10_000 else 1500) {
            pings.add(time)
            if (pings.size > 8) pings.remove(pings.min())
            send(message(WatchMessageType.Ping).copy(ping = time))
            lastPing = time
        }
    }

    private fun setSpeed(speed: Double) {
        if (appliedSpeed == null || abs(appliedSpeed!! - speed) > 0.0001) {
            appliedSpeed = speed
            managedSpeeds.addLast(speed)
            if (managedSpeeds.size > 16) managedSpeeds.removeFirst()
            player.speed(speed)
        }
    }

    private fun message(type: WatchMessageType): WatchMessage = WatchMessage(
        type = type,
        coordinationVersion = 2,
        readingVersion = 2,
        readingSessionVersion = 1,
        interactionVersion = 1,
        interactionSession = interactionEpoch,
        removedMembers = removedMembers.toSet(),
        admissionLocked = removedMembers.isNotEmpty(),
        tvVersion = 1,
        observer = observing,
        observerVersion = 1,
        liveMode = liveMode,
        sequence = ++sequence,
        at = now(),
    )
    private fun send(message: WatchMessage) = transport?.send(message)
}
