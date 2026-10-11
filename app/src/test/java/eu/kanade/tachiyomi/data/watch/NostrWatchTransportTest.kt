package eu.kanade.tachiyomi.data.watch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in live test: two independent clients exchange only synthetic encrypted events, never user data. */
@EnabledIfEnvironmentVariable(named = "NYANIME_WATCH_NETWORK_TEST", matches = "1")
class NostrWatchTransportTest {
    @Test fun firstDefaultRelay() = roundtrip(WatchInvite.defaultRelays[0])

    @Test fun secondDefaultRelay() = roundtrip(WatchInvite.defaultRelays[1])

    private fun roundtrip(url: String) {
        val hostIdentity = WatchIdentity()
        val guestIdentity = WatchIdentity()
        val invite = WatchInvite.create(hostIdentity.publicKey, System.currentTimeMillis(), listOf(url))
        val host = NostrWatchTransport(invite, hostIdentity)
        val guest = NostrWatchTransport(invite, guestIdentity)
        host.diagnostics = { System.err.println("host: $it") }
        guest.diagnostics = { System.err.println("guest: $it") }
        val connected = CountDownLatch(2)
        val delivered = CountDownLatch(2)
        val hostCount = AtomicInteger()
        val guestCount = AtomicInteger()
        try {
            host.start(
                { sender, message ->
                    if (sender == guest.publicKey &&
                        message.command == "pause" &&
                        hostCount.getAndIncrement() == 0
                    ) {
                        delivered.countDown()
                    }
                },
                { if (it > 0) connected.countDown() },
            )
            guest.start(
                { sender, message ->
                    if (sender == host.publicKey &&
                        message.command == "play" &&
                        guestCount.getAndIncrement() == 0
                    ) {
                        delivered.countDown()
                    }
                },
                { if (it > 0) connected.countDown() },
            )
            assertTrue(connected.await(20, TimeUnit.SECONDS), "Subscription unavailable at " + url)
            for (sequence in 1L..8L) {
                host.send(
                    WatchMessage(type = WatchMessageType.Command, sequence = sequence, at = 1000, command = "play"),
                )
                guest.send(
                    WatchMessage(type = WatchMessageType.Command, sequence = sequence, at = 1000, command = "pause"),
                )
                if (delivered.await(1500, TimeUnit.MILLISECONDS)) break
            }
            assertEquals(0, delivered.count, "Encrypted messages did not travel in both directions through " + url)
            for (sequence in 9L..18L) {
                host.send(
                    WatchMessage(type = WatchMessageType.Command, sequence = sequence, at = 1000, command = "play"),
                )
                guest.send(
                    WatchMessage(type = WatchMessageType.Command, sequence = sequence, at = 1000, command = "pause"),
                )
                Thread.sleep(1500)
            }
            assertTrue(hostCount.get() >= 8, "Guest messages stopped arriving through " + url)
            assertTrue(guestCount.get() >= 8, "Host messages stopped arriving through " + url)
        } finally {
            host.close()
            guest.close()
        }
    }
}
