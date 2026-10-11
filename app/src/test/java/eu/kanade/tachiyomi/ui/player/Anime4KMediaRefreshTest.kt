package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Anime4KMediaRefreshTest {
    @Test
    fun `metadata bursts perform one deferred read per interval`() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var reads = 0
        val refresh = Anime4KMediaRefresh({ delay, callback -> scheduled += delay to callback }, { reads++ })
        repeat(1000) { refresh.request() }
        assertEquals(0, reads)
        assertEquals(1, scheduled.size)
        assertEquals(1000L, scheduled.single().first)
        scheduled.removeAt(0).second()
        assertEquals(1, reads)
        repeat(1000) { refresh.request() }
        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).second()
        assertEquals(2, reads)
    }

    @Test
    fun `old episode callbacks do not read or cancel the next episode refresh`() {
        val scheduled = mutableListOf<() -> Unit>()
        var reads = 0
        val refresh = Anime4KMediaRefresh({ _, callback -> scheduled += callback }, { reads++ })
        refresh.request()
        refresh.reset()
        refresh.request()
        scheduled[0]()
        assertEquals(0, reads)
        refresh.request()
        assertEquals(2, scheduled.size)
        scheduled[1]()
        assertEquals(1, reads)
    }
}
