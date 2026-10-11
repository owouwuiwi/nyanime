package eu.kanade.tachiyomi.data.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceHandoffTest {
    private class Player(val device: String, var position: Long) : DeviceHandoff.Player {
        var playing = true
        var available = true
        var released = false
        var pauses = 0
        var resumes = 0
        val ref = SyncReference(source = 1, titleUrl = "/series", itemUrl = "/episode")
        override fun snapshot(): DevicePlayback? {
            check(!released)
            return if (available) DevicePlayback(device, ref, position, playing) else null
        }
        override fun pause(): Boolean {
            check(!released)
            pauses++
            playing = false
            return available
        }
        override fun resume(position: Long) {
            check(!released)
            resumes++
            this.position = position
            playing = true
        }
        override fun message(text: String) {
            check(!released)
        }
    }

    @Test fun `only a prepared second player transfers and duplicate take is acknowledged once more`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val now = 100_000L
            val fromA = mutableListOf<PrivateAction>()
            val fromB = mutableListOf<PrivateAction>()
            val a = DeviceHandoff({ "a".repeat(32) }, fromA::add, { now })
            val b = DeviceHandoff({ "b".repeat(32) }, fromB::add, { now })
            val first = Player("a".repeat(32), 42_000)
            val second = Player("b".repeat(32), 10_000)
            a.attach(first)
            a.tick()
            b.receive(fromA.removeAt(0))
            second.available = false
            b.attach(second)
            assertTrue(first.playing)
            assertTrue(fromB.isEmpty())
            second.available = true
            b.attach(second)
            a.receive(fromB.removeAt(0))
            assertTrue(first.playing) // An offer is not permission to stop A.
            b.receive(fromA.removeAt(0))
            val take = fromB.removeAt(0)
            a.receive(take)
            assertFalse(first.playing)
            b.receive(fromA.removeAt(0))
            assertTrue(second.playing)
            assertEquals(42_000L, second.position)
            a.receive(take)
            assertEquals(1, first.pauses)
            assertEquals("device.ack", fromA.last().type)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `disabling sync resumes a pending local player and drops all references before release`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var now = 100_000L
            val messages = mutableListOf<PrivateAction>()
            val handoff = DeviceHandoff({ "b".repeat(32) }, messages::add, { now })
            val player = Player("b".repeat(32), 10_000)
            val remote = DevicePlayback("a".repeat(32), player.ref, 42_000, true)
            handoff.receive(
                PrivateAction(
                    type = "device.presence",
                    request = "presence",
                    body = communityJson.encodeToString(DevicePlayback.serializer(), remote),
                    expires = now + 15_000,
                ),
            )
            handoff.attach(player)
            assertFalse(player.playing)
            handoff.disconnect()
            assertTrue(player.playing)
            assertEquals(10_000L, player.position)
            assertEquals(1, player.resumes)
            player.released = true
            messages.clear()
            now += 5000
            handoff.tick()
            assertTrue(messages.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `detached player is never touched by delayed replies or a timeout`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var now = 100_000L
            val messages = mutableListOf<PrivateAction>()
            val handoff = DeviceHandoff({ "b".repeat(32) }, messages::add, { now })
            val player = Player("b".repeat(32), 10_000)
            val remote = DevicePlayback("a".repeat(32), player.ref, 42_000, true)
            handoff.receive(
                PrivateAction(
                    type = "device.presence",
                    request = "presence",
                    body = communityJson.encodeToString(DevicePlayback.serializer(), remote),
                    expires =
                    now + 15_000,
                ),
            )
            handoff.attach(player)
            assertEquals(1, player.pauses)
            handoff.detach(player)
            player.released = true
            now += 4000
            handoff.tick()
            assertEquals(0, player.resumes)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
