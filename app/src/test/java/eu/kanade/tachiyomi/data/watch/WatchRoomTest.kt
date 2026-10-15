package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.data.watch.testRoomText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class WatchRoomTest {
    private class Player(private val now: () -> Long) : WatchPlayer {
        var media: WatchMedia? = WatchMedia("Test Animation", "Episode 1", 1.0, 1400.0, 7, "/title", "/1")
        var ready = true
        var buffering = false
        var paused = true
        var speed = 1.0
        var seeks = 0
        var resumed = 0
        var ended = false
        var upcoming: WatchMedia? = null
        var canAdvance = true
        var preparedNextKey: String? = null
        var problem = WatchProblem.None
        var nextProblem = WatchProblem.None
        var bufferedAhead: Double? = null
        var advances = 0
        private var position = 40.0
        private var last = now()
        override fun sample(): WatchPlayback {
            val at = now()
            if (!paused && ready && !buffering) position += (at - last) / 1000.0 * speed
            last = at
            return WatchPlayback(
                media, position, paused, ready, buffering, speed, ended, problem, upcoming,
                canAdvance, preparedNextKey, nextProblem, bufferedAhead,
            )
        }
        override fun pause(paused: Boolean) {
            sample()
            this.paused = paused
        }
        override fun seek(seconds: Double) {
            sample()
            position = seconds
            seeks++
        }
        override fun speed(value: Double) {
            sample()
            speed = value
        }
        override fun userResumed() {
            resumed++
        }
        override fun advance(media: WatchMedia) {
            advances++
            this.media = media.copy(duration = 1400.0)
            upcoming = null
            ended = false
            seek(0.0)
        }
    }

    private class Network {
        val endpoints = mutableListOf<Endpoint>()
        var intercept: ((Endpoint, WatchMessage, () -> Unit) -> Unit)? = null
        fun factory(
            invite: WatchInvite,
            identity: WatchIdentity,
        ): WatchTransport = Endpoint(invite, identity.publicKey).also(endpoints::add)
        inner class Endpoint(val invite: WatchInvite, override val publicKey: String) : WatchTransport {
            var receiver: (String, WatchMessage) -> Unit = { _, _ -> }
            var connection: (Int) -> Unit = {}
            var online = true
            var closed = false
            var retries = 0
            val sent = mutableListOf<WatchMessage>()
            override fun start(onMessage: (String, WatchMessage) -> Unit, onConnection: (Int) -> Unit) {
                receiver = onMessage
                connection = onConnection
                connection(2)
            }
            override fun send(message: WatchMessage) {
                sent.add(message)
                if (!online || closed) return
                val delivery = {
                    endpoints.filter { it !== this && !it.closed && it.online && it.invite.topic == invite.topic }
                        .forEach { it.receiver(publicKey, message) }
                }
                intercept?.invoke(this, message, delivery) ?: delivery()
            }
            override fun close() {
                closed = true
            }
            override fun retryUnavailable() {
                retries++
            }
        }
    }

    private class Pairing(val scope: TestScope) {
        val network = Network()
        val hostPlayer = Player { scope.testScheduler.currentTime }
        val guestPlayer = Player { scope.testScheduler.currentTime }
        val host =
            WatchRoomController(scope.backgroundScope, testRoomText, hostPlayer, { scope.testScheduler.currentTime }, {
                1_800_000_000_000L +
                    scope.testScheduler.currentTime
            }, network::factory)
        val guest =
            WatchRoomController(scope.backgroundScope, testRoomText, guestPlayer, {
                scope.testScheduler.currentTime +
                    90_000
            }, {
                1_800_000_000_000L +
                    scope.testScheduler.currentTime
            }, network::factory)
        fun join() {
            host.create("Host")
            scope.runCurrent()
            guest.join(host.state.value.invite, "Friend")
            scope.advanceTimeBy(5000)
            scope.runCurrent()
        }
        fun advance(ms: Long = 5000) {
            scope.advanceTimeBy(ms)
            scope.runCurrent()
        }
    }

    @Test
    fun logicalEndDoesNotKeepRestartingGuestsWhenTheOwnerIsHeldBehind() = runTest {
        val pair = Pairing(this)
        pair.hostPlayer.media = pair.hostPlayer.media!!.copy(duration = 50.0)
        pair.guestPlayer.media = pair.guestPlayer.media!!.copy(duration = 50.0)
        pair.join()
        pair.host.requestPause(false)
        pair.advance(4000)
        pair.host.hold()
        pair.advance(20_000)
        assertTrue(pair.guestPlayer.paused)
        assertTrue(pair.hostPlayer.paused)
        assertFalse(pair.host.state.value.playRequested)
        assertEquals(0, pair.hostPlayer.advances)
        pair.host.leave()
        pair.guest.leave()
    }

    @Test
    fun removalImmediatelyUpdatesRosterEvenWhenRelaysAreUnavailable() = runTest {
        val pair = Pairing(this)
        pair.join()
        val own = pair.host.state.value.localMemberId
        val guest = pair.host.state.value.members.first { it.id != own }.id
        pair.network.endpoints[0].online = false
        pair.network.endpoints[0].connection(0)
        runCurrent()
        pair.host.removeMember(guest)
        assertFalse(pair.host.state.value.members.any { it.id == guest })
        assertFalse(guest in pair.host.interactionContext().members)
        pair.host.leave()
        pair.guest.leave()
    }

    @Test
    fun restoredChapterSurvivesPlaybackPauseDuringModeTransition() {
        // Android Main.immediate collectors can run inline during a button callback.
        // Use an immediate scope to reproduce that ordering instead of deferring all flows.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val network = Network()
            val player = Player { 10_000 }
            val host =
                WatchRoomController(scope, testRoomText, player, { 10_000 }, { 1_800_000_000_000 }, network::factory)
            val reading = eu.kanade.tachiyomi.data.reading.ReadingSessionController(
                scope,
                { 10_000 },
                host::readingHostClock,
                host::sendReadingSession,
                { if (it != null) host.sharedReadingSelected() },
            )
            scope.launch {
                host.state.collect { room ->
                    reading.roomChanged(host.readingSessionContext())
                    if (room.host && !room.sharedReadingMode) reading.exitReading()
                }
            }
            host.create("Host")
            val choice = eu.kanade.tachiyomi.data.reading.ReadingSelection(
                1,
                eu.kanade.tachiyomi.data.reading.ReadingChapter(7, "/manga", "/one", "Sample", "One"),
            )
            reading.restore(
                eu.kanade.tachiyomi.data.reading.ReadingSessionEnvelope(
                    kind = eu.kanade.tachiyomi.data.reading.ReadingSessionKind.Snapshot,
                    revision = 1,
                    selection = choice,
                ),
            )
            assertEquals(choice, reading.state.value.selection)
            assertTrue(host.state.value.sharedReadingMode)
            assertTrue(player.paused)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sharedReadingNegotiatesAndDoesNotAcceptAStaleSelectionAfterVideo() = runTest {
        val pair = Pairing(this)
        pair.join()
        val received = mutableListOf<eu.kanade.tachiyomi.data.reading.ReadingSelection?>()
        val hostReading = eu.kanade.tachiyomi.data.reading.ReadingSessionController(
            backgroundScope,
            { testScheduler.currentTime },
            pair.host::readingHostClock,
            pair.host::sendReadingSession,
            { if (it != null) pair.host.sharedReadingSelected() },
        )
        val guestReading = eu.kanade.tachiyomi.data.reading.ReadingSessionController(
            backgroundScope,
            { testScheduler.currentTime + 90000 },
            pair.guest::readingHostClock,
            pair.guest::sendReadingSession,
            {
                received += it
                if (it != null) pair.guest.sharedReadingSelected()
            },
        )
        hostReading.roomChanged(pair.host.readingSessionContext())
        guestReading.roomChanged(pair.guest.readingSessionContext())
        pair.host.onReadingSessionMessage = { sender, value -> hostReading.receive(sender, value) }
        pair.guest.onReadingSessionMessage = { sender, value -> guestReading.receive(sender, value) }
        assertTrue(guestReading.state.value.supported)
        hostReading.choose(eu.kanade.tachiyomi.data.reading.ReadingChapter(7, "/manga", "/one", "Sample", "One"))
        pair.advance(500)
        assertTrue(pair.guest.state.value.sharedReadingMode)
        assertEquals(1, received.size)
        val stale = pair.network.endpoints.first().sent.last { it.readingSession?.selection != null }
        pair.host.setReadingMode(false)
        pair.advance(4500)
        assertFalse(pair.guest.state.value.sharedReadingMode)
        pair.network.endpoints.last().receiver(pair.network.endpoints.first().publicKey, stale)
        assertEquals(1, received.size)
        assertFalse(pair.guest.state.value.sharedReadingMode)
    }

    @Test
    fun lateTvSelectionCannotOverrideANewerSharedChapter() = runTest {
        val pair = Pairing(this)
        pair.join()
        val owner = pair.network.endpoints.first()
        val guest = pair.network.endpoints.last()
        var delayed: WatchMessage? = null
        pair.network.intercept = { _, message, deliver ->
            if (message.tv != null) delayed = message else deliver()
        }
        pair.host.sendTv(
            eu.kanade.tachiyomi.data.tv.TvRoomEnvelope(
                kind = eu.kanade.tachiyomi.data.tv.TvRoomKind.Selection,
                selection = nyanime.tv.api.TvRoomSelection(nyanime.tv.api.TvChannelRef(7, "channel-one"), "Sample", 1),
            ),
            guest.publicKey,
        )
        pair.guest.onReadingSessionMessage = { _, _ -> pair.guest.sharedReadingSelected() }
        pair.host.sharedReadingSelected()
        pair.host.sendReadingSession(
            eu.kanade.tachiyomi.data.reading.ReadingSessionEnvelope(
                kind = eu.kanade.tachiyomi.data.reading.ReadingSessionKind.Snapshot,
                revision = 1,
                selection = eu.kanade.tachiyomi.data.reading.ReadingSelection(
                    1,
                    eu.kanade.tachiyomi.data.reading.ReadingChapter(7, "/manga", "/one", "Sample", "One"),
                ),
            ),
            guest.publicKey,
        )
        pair.advance(500)
        assertTrue(pair.guest.state.value.sharedReadingMode)
        var tvSelections = 0
        pair.guest.onTvMessage = { _, _ -> tvSelections++ }
        guest.receiver(owner.publicKey, requireNotNull(delayed))
        assertEquals(0, tvSelections)
        assertTrue(pair.guest.state.value.sharedReadingMode)
    }

    @Test
    fun resumedIdentityUsesAReservedSequenceRangeInsteadOfReplayingOldCounters() = runTest {
        val network = Network()
        val player = Player { testScheduler.currentTime }
        var secret = byteArrayOf()
        val wall = { 1_800_000_000_000L + testScheduler.currentTime }
        val host = WatchRoomController(
            backgroundScope,
            testRoomText,
            player,
            { testScheduler.currentTime },
            wall,
            network::factory,
            onSessionStarted = { _, identity, _ -> secret = identity.sessionSecret() },
        )
        host.create("Host")
        runCurrent()
        val guest = WatchRoomController(
            backgroundScope,
            testRoomText,
            Player { testScheduler.currentTime },
            { testScheduler.currentTime },
            wall,
            network::factory,
        )
        guest.join(host.state.value.invite, "Guest")
        advanceTimeBy(5000)
        runCurrent()
        val old = network.endpoints.first()
        old.closed = true
        val recovered = WatchRoomController(
            backgroundScope,
            testRoomText,
            player,
            { testScheduler.currentTime },
            wall,
            network::factory,
        )
        recovered.resume(old.invite, secret, "Host", 1L shl 32)
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(network.endpoints.last().sent.all { it.sequence > (1L shl 32) })
        assertEquals(recovered.state.value.localMemberId, host.state.value.localMemberId)
        assertTrue(guest.state.value.active)
        assertTrue(network.endpoints.last().sent.any { it.type == WatchMessageType.Timeline })
    }

    @Test
    fun anEmptyRoomDoesNotPublishRepeatedTimelines() = runTest {
        val room = Pairing(this)
        room.hostPlayer.media = null
        room.host.create("Host")
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(room.network.endpoints.single().sent.none { it.type == WatchMessageType.Timeline })
    }

    @Test
    fun aReadingRoomDoesNotClaimToBeOpenWithoutAUsableRelay() = runTest {
        val room = Pairing(this)
        room.host.setReadingMode(true)
        room.host.create("Reader")
        runCurrent()
        room.network.endpoints.single().connection(0)
        advanceTimeBy(13_000)
        runCurrent()
        assertEquals(WatchPhase.Reconnecting, room.host.state.value.phase)
        assertEquals("Connessione assente. Riprovo automaticamente…", room.host.state.value.message)
    }

    @Test
    fun hostPlayShowsPreparationBeforeTheNextTickAndCanBeCancelled() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.resumeByUser()
        assertTrue(room.host.state.value.preparingPlayback)
        assertTrue(room.hostPlayer.paused)
        assertEquals(null, room.host.state.value.resumeSeconds)
        room.host.requestPause(true)
        assertFalse(room.host.state.value.preparingPlayback)
        room.advance()
        assertTrue(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
    }

    @Test
    fun onlyHostCanEnableInitialPrebufferAndBothCachesFillInParallel() = runTest {
        val room = Pairing(this)
        room.join()
        room.guest.setPrebufferOnStart(true)
        assertFalse(room.host.state.value.prebufferOnStart)
        room.host.setPrebufferOnStart(true)
        room.advance(500)
        assertTrue(room.guest.state.value.prebufferOnStart)
        room.hostPlayer.bufferedAhead = 3.0
        room.guestPlayer.bufferedAhead = 6.0
        room.host.resumeByUser()
        room.advance(7000)
        assertTrue(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
        assertTrue(room.host.state.value.message.contains("precarica"))
        room.hostPlayer.bufferedAhead = 16.0
        room.guestPlayer.bufferedAhead = 16.0
        room.advance(7000)
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
        assertEquals(WatchPhase.Playing, room.host.state.value.phase)
    }

    @Test
    fun unavailableCacheEstimateCannotBlockTheInitialStartForever() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setPrebufferOnStart(true)
        room.host.resumeByUser()
        room.advance(3000)
        assertTrue(room.hostPlayer.paused)
        room.advance(7000)
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
    }

    @Test
    fun unknownHostDurationDoesNotRejectAnOtherwiseExactEpisode() = runTest {
        val room = Pairing(this)
        room.join()
        room.hostPlayer.media = room.hostPlayer.media!!.copy(duration = 0.0)
        room.advance(5000)
        assertFalse(room.guest.state.value.message.contains("durate"))
        assertEquals(WatchProblem.None, room.host.state.value.members.last().problem)
        room.hostPlayer.media = room.hostPlayer.media!!.copy(duration = 1400.0)
        room.advance(5000)
        assertTrue(room.host.state.value.members.last().ready)
    }

    @Test
    fun guestPlayShowsPendingFeedbackUntilAcknowledgedWithoutStartingEarly() = runTest {
        val room = Pairing(this)
        room.join()
        room.network.intercept = { _, message, deliver ->
            if (message.type != WatchMessageType.Command) deliver()
        }
        room.guest.resumeByUser()
        assertTrue(room.guest.state.value.preparingPlayback)
        assertTrue(room.guest.state.value.wantsPlayback)
        assertEquals(false, room.guest.state.value.pendingPlaybackPaused)
        room.advance(1000)
        assertTrue(room.guest.state.value.preparingPlayback)
        assertTrue(room.guestPlayer.paused)
        assertTrue(room.hostPlayer.paused)
        room.network.intercept = null
        room.advance(8000)
        assertEquals(null, room.guest.state.value.pendingPlaybackPaused)
        assertFalse(room.guest.state.value.preparingPlayback)
        assertFalse(room.guestPlayer.paused)
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 0.4)
    }

    @Test
    fun pendingPlayCancellationAndTimeoutDoNotLeaveAnIndefinitePreparationCue() = runTest {
        val room = Pairing(this)
        room.join()
        room.network.intercept = { _, message, deliver ->
            if (message.type != WatchMessageType.Command) deliver()
        }
        room.guest.resumeByUser()
        room.guest.requestPause(true)
        assertFalse(room.guest.state.value.wantsPlayback)
        assertFalse(room.guest.state.value.preparingPlayback)
        room.guest.resumeByUser()
        assertTrue(room.guest.state.value.preparingPlayback)
        room.advance(9000)
        assertFalse(room.guest.state.value.preparingPlayback)
        assertEquals(null, room.guest.state.value.pendingPlaybackPaused)
        assertTrue(room.guest.state.value.message.contains("non confermato"))
        assertTrue(room.guestPlayer.paused)
        room.guest.leave()
        assertFalse(room.guest.state.value.preparingPlayback)
    }

    @Test
    fun sharedActionsKeepTheirAuthorAndExpireDespiteRepeatedTimelines() = runTest {
        val room = Pairing(this)
        room.join()
        room.guest.requestSeek(135.0)
        room.advance(1000)
        val activity = room.host.state.value.activity!!
        assertEquals("Friend", activity.name)
        assertEquals(room.network.endpoints[1].publicKey, activity.actorId)
        assertEquals("Friend è andato a 2:15", activity.label(testRoomText))
        assertEquals(activity, room.guest.state.value.activity)
        room.advance(3000)
        assertEquals(null, room.host.state.value.activity)
        assertEquals(null, room.guest.state.value.activity)
        room.host.requestPause(true)
        room.advance(500)
        assertEquals("Host ha messo in pausa", room.guest.state.value.activity?.label(testRoomText))
        assertTrue(room.guest.state.value.activity!!.id > activity.id)
    }

    @Test
    fun reconnectSnapshotDoesNotReplayAnOldAction() = runTest {
        val room = Pairing(this)
        room.join()
        val endpoint = room.network.endpoints[1]
        endpoint.online = false
        endpoint.connection(0)
        room.advance(250)
        room.host.requestPause(true)
        room.advance(250)
        endpoint.online = true
        endpoint.connection(2)
        room.advance(2500)
        assertEquals(null, room.guest.state.value.activity)
    }

    @Test
    fun changingEpisodeClearsActionFromThePreviousEpisode() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.requestPause(true)
        room.advance(250)
        assertNotNull(room.guest.state.value.activity)
        room.hostPlayer.media = room.hostPlayer.media!!.copy(episodeUrl = "/2", number = 2.0)
        room.advance(500)
        assertEquals(null, room.host.state.value.activity)
        assertEquals(null, room.guest.state.value.activity)
    }

    @Test
    fun failedOfflineCommandExpiresAndAnExplicitRetryHasAFreshSequence() = runTest {
        val room = Pairing(this)
        room.join()
        val endpoint = room.network.endpoints[1]
        endpoint.online = false
        endpoint.connection(0)
        room.guest.resumeByUser()
        val lost = endpoint.sent.last { it.type == WatchMessageType.Command }
        room.advance(9000)
        assertTrue(room.guest.state.value.commandFailed)
        assertEquals(WatchRecovery.Command, room.guest.state.value.recovery)
        assertFalse(room.guest.state.value.preparingPlayback)
        endpoint.online = true
        endpoint.connection(2)
        room.guest.retryFailedCommand()
        val retry = endpoint.sent.last { it.type == WatchMessageType.Command }
        assertTrue(retry.sequence > lost.sequence)
        assertEquals(lost.media?.key, retry.media?.key)
        assertEquals(1, endpoint.retries)
        room.advance(8000)
        assertFalse(room.guest.state.value.commandFailed)
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
    }

    @Test
    fun lateAcknowledgementClearsFailureWithoutSendingAnotherCommand() = runTest {
        val room = Pairing(this)
        room.join()
        var deliverCommand: (() -> Unit)? = null
        room.network.intercept = { _, message, deliver ->
            if (message.type == WatchMessageType.Command) deliverCommand = deliver else deliver()
        }
        room.guest.requestSeek(100.0)
        room.advance(9000)
        assertTrue(room.guest.state.value.commandFailed)
        val count = room.network.endpoints[1].sent.count { it.type == WatchMessageType.Command }
        deliverCommand!!()
        room.advance(500)
        assertFalse(room.guest.state.value.commandFailed)
        assertEquals(count, room.network.endpoints[1].sent.count { it.type == WatchMessageType.Command })
        assertEquals(100.0, room.hostPlayer.sample().position, 0.2)
    }

    @Test
    fun failedRetryCannotSeekAnotherEpisodeOrClearTheLocalHold() = runTest {
        val room = Pairing(this)
        room.join()
        room.network.intercept = { _, message, deliver ->
            if (message.type != WatchMessageType.Command) deliver()
        }
        room.guest.requestSeek(100.0)
        room.advance(9000)
        val endpoint = room.network.endpoints[1]
        val count = endpoint.sent.size
        room.guestPlayer.media = room.guestPlayer.media!!.copy(episodeUrl = "/2")
        room.guest.retryFailedCommand()
        assertEquals(count, endpoint.sent.size)
        assertFalse(room.guest.state.value.commandFailed)
        room.guest.hold()
        room.advance(9000)
        assertTrue(room.guest.state.value.localHold)
        room.guest.retryFailedCommand()
        assertTrue(room.guest.state.value.localHold)
        assertTrue(room.guestPlayer.paused)
    }

    @Test
    fun manualConnectionRecoveryIsThrottledAndShowsFreshProgress() = runTest {
        val room = Pairing(this)
        room.join()
        val endpoint = room.network.endpoints[1]
        endpoint.online = false
        endpoint.connection(0)
        room.advance(14_000)
        assertEquals(WatchRecovery.Connection, room.guest.state.value.recovery)
        room.guest.retryConnection()
        room.guest.retryConnection()
        assertEquals(1, endpoint.retries)
        assertEquals(0, room.guest.state.value.waitingSeconds)
        assertTrue(room.guest.state.value.showPreparationFeedback)
        room.advance(2500)
        room.guest.retryConnection()
        assertEquals(2, endpoint.retries)
        assertTrue(room.guestPlayer.paused)
    }

    @Test
    fun retryCannotTargetOldLocalMediaAfterANewHostSelectionArrives() = runTest {
        val room = Pairing(this)
        room.join()
        room.network.intercept = { _, message, deliver ->
            if (message.type != WatchMessageType.Command) deliver()
        }
        room.guest.requestSeek(120.0)
        room.advance(9000)
        val host = room.network.endpoints[0]
        val guest = room.network.endpoints[1]
        val selected = room.hostPlayer.media!!.copy(episodeUrl = "/2")
        val timeline = host.sent.last { it.type == WatchMessageType.Timeline }
        guest.receiver(host.publicKey, timeline.copy(sequence = timeline.sequence + 1, media = selected))
        runCurrent()
        assertEquals(selected.key, room.guest.state.value.media?.key)
        val count = guest.sent.size
        room.guest.retryFailedCommand()
        assertEquals(count, guest.sent.size)
        assertFalse(room.guest.state.value.commandFailed)
    }

    @Test
    fun automaticSkipDoesNotAnnounceAHumanSeek() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.offerSkip("opening", "Salta apertura", 120.0, 3)
        room.host.resumeByUser()
        room.advance(7000)
        assertEquals(1, room.hostPlayer.seeks)
        assertEquals(null, room.host.state.value.activity)
        assertEquals(null, room.guest.state.value.activity)
        assertFalse(room.network.endpoints[0].sent.any { it.activity?.command == "seek" })
    }

    @Test
    fun inactiveRoomControlsNeverReadOrModifyThePlayer() = runTest {
        val player = object : WatchPlayer {
            override fun sample(): WatchPlayback = error("Inactive room sampled the player")
            override fun pause(paused: Boolean): Unit = error("Inactive room changed pause")
            override fun seek(seconds: Double): Unit = error("Inactive room sought")
            override fun speed(value: Double): Unit = error("Inactive room changed speed")
            override fun userResumed(): Unit = error("Inactive room changed local safety")
        }
        val controller = WatchRoomController(backgroundScope, testRoomText, player, { testScheduler.currentTime })
        controller.playerAttached()
        controller.hold()
        controller.resync()
        controller.retryFailedCommand()
        controller.retryConnection()
        controller.confirmSameVideo()
        controller.offerSkip("unused", "Skip", 90.0)
        controller.setSharedControls(false)
        controller.setWaitForEveryone(false)
        assertFalse(controller.requestPause(true))
        assertFalse(controller.requestPause(false))
        assertFalse(controller.requestSeek(30.0))
        assertFalse(controller.requestSpeed(1.5))
        assertFalse(controller.resumeByUser())
        controller.cancelSkip()
        controller.cancelNext()
        controller.leave()
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(controller.active)
    }

    @Test
    fun codeJoinsRoomAndCorrectsDifferentDeviceClocks() = runTest {
        val room = Pairing(this)
        room.join()
        assertEquals(2, room.host.state.value.members.size)
        assertNotNull(room.guest.state.value.latencyMs)
        room.host.resumeByUser()
        room.advance()
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 0.4)
    }

    @Test
    fun bothParticipantsControlPlaybackWithoutEchoes() = runTest {
        val room = Pairing(this)
        room.join()
        room.guest.resumeByUser()
        room.advance()
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
        room.guest.requestPause(true)
        room.advance()
        assertTrue(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
        room.guest.requestSeek(300.5)
        room.advance()
        assertEquals(300.5, room.hostPlayer.sample().position, 0.2)
        assertEquals(300.5, room.guestPlayer.sample().position, 0.2)
        room.guest.requestSpeed(1.5)
        room.advance()
        assertEquals(1.5, room.hostPlayer.speed, 0.001)
        assertEquals(1.5, room.guestPlayer.speed, 0.001)
        assertEquals(4, room.network.endpoints[1].sent.count { it.type == WatchMessageType.Command })
    }

    @Test
    fun guestCanSeekWhileHostStillSeesItsPreviousBufferingStatus() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setWaitForEveryone(true)
        room.host.resumeByUser()
        room.advance()
        room.guestPlayer.buffering = true
        room.guestPlayer.ready = false
        room.advance()
        assertTrue(room.hostPlayer.paused)
        // Releasing a seek gesture sends its command before the next presence update.
        room.guestPlayer.buffering = false
        room.guestPlayer.ready = true
        room.guest.requestSeek(400.0)
        room.advance()
        assertTrue(room.hostPlayer.sample().position >= 400.0)
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 0.4)
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
    }

    @Test
    fun bufferingPausesGroupUntilGuestIsReady() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setWaitForEveryone(true)
        room.host.resumeByUser()
        room.advance()
        room.guestPlayer.buffering = true
        room.advance()
        assertTrue(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
        room.guestPlayer.buffering = false
        room.advance()
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
    }

    @Test
    fun newRoomsLetTheOtherPlayerContinueThroughAGuestsBuffering() = runTest {
        val room = Pairing(this)
        room.join()
        assertFalse(room.host.state.value.waitForEveryone)
        room.host.resumeByUser()
        room.advance()
        val seeksBeforeBuffer = room.guestPlayer.seeks
        room.guestPlayer.buffering = true
        room.advance(2_000)
        assertFalse(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
        room.guestPlayer.buffering = false
        room.advance(5_000)
        assertFalse(room.guestPlayer.paused)
        // A brief delay recovers by small speed changes instead of invalidating the cache again.
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 3.0)
        assertEquals(seeksBeforeBuffer, room.guestPlayer.seeks)
        room.advance(120_000)
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 1.0)
    }

    @Test
    fun prolongedGuestBufferingNeverPausesTheOtherPlayerAndRecoversLocally() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.resumeByUser()
        room.advance()
        room.guestPlayer.buffering = true
        room.advance(20_000)
        assertFalse(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
        room.guestPlayer.buffering = false
        room.advance(5_000)
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 3.0)
    }

    @Test
    fun hostDecoderBufferingDoesNotFreezeTheAuthoritativeClock() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.resumeByUser()
        room.advance()
        val before = room.guestPlayer.sample().position
        room.hostPlayer.buffering = true
        room.advance(20_000)
        assertTrue(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
        assertTrue(room.guestPlayer.sample().position >= before + 19)
        room.hostPlayer.buffering = false
        room.advance(5000)
        assertFalse(room.hostPlayer.paused)
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 3.0)
    }

    @Test
    fun oneMissedPresenceUpdateDoesNotPauseBothPlayers() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.resumeByUser()
        room.advance()
        room.guest.resync()
        room.advance(500)
        assertFalse(room.hostPlayer.paused)
        room.network.intercept = { endpoint, message, deliver ->
            if (endpoint !== room.network.endpoints[1] || message.type != WatchMessageType.Status) deliver()
        }
        room.advance(7_000)
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
        room.advance(6_000)
        assertFalse(room.hostPlayer.paused)
    }

    @Test
    fun localSafetyHoldsCannotBeClearedRemotely() = runTest {
        val room = Pairing(this)
        room.join()
        room.guest.hold()
        room.advance()
        room.host.resumeByUser()
        room.advance()
        assertTrue(room.guestPlayer.paused)
        assertFalse(room.hostPlayer.paused)
        room.guest.resumeByUser()
        room.advance()
        assertFalse(room.guestPlayer.paused)
        assertFalse(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.resumed > 0)
    }

    @Test
    fun oldReadinessCannotReleaseNewEpisode() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setWaitForEveryone(true)
        room.host.resumeByUser()
        room.advance()
        room.hostPlayer.media = room.hostPlayer.media!!.copy(episode = "Episode 2", number = 2.0, episodeUrl = "/2")
        room.advance()
        assertTrue(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
        assertEquals(WatchPhase.DifferentVideo, room.guest.state.value.phase)
        room.guestPlayer.media = room.hostPlayer.media
        room.advance()
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
    }

    @Test
    fun lostCommandRetriesSurviveLaterStatusMessages() = runTest {
        val room = Pairing(this)
        room.join()
        var lost = false
        room.network.intercept = { _, message, deliver ->
            if (message.type == WatchMessageType.Command && !lost) lost = true else deliver()
        }
        room.guest.resumeByUser()
        room.advance(7000)
        assertTrue(lost)
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
        assertTrue(room.network.endpoints[1].sent.count { it.type == WatchMessageType.Command } >= 2)
    }

    @Test
    fun creatorDisappearancePausesAndLeavingRestoresSpeed() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.resumeByUser()
        room.advance()
        room.network.endpoints[0].online = false
        room.advance(14_000)
        assertTrue(room.guestPlayer.paused)
        assertEquals(WatchPhase.Reconnecting, room.guest.state.value.phase)
        room.guest.leave()
        assertFalse(room.guest.active)
        assertEquals(1.0, room.guestPlayer.speed, 0.0001)
        assertTrue(room.guestPlayer.paused)
    }

    @Test
    fun creatorClosingTerminatesRemoteControl() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.leave()
        room.advance()
        assertFalse(room.guest.active)
        assertEquals(WatchPhase.Closed, room.guest.state.value.phase)
    }

    @Test
    fun roomCanExistBeforeChoosingVideo() = runTest {
        val room = Pairing(this)
        room.hostPlayer.media = null
        room.hostPlayer.ready = false
        room.guestPlayer.media = null
        room.guestPlayer.ready = false
        room.join()
        assertTrue(room.host.active)
        assertTrue(room.guest.active)
        assertEquals(WatchPhase.Waiting, room.guest.state.value.phase)
        room.hostPlayer.media = WatchMedia("Test Animation", "Episode 1", 1.0, 1400.0)
        room.hostPlayer.ready = true
        room.advance()
        assertEquals("Test Animation", room.guest.state.value.media?.title)
    }

    @Test
    fun differentCutDoesNotGetForcedIntoSynchronization() = runTest {
        val room = Pairing(this)
        room.guestPlayer.media = room.guestPlayer.media!!.copy(duration = 1000.0)
        room.join()
        room.host.resumeByUser()
        room.advance()
        assertTrue(room.guestPlayer.paused)
        assertFalse(room.hostPlayer.paused)
        assertEquals(WatchPhase.DifferentVideo, room.guest.state.value.phase)
    }

    @Test
    fun disabledGuestControlsStillAllowLocalSafetyHold() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setSharedControls(false)
        room.advance()
        room.guest.resumeByUser()
        room.advance()
        assertTrue(room.hostPlayer.paused)
        room.host.resumeByUser()
        room.advance()
        room.guest.hold()
        room.advance()
        assertTrue(room.guestPlayer.paused)
        assertFalse(room.hostPlayer.paused)
    }

    @Test
    fun delayedOlderSeekCannotUndoLatestSeek() = runTest {
        val room = Pairing(this)
        room.join()
        var delayed: (() -> Unit)? = null
        room.network.intercept = { _, message, deliver ->
            if (message.type == WatchMessageType.Command && message.position == 100.0) delayed = deliver else deliver()
        }
        room.guest.requestSeek(100.0)
        room.guest.requestSeek(400.0)
        room.advance()
        delayed?.invoke()
        room.advance()
        assertEquals(400.0, room.hostPlayer.sample().position, 0.2)
        assertEquals(400.0, room.guestPlayer.sample().position, 0.2)
    }

    @Test
    fun playIntentCanBeCancelledWhileBuffering() = runTest {
        val room = Pairing(this)
        room.join()
        room.guestPlayer.buffering = true
        room.host.resumeByUser()
        room.advance()
        assertTrue(room.guest.state.value.playRequested)
        room.guest.requestPause(true)
        room.advance()
        room.guestPlayer.buffering = false
        room.advance()
        assertTrue(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
        assertFalse(room.host.state.value.playRequested)
    }

    @Test
    fun closedRoomIgnoresQueuedMessagesFromPreviousSession() = runTest {
        val room = Pairing(this)
        room.join()
        val previousEndpoint = room.network.endpoints[1]
        room.guest.leave()
        room.guest.create("New host")
        val newCode = room.guest.state.value.invite
        previousEndpoint.receiver(
            room.network.endpoints[0].publicKey,
            WatchMessage(type = WatchMessageType.Closed, sequence = 9999, at = 0),
        )
        room.advance()
        assertTrue(room.guest.active)
        assertTrue(room.guest.state.value.host)
        assertEquals(newCode, room.guest.state.value.invite)
    }

    @Test
    fun roomLimitRejectsAnExtraGuestClearly() = runTest {
        val room = Pairing(this)
        room.join()
        val guests = (1..7).map {
            WatchRoomController(
                backgroundScope,
                testRoomText,
                Player { testScheduler.currentTime },
                { testScheduler.currentTime },
                { 1_800_000_000_000L + testScheduler.currentTime },
                room.network::factory,
            ).also {
                it.join(room.host.state.value.invite, "Friend")
                room.advance()
            }
        }
        assertEquals(8, room.host.state.value.members.size)
        assertFalse(guests.last().active)
        assertEquals(WatchPhase.Failed, guests.last().state.value.phase)
    }

    @Test
    fun stableReadinessAndSharedCountdownAreCancelledByPause() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.resumeByUser()
        room.advance(1500)
        assertTrue(room.hostPlayer.paused)
        assertEquals(WatchPhase.Starting, room.host.state.value.phase)
        assertEquals(room.host.state.value.resumeSeconds, room.guest.state.value.resumeSeconds)
        room.guest.requestPause(true)
        room.advance()
        assertTrue(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
        assertEquals(null, room.host.state.value.resumeSeconds)
        assertTrue(room.host.state.value.message.contains("Friend"))
        assertTrue(room.guest.state.value.message.contains("Friend"))
    }

    @Test
    fun readinessFlappingNeverProducesRepeatedStarts() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setWaitForEveryone(true)
        room.host.resumeByUser()
        repeat(4) {
            room.guestPlayer.buffering = true
            room.advance(500)
            room.guestPlayer.buffering = false
            room.advance(750)
            assertTrue(room.hostPlayer.paused)
        }
        room.advance()
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
    }

    @Test
    fun sharedSkipIsAppliedOnceAndLateDuplicateIsHarmless() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.offerSkip("opening-1", "Salta apertura", 120.0)
        room.advance()
        room.guest.requestSkip()
        room.guest.requestSkip()
        room.advance()
        assertEquals(120.0, room.hostPlayer.sample().position, 0.2)
        assertEquals(120.0, room.guestPlayer.sample().position, 0.2)
        val seeks = room.hostPlayer.seeks
        room.host.offerSkip("opening-1", "Salta apertura", 120.0)
        room.advance()
        assertEquals(null, room.host.state.value.skip)
        assertEquals(seeks, room.hostPlayer.seeks)
    }

    @Test
    fun automaticSkipWaitsForPlaybackAndGuestCanCancelItForEveryone() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.offerSkip("opening-1", "Salta apertura", 120.0, 3)
        room.advance(8000)
        assertEquals(40.0, room.hostPlayer.sample().position, 0.2)
        room.host.resumeByUser()
        room.advance(3750)
        assertNotNull(room.guest.state.value.skipSeconds)
        room.guest.cancelSkip()
        room.advance(7000)
        assertEquals(null, room.host.state.value.skip)
        assertTrue(room.hostPlayer.sample().position < 60)
    }

    @Test
    fun nextEpisodeWaitsForPreparationAndCancellationIsShared() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setWaitForEveryone(true)
        val next = room.hostPlayer.media!!.copy(episodeUrl = "/2", episode = "Episode 2", number = 2.0, duration = 0.0)
        room.hostPlayer.upcoming = next
        room.hostPlayer.ended = true
        room.advance()
        assertEquals(next, room.guest.state.value.next?.media)
        assertEquals(null, room.host.state.value.nextSeconds)
        room.guestPlayer.preparedNextKey = next.key
        room.advance(1000)
        assertNotNull(room.host.state.value.nextSeconds)
        room.guest.cancelNext()
        room.advance(15_000)
        assertEquals(0, room.hostPlayer.advances)
        assertEquals(null, room.guest.state.value.next)
    }

    @Test
    fun nextEpisodeRespectsTimerHoldAndAdvancesOnlyOnceAfterAllAreReady() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setWaitForEveryone(true)
        val next = room.hostPlayer.media!!.copy(episodeUrl = "/2", episode = "Episode 2", number = 2.0, duration = 0.0)
        room.hostPlayer.upcoming = next
        room.hostPlayer.ended = true
        room.guestPlayer.preparedNextKey = next.key
        room.guestPlayer.canAdvance = false
        room.advance(15_000)
        assertEquals(0, room.hostPlayer.advances)
        room.guestPlayer.canAdvance = true
        room.advance(15_000)
        assertEquals(1, room.hostPlayer.advances)
        room.advance(15_000)
        assertEquals(1, room.hostPlayer.advances)
        assertTrue(room.hostPlayer.paused)
    }

    @Test
    fun reconnectedGuestWaitsForFreshHostStateInsteadOfReusingPlayIntent() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.resumeByUser()
        room.advance()
        assertFalse(room.guestPlayer.paused)
        val guestEndpoint = room.network.endpoints[1]
        guestEndpoint.online = false
        guestEndpoint.connection(0)
        room.advance(1000)
        assertTrue(room.guestPlayer.paused)
        room.network.endpoints[0].online = false
        guestEndpoint.online = true
        guestEndpoint.connection(2)
        room.advance(1000)
        assertTrue(room.guestPlayer.paused)
        assertEquals(WatchPhase.Waiting, room.guest.state.value.phase)
        room.network.endpoints[0].online = true
        room.advance(6000)
        assertFalse(room.hostPlayer.paused)
        assertFalse(room.guestPlayer.paused)
    }

    @Test
    fun connectionLossDiscardsNextEpisodeDeadlineBeforeReconnecting() = runTest {
        val room = Pairing(this)
        room.join()
        val next = room.hostPlayer.media!!.copy(episodeUrl = "/2", episode = "Episode 2", number = 2.0, duration = 0.0)
        room.hostPlayer.upcoming = next
        room.hostPlayer.ended = true
        room.guestPlayer.preparedNextKey = next.key
        room.advance(1000)
        assertNotNull(room.host.state.value.nextSeconds)
        val endpoint = room.network.endpoints[0]
        endpoint.online = false
        endpoint.connection(0)
        room.advance(14_000)
        assertEquals(0, room.hostPlayer.advances)
        assertEquals(null, room.host.state.value.nextSeconds)
        assertEquals(null, room.guest.state.value.nextSeconds)
        assertEquals(null, room.guest.state.value.next?.deadline)
        endpoint.online = true
        endpoint.connection(2)
        room.advance(4500)
        assertEquals(0, room.hostPlayer.advances)
        assertNotNull(room.host.state.value.nextSeconds)
        room.advance(12_000)
        assertEquals(1, room.hostPlayer.advances)
    }

    @Test
    fun unresolvedGuestCanCancelTheSharedNextCue() = runTest {
        val room = Pairing(this)
        room.join()
        val next = room.hostPlayer.media!!.copy(episodeUrl = "/2", episode = "Episode 2", number = 2.0, duration = 0.0)
        room.hostPlayer.upcoming = next
        room.hostPlayer.ended = true
        room.guestPlayer.media = null
        room.guestPlayer.ready = false
        room.advance()
        assertEquals(WatchPhase.DifferentVideo, room.guest.state.value.phase)
        assertNotNull(room.guest.state.value.next)
        room.guest.cancelNext()
        room.advance()
        assertEquals(null, room.host.state.value.next)
        assertEquals(null, room.guest.state.value.next)
        assertEquals(0, room.hostPlayer.advances)
    }

    @Test
    fun silentlyLostGuestReadinessCannotAdvanceAnEpisode() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setWaitForEveryone(true)
        val next = room.hostPlayer.media!!.copy(episodeUrl = "/2", episode = "Episode 2", number = 2.0, duration = 0.0)
        room.hostPlayer.upcoming = next
        room.hostPlayer.ended = true
        room.guestPlayer.preparedNextKey = next.key
        room.advance(1000)
        assertNotNull(room.host.state.value.nextSeconds)
        room.network.endpoints[1].online = false
        room.advance(13_000)
        assertEquals(0, room.hostPlayer.advances)
        assertEquals(null, room.host.state.value.nextSeconds)
        assertEquals(WatchProblem.Connection, room.host.state.value.members.last().problem)
        assertTrue(room.host.state.value.message.contains("Friend"))
        assertTrue(room.host.state.value.message.contains("Riconnessione"))
    }

    @Test
    fun automaticSkipPublishesTheNewPositionAndSeeksOnlyOnce() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.offerSkip("opening-1", "Salta apertura", 120.0, 3)
        room.host.resumeByUser()
        room.advance(12_000)
        assertEquals(1, room.hostPlayer.seeks)
        assertEquals(null, room.host.state.value.skip)
        assertTrue(room.hostPlayer.sample().position >= 120)
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 0.4)
        val timeline = room.network.endpoints[0].sent.last { it.type == WatchMessageType.Timeline }
        assertTrue(timeline.position >= 120)
    }

    @Test
    fun readingMessagesUseExistingRoomWithoutConsumingVideoStatus() = runTest {
        val room = Pairing(this)
        room.join()
        val received = mutableListOf<eu.kanade.tachiyomi.data.reading.ReadingEnvelope>()
        room.host.onReadingMessage = { _, message, _ -> received.add(message) }
        val payload = eu.kanade.tachiyomi.data.reading.ReadingEnvelope(
            kind = eu.kanade.tachiyomi.data.reading.ReadingKind.Presence,
            peer = eu.kanade.tachiyomi.data.reading.ReadingPeer("Reader"),
        )
        room.guest.sendReading(payload)
        room.advance(500)
        assertEquals(listOf(payload), received)
        assertEquals("Friend", room.host.state.value.members.last().name)
        assertEquals(2, room.network.endpoints.size)
        assertEquals(room.host.state.value.invite, room.guest.state.value.invite)
    }

    @Test
    fun readingGuestDoesNotPauseOrBlockPeopleWatchingVideo() = runTest {
        val room = Pairing(this)
        room.join()
        room.guest.setReadingMode(true)
        room.advance()
        room.host.resumeByUser()
        room.advance(8000)
        assertFalse(room.hostPlayer.paused)
        assertTrue(room.guestPlayer.paused)
        assertEquals(2, room.host.state.value.members.size)
        assertFalse(room.guest.requestSeek(600.0))
        assertEquals(0, room.hostPlayer.seeks)
    }

    @Test
    fun readingGuestDoesNotBlockOrMislabelNextEpisodePreparation() = runTest {
        val room = Pairing(this)
        room.join()
        room.guest.setReadingMode(true)
        room.advance()
        val next = room.hostPlayer.media!!.copy(episodeUrl = "/2", episode = "Episode 2", number = 2.0, duration = 0.0)
        room.hostPlayer.upcoming = next
        room.hostPlayer.ended = true
        room.advance(1000)
        assertNotNull(room.host.state.value.nextSeconds)
        assertFalse(room.host.state.value.message.contains("Friend"))
        room.advance(12000)
        assertEquals(1, room.hostPlayer.advances)
        assertTrue(room.guestPlayer.paused)
        assertEquals(0, room.guestPlayer.advances)
    }

    @Test
    fun returningFromReadingToVideoRetainsRoomAndResynchronizes() = runTest {
        val room = Pairing(this)
        room.join()
        val identity = room.guest.state.value.localMemberId
        room.guest.setReadingMode(true)
        room.advance()
        room.host.resumeByUser()
        room.advance(15000)
        room.guest.setReadingMode(false)
        room.guest.playerAttached()
        room.guest.resumeByUser()
        room.advance(12000)
        assertEquals(identity, room.guest.state.value.localMemberId)
        assertFalse(room.guestPlayer.paused)
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 0.5)
    }

    @Test
    fun readingOnlyHostNeverCallsPlayerEvenForVideoCommandsAndClosure() = runTest {
        val network = Network()
        val player = object : WatchPlayer {
            override fun sample(): WatchPlayback = error("Reader must not query player")
            override fun pause(paused: Boolean) {
                error("Reader must not pause player")
            }
            override fun seek(seconds: Double) {
                error("Reader must not seek player")
            }
            override fun speed(value: Double) {
                error("Reader must not change player speed")
            }
        }
        val host =
            WatchRoomController(backgroundScope, testRoomText, player, {
                testScheduler.currentTime
            }, { 1800000000000L }, network::factory)
        host.setReadingMode(true)
        host.create("Reader")
        runCurrent()
        assertTrue(host.active)
        val guest =
            WatchRoomController(
                backgroundScope,
                testRoomText,
                Player {
                    testScheduler.currentTime
                },
                { testScheduler.currentTime },
                { 1800000000000L },
                network::factory,
            )
        guest.join(host.state.value.invite, "Friend")
        advanceTimeBy(5000)
        runCurrent()
        guest.requestPause(true)
        advanceTimeBy(1000)
        runCurrent()
        host.hold()
        host.leave()
        assertFalse(host.active)
    }

    @Test
    fun oldHostNeverReceivesReadingPayloadAsVideoStatus() = runTest {
        val room = Pairing(this)
        room.join()
        val endpoint = room.network.endpoints[0]
        val previous = endpoint.sent.last { it.type == WatchMessageType.Timeline }
        room.network.endpoints[1].receiver(
            endpoint.publicKey,
            previous.copy(sequence = previous.sequence + 1000, readingVersion = 0),
        )
        runCurrent()
        val sent = room.network.endpoints[1].sent.size
        room.guest.sendReading(
            eu.kanade.tachiyomi.data.reading.ReadingEnvelope(
                kind = eu.kanade.tachiyomi.data.reading.ReadingKind.Presence,
                peer = eu.kanade.tachiyomi.data.reading.ReadingPeer("Reader"),
            ),
        )
        assertEquals(sent, room.network.endpoints[1].sent.size)
    }

    @Test
    fun actionablePeerProblemsAreVisibleWithoutSharingSourceErrors() = runTest {
        val room = Pairing(this)
        room.join()
        room.host.setWaitForEveryone(true)
        room.guestPlayer.ready = false
        room.guestPlayer.problem = WatchProblem.MissingSource
        room.advance()
        assertTrue(room.host.state.value.message.contains("Friend"))
        assertTrue(room.host.state.value.message.contains("Estensione"))
        assertEquals(WatchProblem.MissingSource, room.host.state.value.members.last().problem)
    }

    @Test
    fun observerDoesNotPauseOrBlockParticipantsStillWatching() = runTest {
        val room = Pairing(this)
        room.join()
        assertTrue(room.guest.supportsObserver)
        room.guestPlayer.ready = false
        room.guestPlayer.buffering = false
        assertTrue(room.guest.setObserver(true))
        room.advance()
        room.host.resumeByUser()
        room.advance(8000)
        assertFalse(room.hostPlayer.paused)
        assertTrue(room.guest.state.value.observer)
        assertTrue(room.host.state.value.members.last().observer)
        assertFalse(room.host.state.value.members.last().ready)
        assertEquals(2, room.host.state.value.members.size)
    }

    @Test
    fun coordinatorMayObserveWithoutBlockingAReadyGuest() = runTest {
        val room = Pairing(this)
        room.join()
        room.hostPlayer.ready = false
        assertTrue(room.host.setObserver(true))
        room.host.resumeByUser()
        room.advance(10000)
        assertTrue(room.host.state.value.observer)
        assertFalse(room.guestPlayer.paused)
        assertTrue(room.guest.state.value.members.first().observer)
    }

    @Test
    fun observerReentryIsOnlyAnnouncedAfterTheNativePlayerIsReady() = runTest {
        val room = Pairing(this)
        room.join()
        val identity = room.guest.state.value.localMemberId
        room.guestPlayer.ready = false
        room.guest.setObserver(true)
        room.host.resumeByUser()
        room.advance(15000)
        room.guest.playerAttached()
        room.advance(1000)
        assertTrue(room.guest.state.value.observer)
        assertFalse(room.hostPlayer.paused)
        room.guestPlayer.ready = true
        room.advance(12000)
        assertFalse(room.guest.state.value.observer)
        assertEquals(identity, room.guest.state.value.localMemberId)
        assertFalse(room.guestPlayer.paused)
        assertTrue(abs(room.hostPlayer.sample().position - room.guestPlayer.sample().position) < 0.5)
    }

    @Test
    fun observationRequiresTheOlderCoordinatorToAdvertiseSupport() = runTest {
        val room = Pairing(this)
        room.join()
        val endpoint = room.network.endpoints[0]
        val previous = endpoint.sent.last { it.type == WatchMessageType.Timeline }
        room.network.endpoints[1].receiver(
            endpoint.publicKey,
            previous.copy(sequence = previous.sequence + 1000, observerVersion = 0),
        )
        runCurrent()
        assertFalse(room.guest.supportsObserver)
        assertFalse(room.guest.setObserver(true))
        assertFalse(room.guest.state.value.observer)
    }
}
