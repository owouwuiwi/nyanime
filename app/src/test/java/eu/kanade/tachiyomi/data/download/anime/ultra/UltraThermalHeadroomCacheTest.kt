package eu.kanade.tachiyomi.data.download.anime.ultra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UltraThermalHeadroomCacheTest {
    @Test
    fun `successive controllers share one reading until the sampling interval expires`() {
        val cache = UltraThermalHeadroomCache()
        var reads = 0
        val sensor = {
            reads++
            if (reads == 1) 0.2f else 0.8f
        }
        assertEquals(0.2f, cache.sample(0, sensor))
        for (time in 1L..9_999L) assertEquals(0.2f, cache.sample(time, sensor))
        assertEquals(1, reads)
        assertEquals(0.8f, cache.sample(10_000, sensor))
        assertEquals(2, reads)
    }

    @Test
    fun `unsupported readings are cached instead of repeatedly querying the sensor`() {
        val cache = UltraThermalHeadroomCache()
        var reads = 0
        val sensor = {
            reads++
            if (reads == 1) Float.NaN else 0.4f
        }
        assertNull(cache.sample(100, sensor))
        assertNull(cache.sample(10_099, sensor))
        assertEquals(1, reads)
        assertEquals(0.4f, cache.sample(10_100, sensor))
        assertEquals(2, reads)
    }
}
