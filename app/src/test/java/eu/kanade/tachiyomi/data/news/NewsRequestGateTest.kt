package eu.kanade.tachiyomi.data.news

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewsRequestGateTest {
    @Test fun `requests are bounded globally and serialized per publisher`() = runTest {
        val gate = NewsRequestGate()
        val running = mutableMapOf<String, Int>()
        var active = 0
        var peak = 0
        (0..19).map { index ->
            async {
                val source = "publisher-${index % 5}"
                gate.run(source) {
                    running[source] = (running[source] ?: 0) + 1
                    active++
                    peak = maxOf(peak, active)
                    assertEquals(1, running[source])
                    assertTrue(active <= 3)
                    delay(20)
                    active--
                    running[source] = running.getValue(source) - 1
                }
            }
        }.awaitAll()
        assertEquals(3, peak)
        assertEquals(0, active)
    }

    @Test fun `cancellation and a failed publisher release every permit`() = runTest {
        val gate = NewsRequestGate(1)
        val job = launch { gate.run("one") { delay(10_000) } }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        runCatching { gate.run("one") { error("Unavailable") } }
        assertEquals("ready", gate.run("one") { "ready" })
        assertEquals("ready", gate.run("two") { "ready" })
    }
}
