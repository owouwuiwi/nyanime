package eu.kanade.presentation.entries.details

import eu.kanade.tachiyomi.ui.entries.anime.EpisodeSeasonLoadGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpisodeSeasonLoadGateTest {
    @Test fun `one source lane serializes expansions without blocking other sources`() = runTest {
        val active = mutableMapOf<Long, Int>()
        val maximum = mutableMapOf<Long, Int>()
        var total = 0
        var maximumTotal = 0
        (0 until 20).map { index ->
            launch {
                val source = if (index % 2 == 0) 101L else 102L
                EpisodeSeasonLoadGate.forSource(source).withLock {
                    active[source] = active.getOrDefault(source, 0) + 1
                    maximum[source] = maxOf(maximum.getOrDefault(source, 0), active.getValue(source))
                    total++
                    maximumTotal = maxOf(maximumTotal, total)
                    delay(10)
                    active[source] = active.getValue(source) - 1
                    total--
                }
            }
        }.joinAll()
        assertEquals(mapOf(101L to 1, 102L to 1), maximum)
        assertEquals(2, maximumTotal)
        assertEquals(0, total)
    }
}
