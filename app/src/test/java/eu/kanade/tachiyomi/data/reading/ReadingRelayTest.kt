package eu.kanade.tachiyomi.data.reading

import eu.kanade.tachiyomi.data.watch.NostrWatchTransport
import eu.kanade.tachiyomi.data.watch.WatchIdentity
import eu.kanade.tachiyomi.data.watch.WatchInvite
import eu.kanade.tachiyomi.data.watch.WatchMessage
import eu.kanade.tachiyomi.data.watch.WatchMessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Two real relay clients, synthetic catalog paths only, no device or user data. */
@EnabledIfEnvironmentVariable(named = "NYANIME_WATCH_NETWORK_TEST", matches = "1")
class ReadingRelayTest {
    @Test fun firstRelay() = exchange(WatchInvite.defaultRelays[0])

    @Test fun secondRelay() = exchange(WatchInvite.defaultRelays[1])

    private fun exchange(relay: String) {
        val hostIdentity = WatchIdentity()
        val guestIdentity = WatchIdentity()
        val invite = WatchInvite.create(hostIdentity.publicKey, System.currentTimeMillis(), listOf(relay))
        val host = NostrWatchTransport(invite, hostIdentity)
        val guest = NostrWatchTransport(invite, guestIdentity)
        val ready = CountDownLatch(2)
        val delivered = CountDownLatch(2)
        val hostReady = AtomicBoolean()
        val guestReady = AtomicBoolean()
        val receivedBoard = AtomicBoolean()
        val receivedPosition = AtomicBoolean()
        val page =
            ReadingPosition(7, "/synthetic-title", "/synthetic-chapter", "Synthetic manga", "Synthetic chapter", 3, 25)
        val board = ReadingEnvelope(
            kind = ReadingKind.Board,
            page = page,
            revision = 1,
            strokes = List(12) { index ->
                ReadingStroke(index.toString(16).padStart(32, '0'), host.publicKey, index % 5, 2, List(192) { 9999 })
            },
        )
        val presence =
            ReadingEnvelope(
                kind = ReadingKind.Presence,
                peer = ReadingPeer("Synthetic reader", page, true),
                revision = 1,
            )
        try {
            host.start({ author, message ->
                if (author == guest.publicKey &&
                    message.reading == presence &&
                    receivedPosition.compareAndSet(false, true)
                ) {
                    delivered.countDown()
                }
            }, { if (it > 0 && hostReady.compareAndSet(false, true)) ready.countDown() })
            guest.start({ author, message ->
                if (author == host.publicKey &&
                    message.reading == board &&
                    receivedBoard.compareAndSet(false, true)
                ) {
                    delivered.countDown()
                }
            }, { if (it > 0 && guestReady.compareAndSet(false, true)) ready.countDown() })
            assertTrue(ready.await(20, TimeUnit.SECONDS), "Relay subscription unavailable: $relay")
            for (sequence in 1L..8L) {
                host.send(WatchMessage(type = WatchMessageType.Status, sequence = sequence, at = 1, reading = board))
                guest.send(
                    WatchMessage(type = WatchMessageType.Status, sequence = sequence, at = 1, reading = presence),
                )
                if (delivered.await(1500, TimeUnit.MILLISECONDS)) break
            }
            assertEquals(0, delivered.count, "Encrypted reading payload did not round trip through $relay")
        } finally {
            host.close()
            guest.close()
        }
    }
}
