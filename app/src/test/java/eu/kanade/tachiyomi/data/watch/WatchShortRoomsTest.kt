package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.data.watch.testRoomText
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchShortRoomsTest {
    private class Network {
        val members = mutableListOf<Endpoint>()
        fun open(invite: WatchInvite, identity: WatchIdentity): WatchTransport =
            Endpoint(invite, identity.publicKey).also(members::add)

        inner class Endpoint(private val invite: WatchInvite, override val publicKey: String) : WatchTransport {
            var receiver: (String, WatchMessage) -> Unit = { _, _ -> }
            var closed = false
            override fun start(onMessage: (String, WatchMessage) -> Unit, onConnection: (Int) -> Unit) {
                receiver = onMessage
                onConnection(1)
            }
            override fun send(message: WatchMessage) {
                members.filter { it !== this && !it.closed && it.invite.topic == invite.topic }
                    .forEach { it.receiver(publicKey, message) }
            }
            override fun retryUnavailable() = Unit
            override fun close() {
                closed = true
            }
        }
    }

    @Test
    fun eightDigitsRequireHostApprovalAndNeverContainTheRoomSecret() = runTest {
        val network = Network()
        val owner = WatchIdentity()
        val invite = WatchInvite.create(owner.publicKey, System.currentTimeMillis()).encode()
        var granted = ""
        val host =
            WatchShortRooms(this, testRoomText, { _, _ ->
                error("Host must not join")
            }, network::open) { testScheduler.currentTime }
        val guest =
            WatchShortRooms(this, testRoomText, { room, _ ->
                granted = room
            }, network::open) { testScheduler.currentTime }
        host.host(invite, "Host")
        val code = host.state.value.code
        assertTrue(code.matches(Regex("[0-9]{8}")))
        assertFalse(code.contains(WatchInvite.parse(invite, System.currentTimeMillis()).secret))
        guest.join(code, "Friend")
        runCurrent()
        assertEquals("", granted)
        assertEquals(1, host.state.value.requests.size)
        host.approve(host.state.value.requests.single().id)
        runCurrent()
        assertEquals(invite, granted)
        host.close()
        guest.close()
        owner.clear()
    }

    @Test
    fun sharedKeyMatchesTvImplementation() {
        val first = WatchIdentity(ByteArray(32) { 1 })
        val second = WatchIdentity(ByteArray(32) { 2 })
        val expected = "4d139ed873f471dcdd34db5a715e20aee190b184a89214239db73aeebd915b11"
        assertEquals(expected, first.sharedKey(second.publicKey, "12345678").watchHex())
        assertEquals(expected, second.sharedKey(first.publicKey, "12345678").watchHex())
        first.clear()
        second.clear()
    }
}
