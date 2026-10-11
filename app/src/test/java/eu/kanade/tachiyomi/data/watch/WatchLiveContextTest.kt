package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.data.tv.TvRoomEnvelope
import eu.kanade.tachiyomi.data.tv.TvRoomKind
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvRoomSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchLiveContextTest {
    private class Player : WatchPlayer {
        var forbidden = false
        var calls = 0
        private fun touch() {
            check(!forbidden) { "VOD adapter touched in live mode" }
            calls++
        }
        override fun sample(): WatchPlayback {
            touch()
            return WatchPlayback(null, 0.0, true, false, false, 1.0)
        }
        override fun pause(paused: Boolean) {
            touch()
        }
        override fun seek(seconds: Double) {
            touch()
        }
        override fun speed(value: Double) {
            touch()
        }
    }

    private class Endpoint(override val publicKey: String) : WatchTransport {
        var receive: (String, WatchMessage) -> Unit = { _, _ -> }
        var connection: (Int) -> Unit = {}
        val sent = mutableListOf<WatchMessage>()
        override fun start(onMessage: (String, WatchMessage) -> Unit, onConnection: (Int) -> Unit) {
            receive = onMessage
            connection = onConnection
            connection(1)
        }
        override fun send(message: WatchMessage) {
            sent += message
        }
        override fun close() {}
    }

    @Test fun `live room does not sample pause seek or change a VOD player even on reconnect and leave`() = runTest {
        val player = Player()
        lateinit var network: Endpoint
        val controller = WatchRoomController(
            backgroundScope,
            testRoomText,
            player,
            { testScheduler.currentTime },
            wallMillis = { 1_800_000_000_000L },
            transportFactory = { _, identity ->
                Endpoint(identity.publicKey).also {
                    network =
                        it
                }
            },
        )
        controller.setLiveMode(true)
        player.forbidden = true
        controller.create("Viewer")
        runCurrent()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(controller.state.value.liveMode)
        assertEquals(0, player.calls)
        assertFalse(controller.requestPause(true))
        network.connection(0)
        runCurrent()
        advanceTimeBy(5000)
        runCurrent()
        controller.hold()
        controller.offerSkip("unused", target = 10.0)
        controller.leave()
        assertFalse(controller.active)
        assertEquals(0, player.calls)
        assertTrue(network.sent.all { it.tvVersion == 1 })
    }

    @Test fun `TV payload requires a capability explicit recipient and cannot carry reading data`() {
        val envelope = TvRoomEnvelope(
            kind = TvRoomKind.Selection,
            selection = TvRoomSelection(TvChannelRef(42, "opaque-feed"), "Channel", 1),
        )
        val base = WatchMessage(type = WatchMessageType.Status, sequence = 1, at = 1, tv = envelope)
        assertFalse(base.valid())
        assertFalse(base.copy(tvVersion = 1).valid())
        assertTrue(base.copy(tvVersion = 1, target = "a".repeat(64)).valid())
        assertFalse(base.copy(tvVersion = 1, target = "a".repeat(64), tv = envelope.copy(version = 2)).valid())
    }

    @Test fun `owner identity and peer capabilities reach TV while duplicates are ignored`() = runTest {
        val owner = WatchIdentity()
        val invite = WatchInvite.create(owner.publicKey, 1_800_000_000_000L)
        lateinit var network: Endpoint
        val player = Player()
        val controller = WatchRoomController(
            backgroundScope,
            testRoomText,
            player,
            { testScheduler.currentTime },
            wallMillis = { 1_800_000_000_000L },
            transportFactory = { _, identity ->
                Endpoint(identity.publicKey).also {
                    network =
                        it
                }
            },
        )
        controller.join(invite.encode(), "Guest")
        runCurrent()
        var received = 0
        controller.onTvMessage = { _, _ -> received++ }
        val timeline = WatchMessage(
            type = WatchMessageType.Timeline,
            sequence = 1,
            at = 1,
            coordinationVersion = 2,
            tvVersion = 1,
            liveMode = true,
            peers = mapOf(owner.publicKey to WatchPeerStatus("Host", true, false, tvVersion = 1)),
        )
        network.receive(owner.publicKey, timeline)
        runCurrent()
        player.forbidden = true
        val payload = WatchMessage(
            type = WatchMessageType.Status,
            sequence = 2,
            at = 2,
            coordinationVersion = 2,
            tvVersion = 1,
            target = network.publicKey,
            tv = TvRoomEnvelope(
                kind = TvRoomKind.Selection,
                selection = TvRoomSelection(TvChannelRef(42, "opaque-feed"), "Channel", 1),
            ),
        )
        network.receive(owner.publicKey, payload)
        runCurrent()
        network.receive(owner.publicKey, payload)
        runCurrent()
        assertEquals(1, received)
        assertEquals(owner.publicKey, controller.tvContext().ownerId)
        assertEquals(1, controller.tvContext().members[owner.publicKey])
        controller.leave()
        owner.clear()
    }
}
