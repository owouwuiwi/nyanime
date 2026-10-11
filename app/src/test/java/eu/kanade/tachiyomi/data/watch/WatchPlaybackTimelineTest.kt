package eu.kanade.tachiyomi.data.watch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchPlaybackTimelineTest {
    @Test
    fun decoderObservationsCannotRewindTheSharedClock() {
        val clock = WatchPlaybackTimeline()
        clock.bind("entry:part", 40.0, 0)
        clock.update(true, 1.0, 0)
        repeat(60) { clock.bind("entry:part", 40.0, it * 250L) }
        assertEquals(55.0, clock.position(15_000, 1400.0))
        clock.update(false, 1.0, 15_000)
        assertEquals(55.0, clock.position(30_000, 1400.0))
    }

    @Test
    fun commandsPreserveTimeAndChangingContentStartsANewClock() {
        val clock = WatchPlaybackTimeline()
        clock.bind("first", 20.0, 0)
        clock.update(true, 1.0, 0)
        clock.speed(1.5, 4000)
        assertEquals(27.0, clock.position(6000, 100.0))
        clock.seek(90.0, 6000)
        assertEquals(100.0, clock.position(20_000, 100.0))
        clock.bind("second", 0.0, 20_000)
        assertEquals(0.0, clock.position(30_000, 100.0))
        clock.reset()
        clock.bind("first", 4.0, 40_000)
        assertEquals(4.0, clock.position(45_000, 0.0))
    }

    @Test
    fun provisionalManifestDurationsAreNotEvidenceOfAnotherEdition() {
        val verifier = WatchDurationVerifier()
        val title = WatchMedia("Example", "Part one", 1.0, 1200.0, 1, "/entry", "/part")
        assertFalse(verifier.incompatible(title.copy(duration = 0.0), title, 0))
        assertFalse(verifier.incompatible(title.copy(duration = 600.0), title, 1000))
        assertFalse(verifier.incompatible(title, title, 2000))
        assertFalse(verifier.incompatible(title, title, 9000))
        assertFalse(verifier.incompatible(title.copy(duration = 600.0), title, 10_000))
        assertTrue(verifier.incompatible(title.copy(duration = 600.0), title, 13_000))
        assertFalse(verifier.incompatible(title.copy(episodeUrl = "/other", duration = 600.0), title, 13_100))
    }
}
