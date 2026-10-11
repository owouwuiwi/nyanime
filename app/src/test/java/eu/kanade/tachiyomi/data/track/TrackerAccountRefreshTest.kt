package eu.kanade.tachiyomi.data.track

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class TrackerAccountRefreshTest {
    @Test
    fun `logout discards a late account response`() = runTest {
        var identity: String? = "person"
        val refresh = TrackerAccountRefresh { identity }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        var applied: String? = null
        val job = async {
            refresh.execute({
                started.complete(Unit)
                response.await()
            }, { applied = it })
        }
        started.await()
        refresh.invalidate { identity = null }
        response.complete("old account")
        assertFalse(job.await())
        assertEquals(null, applied)
        assertFalse(refresh.state.value)
    }

    @Test
    fun `repeated refreshes share one active request without queueing another`() = runTest {
        val refresh = TrackerAccountRefresh { "person" }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Int>()
        var calls = 0
        var applied = 0
        val first = async {
            refresh.execute({
                calls++
                started.complete(Unit)
                response.await()
            }, { applied = it })
        }
        started.await()
        assertFalse(
            refresh.execute({
                calls++
                2
            }, { applied = it }),
        )
        response.complete(1)
        assertTrue(first.await())
        assertEquals(1, calls)
        assertEquals(1, applied)
    }

    @Test
    fun `failures and cancellation clear busy state and allow a later refresh`() = runTest {
        val refresh = TrackerAccountRefresh { "person" }
        try {
            refresh.execute({ throw IOException("Unavailable") }, {})
        } catch (_: IOException) { }
        assertFalse(refresh.state.value)
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Int>()
        val job = launch {
            refresh.execute({
                started.complete(Unit)
                response.await()
            }, { error("Cancelled") })
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(refresh.state.value)
        assertTrue(refresh.execute({ 3 }, { assertEquals(3, it) }))
    }

    @Test
    fun `identifier search accepts positive numeric IDs without guessing malformed inputs`() {
        assertEquals(123L, trackerSearchId("  ID: 123  "))
        listOf("Example 123", "id:0", "id:-1", "id:1.5", "id:", "id:9999999999999999999999").forEach {
            assertEquals(null, trackerSearchId(it))
        }
    }
}
