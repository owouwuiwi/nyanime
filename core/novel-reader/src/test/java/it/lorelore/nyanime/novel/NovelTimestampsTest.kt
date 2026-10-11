package it.lorelore.nyanime.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelTimestampsTest {
    @Test
    fun `a clock moving backwards still permits a newer local edit`() {
        assertEquals(101L, nextNovelTimestamp(100, 50))
        assertEquals(200L, nextNovelTimestamp(100, 200))
    }

    @Test
    fun `extreme imported timestamps saturate rather than becoming negative`() {
        assertEquals(Long.MAX_VALUE, nextNovelTimestamp(Long.MAX_VALUE - 1, 0))
        assertEquals(Long.MAX_VALUE, nextNovelTimestamp(Long.MAX_VALUE, 0))
        assertEquals(Long.MAX_VALUE, nextNovelTimestamp(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
