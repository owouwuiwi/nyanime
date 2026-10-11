package eu.kanade.tachiyomi.data.tv

import nyanime.tv.api.TvStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TvLoadLaneTest {
    private val first = TvStream("first", "https://media.example/first", "Auto")
    private val second = first.copy(id = "second", url = "https://media.example/second")

    @Test fun `rapid channel switches stop once and only the last pending stream starts after idle`() {
        val calls = mutableListOf<String>()
        val lane = TvLoadLane({ calls += "stop" }, { stream, _ -> calls += stream.id })
        lane.initialized(true)
        lane.request(first, 1)
        assertEquals(1L, lane.eventTicket)
        lane.request(second, 2)
        lane.request(first, 3)
        assertNull(lane.eventTicket)
        assertEquals(listOf("first", "stop"), calls)
        lane.idle(true)
        assertEquals(listOf("first", "stop", "first"), calls)
        assertEquals(3L, lane.eventTicket)
    }

    @Test fun `loads wait for initialization and all queued work is inert after release`() {
        val calls = mutableListOf<String>()
        val lane = TvLoadLane({ calls += "stop" }, { _, _ -> calls += "load" })
        lane.request(first, 1)
        assertEquals(emptyList<String>(), calls)
        lane.initialized(true)
        assertEquals(listOf("load"), calls)
        lane.request(second, 2)
        lane.close()
        val closed = calls.toList()
        lane.idle(true)
        lane.request(first, 3)
        lane.initialized(true)
        assertEquals(closed, calls)
        assertNull(lane.eventTicket)
    }
}
