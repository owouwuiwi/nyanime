package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageRebindRangeTest {
    @Test
    fun `rebinding pages never notifies beyond the adapter bounds`() {
        assertEquals(null, pageRebindRange(0, 0))
        assertEquals(null, pageRebindRange(-1, 10))
        assertEquals(0..0, pageRebindRange(0, 1))
        assertEquals(0..3, pageRebindRange(0, 10))
        assertEquals(6..9, pageRebindRange(9, 10))
        assertEquals(2..8, pageRebindRange(5, 10))
        assertEquals(6..9, pageRebindRange(12, 10))
    }
}
