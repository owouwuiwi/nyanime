package eu.kanade.presentation.reader.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VerticalChapterNavigatorTest {
    @Test
    fun `page navigation clamps to actual bounds and tolerates an unloaded chapter`() {
        assertEquals(1, navigatorPageAt(100f, 0f, 0))
        assertEquals(1, navigatorPageAt(Float.NaN, 100f, 20))
        assertEquals(1, navigatorPageAt(-10f, 100f, 20))
        assertEquals(20, navigatorPageAt(130f, 100f, 20))
        assertEquals(11, navigatorPageAt(50f, 100f, 21))
    }
}
