package eu.kanade.tachiyomi.cast

import eu.kanade.tachiyomi.data.cast.CastVolumeQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CastVolumeQueueTest {
    @Test
    fun rapidKeysAccumulateWhileOnlyTheLatestTargetWaitsForTheReceiver() {
        val queue = CastVolumeQueue()
        repeat(5) { queue.adjust(1, 0.2f, 0.05f) }
        val inFlight = queue.take()!!
        assertEquals(0.45f, inFlight.value, 0.001f)
        repeat(3) { queue.adjust(1, 0.2f, 0.05f) }
        queue.complete(inFlight)
        assertFalse(queue.isLatest(inFlight))
        val next = queue.take()!!
        assertEquals(0.6f, next.value, 0.001f)
        queue.complete(next)
        assertNull(queue.take())
        queue.adjust(1, 0.8f, 0.05f)
        assertEquals(0.85f, queue.take()!!.value, 0.001f)
    }

    @Test
    fun changingReceiversDiscardsTheOldVolumeBaselineAndClampsTheNewValue() {
        val queue = CastVolumeQueue()
        queue.set(1, 0.8f)
        queue.adjust(2, 0.2f, 0.05f)
        assertEquals(CastVolumeQueue.Target(2, 0.25f), queue.take())
        queue.set(2, 4f)
        assertEquals(1f, queue.take()!!.value)
    }
}
