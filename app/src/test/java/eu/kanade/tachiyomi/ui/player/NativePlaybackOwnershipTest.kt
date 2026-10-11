package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativePlaybackOwnershipTest {
    @Test fun `replacement waits for actual release and cannot steal native ownership`() = runTest {
        val gate = NativePlaybackOwnership()
        val first = Any()
        val second = Any()
        var stopped = 0
        assertTrue(gate.acquire(first, { stopped++ }, { false }))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire(second, {}, { false }) }
        assertEquals(1, stopped)
        assertFalse(waiting.isCompleted)
        assertFalse(gate.release(second))
        assertFalse(waiting.isCompleted)
        assertTrue(gate.release(first))
        assertTrue(waiting.await())
        assertFalse(gate.release(first))
        assertTrue(gate.release(second))
    }

    @Test fun `cancelled pending activity never becomes an owner and preserves the current session`() = runTest {
        val gate = NativePlaybackOwnership()
        val first = Any()
        val second = Any()
        gate.acquire(first, {}, { false })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire(second, {}, { false }) }
        pending.cancel()
        pending.join()
        assertTrue(gate.release(first))
        assertFalse(gate.acquire(second, {}, { true }))
        assertFalse(gate.release(second))
    }

    @Test fun `rapid replacements initialize one at a time`() = runTest {
        val gate = NativePlaybackOwnership()
        val tokens = List(3) { Any() }
        gate.acquire(tokens[0], {}, { false })
        val second =
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.acquire(tokens[1], { gate.release(tokens[1]) }, { false })
            }
        val third = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire(tokens[2], {}, { false }) }
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)
        gate.release(tokens[0])
        assertTrue(second.await())
        assertTrue(third.await())
        assertTrue(gate.release(tokens[2]))
    }
}
