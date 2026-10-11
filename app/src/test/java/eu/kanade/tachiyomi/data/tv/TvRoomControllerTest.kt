package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.test.runTest
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvLiveObservation
import nyanime.tv.api.TvRoomSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvRoomControllerTest {
    private val channel = TvChannel(TvChannelRef(9, "opaque"), "Channel")

    @Test fun `only owner ordered selections change channel and duplicate snapshots do not reopen it`() = runTest {
        val opened = mutableListOf<TvRoomSelection>()
        val controller = TvRoomController(
            this,
            { 1_000 },
            { 1_800_000_000_000 },
            { _, _ -> },
            opened::add,
            { TvLiveObservation(0.0, null, false, false, false) },
            {},
            {},
        )
        controller.roomChanged(TvRoomContext(true, false, "local", "owner", mapOf("owner" to 1, "friend" to 1), true))
        val selection =
            TvRoomEnvelope(kind = TvRoomKind.Selection, selection = TvRoomSelection(channel.ref, channel.name, 1))
        controller.receive("friend", selection)
        assertTrue(opened.isEmpty())
        controller.receive("owner", selection)
        controller.receive("owner", selection)
        assertEquals(1, opened.size)
        controller.receive("owner", TvRoomEnvelope(kind = TvRoomKind.Exit, revision = 2))
        controller.receive("owner", selection)
        assertEquals(null, controller.state.value.selection)
        assertEquals(1, opened.size)
        controller.roomChanged(TvRoomContext())
    }

    @Test fun `older members prevent selecting live and local buffering emits no playback command`() = runTest {
        val sent = mutableListOf<TvRoomEnvelope>()
        val controller = TvRoomController(
            this,
            { 1_000 },
            { 1_800_000_000_000 },
            { value, _ -> sent += value },
            {},
            { TvLiveObservation(0.0, null, false, true, false) },
            {},
            {},
        )
        controller.roomChanged(TvRoomContext(true, true, "owner", "owner", mapOf("legacy" to 0), true))
        assertFalse(controller.select(channel))
        controller.roomChanged(TvRoomContext(true, true, "owner", "owner", mapOf("friend" to 1), true))
        assertTrue(controller.select(channel))
        testScheduler.runCurrent()
        assertTrue(sent.all { it.kind == TvRoomKind.Selection })
        controller.roomChanged(TvRoomContext())
    }

    @Test fun `joining older clients cannot receive live commands and ends the room live mode`() = runTest {
        val sent = mutableListOf<Pair<TvRoomEnvelope, String>>()
        var exits = 0
        val controller = TvRoomController(
            this,
            { 1_000 },
            { 1_800_000_000_000 },
            { value, target ->
                sent +=
                    value to target
            },
            {},
            { null },
            {},
            { exits++ },
        )
        controller.roomChanged(TvRoomContext(true, true, "owner", "owner", mapOf("friend" to 1), true))
        assertTrue(controller.select(channel))
        controller.roomChanged(TvRoomContext(true, true, "owner", "owner", mapOf("friend" to 1, "legacy" to 0), true))
        assertFalse(controller.state.value.supported)
        assertEquals(null, controller.state.value.selection)
        assertEquals(1, exits)
        assertTrue(sent.none { it.second == "legacy" || it.second.isEmpty() })
        assertTrue(sent.any { it.first.kind == TvRoomKind.Exit })
        controller.close()
    }

    @Test fun `a new room with the same owner resets revisions and close rejects late callbacks`() = runTest {
        val opened = mutableListOf<TvRoomSelection>()
        val controller =
            TvRoomController(this, { 1_000 }, { 1_800_000_000_000 }, { _, _ -> }, opened::add, { null }, {}, {})
        val room = TvRoomContext(true, false, "local", "owner", mapOf("owner" to 1), true, "first-room")
        controller.roomChanged(room)
        controller.receive(
            "owner",
            TvRoomEnvelope(kind = TvRoomKind.Selection, selection = TvRoomSelection(channel.ref, channel.name, 9)),
        )
        controller.roomChanged(room.copy(roomId = "second-room"))
        controller.receive(
            "owner",
            TvRoomEnvelope(kind = TvRoomKind.Selection, selection = TvRoomSelection(channel.ref, channel.name, 1)),
        )
        assertEquals(2, opened.size)
        controller.close()
        controller.roomChanged(room)
        controller.receive(
            "owner",
            TvRoomEnvelope(kind = TvRoomKind.Selection, selection = TvRoomSelection(channel.ref, channel.name, 10)),
        )
        assertFalse(controller.state.value.active)
        assertEquals(2, opened.size)
    }
}
