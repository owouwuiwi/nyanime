package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.track.anilist.displayNumericScore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DisplayScoreTest {
    @Test
    fun `numeric display uses the selected scale without changing the stored score`() {
        val score = 85.0
        assertEquals("8", displayNumericScore(score, "POINT_10"))
        assertEquals("85", displayNumericScore(score, "POINT_100"))
        assertEquals("8.5", displayNumericScore(score, "POINT_10_DECIMAL"))
        assertEquals(85.0, score)
    }
}
