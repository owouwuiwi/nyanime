package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

class FineVolumeCurveTest {
    private val decibels = doubleArrayOf(
        Double.NEGATIVE_INFINITY,
        -48.0, -36.0, -28.0, -22.0, -17.0, -13.0, -10.0, -8.0, -6.0, -4.0, -3.0, -2.0, -1.0, -0.5, 0.0,
    )
    private val curve = FineVolumeCurve.fromDecibels(decibels)

    @Test
    fun `fine adjustment changes actual mixer gain within the same Android step`() {
        val lower = curve.setting(0.50f, 30)
        val upper = curve.setting(0.51f, 30)
        assertEquals(lower.streamIndex, upper.streamIndex)
        assertTrue(upper.softwareVolume > lower.softwareVolume)
        assertEquals(0.50f, curve.level(lower.streamIndex, lower.softwareVolume), 0.00001f)
        assertEquals(0.51f, curve.level(upper.streamIndex, upper.softwareVolume), 0.00001f)
    }

    @Test
    fun `effective audio gain is continuous across every Android boundary`() {
        for (index in 1..14) {
            val level = index / 15f
            val before = amplitude(curve.setting(level - 0.00001f, 30))
            val after = amplitude(curve.setting(level + 0.00001f, 30))
            assertTrue(after > before)
            assertEquals(before, after, 0.001)
        }
    }

    @Test
    fun `a full sweep is monotonic and can reverse without losing the fractional position`() {
        var previous = -1.0
        for (percent in 0..1300) {
            val level = percent / 1000f
            val setting = curve.setting(level, 30)
            val gain = amplitude(setting)
            assertTrue(gain >= previous, "level=$level")
            assertEquals(level, curve.level(setting.streamIndex, setting.softwareVolume), 0.00001f)
            previous = gain
        }
        for (percent in 1300 downTo 0) {
            val level = percent / 1000f
            val setting = curve.setting(level, 30)
            assertEquals(level, curve.level(setting.streamIndex, setting.softwareVolume), 0.00001f)
        }
    }

    @Test
    fun `two hundred percent applies two hundred to the actual mixer at maximum hardware volume`() {
        val setting = curve.setting(2f, 100)
        assertEquals(15, setting.streamIndex)
        assertEquals(200.0, setting.softwareVolume, 0.0)
        assertEquals(2f, curve.level(setting.streamIndex, setting.softwareVolume), 0f)
        assertEquals(8.0, amplitude(setting), 0.00001)
    }

    @Test
    fun `mute unity and amplification obey the actual cap`() {
        assertEquals(0f, curve.level(0, 100.0), 0f)
        assertEquals(100.0, curve.setting(1f, 30).softwareVolume, 0.00001)
        assertEquals(130.0, curve.setting(2f, 30).softwareVolume, 0.00001)
        assertEquals(100.0, curve.setting(2f, 0).softwareVolume, 0.00001)
        assertEquals(0, curve.setting(Float.NaN, 30).streamIndex)
        assertEquals(0f, curve.level(8, Double.NaN), 0f)
    }

    @Test
    fun `missing invalid or non increasing device curves keep continuous software control`() {
        val curves = listOf(
            FineVolumeCurve.linear(15),
            FineVolumeCurve.fromDecibels(DoubleArray(16) { Double.NaN }),
            FineVolumeCurve.fromDecibels(DoubleArray(16)),
        )
        curves.forEach {
            val setting = it.setting(0.51f, 30)
            assertEquals(0.51f, it.level(setting.streamIndex, setting.softwareVolume), 0.00001f)
            assertTrue(setting.softwareVolume in 0.0..100.0)
        }
    }

    private fun amplitude(setting: FineVolumeCurve.Setting): Double {
        if (setting.streamIndex == 0) return 0.0
        return 10.0.pow(decibels[setting.streamIndex] / 20) * (setting.softwareVolume / 100).pow(3)
    }
}
