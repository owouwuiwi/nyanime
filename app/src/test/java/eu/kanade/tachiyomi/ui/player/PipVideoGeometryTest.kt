package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipVideoGeometryTest {
    @Test
    fun `missing aspect during a PiP transition uses the platform default`() {
        assertNull(PipVideoGeometry.fromAspect(null))
    }

    @Test
    fun `invalid and extreme MPV values never become Android dimensions`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE)
            .forEach { assertNull(PipVideoGeometry.fromAspect(it)) }
    }

    @Test
    fun `landscape portrait and square preserve their aspect`() {
        listOf(16.0 / 9, 9.0 / 16, 4.0 / 3, 1.0).forEach { aspect ->
            val size = requireNotNull(PipVideoGeometry.fromAspect(aspect))
            assertEquals(aspect, size.width.toDouble() / size.height, 0.0001)
        }
    }

    @Test
    fun `Android limits are checked in the output direction`() {
        assertNotNull(PipVideoGeometry.fromAspect(2.39))
        assertNotNull(PipVideoGeometry.fromAspect(1.0 / 2.39))
        assertNull(PipVideoGeometry.fromAspect(2.4))
        assertNull(PipVideoGeometry.fromAspect(1.0 / 2.4))
    }

    @Test
    fun `every accepted ratio is positive finite and within platform limits`() {
        for (i in 1..30_000) {
            val size = PipVideoGeometry.fromAspect(i / 10_000.0) ?: continue
            assertTrue(size.width > 0 && size.height > 0)
            assertTrue(size.width.toDouble() / size.height in (1.0 / 2.39)..2.39)
        }
    }
}
