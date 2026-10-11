package eu.kanade.tachiyomi.data.watch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class WatchObserverPlayerTest {
    @Test
    fun `observer timeline advances without advertising decoder readiness`() {
        var time = 0L
        val player =
            WatchObserverPlayer(
                WatchPlayback(WatchMedia("Example", "Episode 1", 1.0, 90.0), 10.0, false, true, false, 1.0),
            ) {
                time
            }
        time = 2500
        assertEquals(12.5, player.sample().position)
        assertFalse(player.sample().ready)
        assertFalse(player.sample().buffering)
        player.pause(true)
        time = 7000
        assertEquals(12.5, player.sample().position)
        player.seek(3.0)
        player.speed(2.0)
        player.pause(false)
        time = 8000
        assertEquals(5.0, player.sample().position)
    }
}
