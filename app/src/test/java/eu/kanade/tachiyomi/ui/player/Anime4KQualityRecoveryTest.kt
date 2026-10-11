package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Anime4KQualityRecoveryTest {
    private val native = Anime4KMediaInfo(1920, 1080, 1920, 1080, 24.0, 60.0)
    private fun sample(second: Long, cost: Double = 8.0, drops: Long = 0) =
        Anime4KPerformanceSample(second * 1000, drops, 0, 0, cost, 0)

    @Test
    fun `two GPU overruns with real losses react before a full window`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..3) controller.onSample(sample(second, 100.0, second))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertEquals(Anime4KMode.ModeA, controller.onSample(sample(4, 100.0, 4))?.mode)
    }

    @Test
    fun `one isolated spike with one lost frame preserves quality`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..30) {
            controller.onSample(sample(second, if (second == 3L) 100.0 else 8.0, if (second >= 3) 1 else 0))
        }
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
    }

    @Test
    fun `output backpressure overrides optimistic GPU headroom when rendering is substantial`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..3) controller.onSample(sample(second, 26.0, (second - 2).coerceAtLeast(0) * 3))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertEquals(Anime4KMode.ModeA, controller.onSample(sample(4, 26.0, 6))?.mode)
    }

    @Test
    fun `an isolated presentation burst preserves quality despite substantial GPU work`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..40) {
            controller.onSample(sample(second, 26.0, if (second >= 4) 16 else 0))
        }
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
    }

    @Test
    fun `sustained smaller presentation losses are caught by consecutive full windows`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..16) controller.onSample(sample(second, 26.0, second))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
    }

    @Test
    fun `cheap rendering and unknown source cadence do not get blamed for output losses`() {
        for (media in listOf(native, native.copy(frameRateKnown = false))) {
            val controller = Anime4KSmartController(media, 0)
            for (second in 1L..40) {
                controller.onSample(sample(second, if (media.frameRateKnown) 5.0 else 26.0, second * 3))
            }
            assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        }
    }

    @Test
    fun `presentation guard requires a measured stable decoder`() {
        for (missingDecoder in listOf(false, true)) {
            val controller = Anime4KSmartController(native, 0)
            for (second in 1L..40) {
                controller.onSample(
                    sample(second, 26.0, second * 3).copy(
                        decoderDroppedFrames = if (missingDecoder) null else second,
                    ),
                )
            }
            assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        }
    }

    @Test
    fun `pause and timing gaps clear consecutive presentation losses`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..3) controller.onSample(sample(second, 26.0, (second - 2).coerceAtLeast(0) * 3))
        controller.onSample(sample(4, 26.0, 3).copy(playing = false))
        controller.onSample(sample(20, 26.0, 6))
        controller.onSample(sample(21, 26.0, 9))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
    }

    @Test
    fun `presentation losses reject a quality recovery even when GPU time alone fits`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..4) controller.onSample(sample(second, 100.0, second))
        for (second in 5L..42) controller.onSample(sample(second, drops = 4))
        assertTrue(controller.diagnostics.probing)
        for (second in 43L..48) controller.onSample(sample(second, 26.0, 4 + (second - 42) * 3))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        assertFalse(controller.diagnostics.probing)
        for (second in 49L..250) controller.onSample(sample(second, drops = 22))
        assertEquals(1, controller.diagnostics.recoveryAttempts)
    }

    @Test
    fun `recent pass costs catch overload hidden by the moving average`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..4) {
            controller.onSample(sample(second, drops = second).copy(lastRenderTimeMillis = 100.0))
        }
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
    }

    @Test
    fun `failed recovery restores exactly the previous level and is never repeated`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..4) controller.onSample(sample(second, 100.0, second))
        for (second in 5L..42) controller.onSample(sample(second, drops = 4))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertTrue(controller.diagnostics.probing)
        for (second in 43L..45) controller.onSample(sample(second, 100.0, second))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        for (second in 46L..250) controller.onSample(sample(second, drops = 45))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        assertEquals(1, controller.diagnostics.recoveryAttempts)
    }

    @Test
    fun `recovery requires a lossless stable period and is spaced by two minutes`() {
        val large = native.copy(targetWidth = 3840, targetHeight = 2160)
        val controller = Anime4KSmartController(large, 0)
        for (second in 1L..8) controller.onSample(sample(second, 100.0, second))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        for (second in 9L..46) controller.onSample(sample(second, drops = 8))
        assertEquals(Anime4KMode.ModeAPlus, controller.currentMode)
        assertEquals(1, controller.diagnostics.recoveryAttempts)
        for (second in 47L..160) controller.onSample(sample(second, drops = 8))
        assertEquals(Anime4KMode.ModeAPlus, controller.currentMode)
        for (second in 161L..210) controller.onSample(sample(second, drops = 8))
        assertEquals(Anime4KMode.ModeAPlusHq, controller.currentMode)
        assertEquals(2, controller.diagnostics.recoveryAttempts)
    }

    @Test
    fun `missing GPU measurements never manufacture recovery headroom`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..4) controller.onSample(sample(second, 100.0, second))
        for (second in 5L..300) controller.onSample(sample(second, drops = 4).copy(renderTimeMillis = null))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        assertEquals(0, controller.diagnostics.recoveryAttempts)
    }

    @Test
    fun `a recovery without measurable GPU timing returns within a bounded playing interval`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..4) controller.onSample(sample(second, 100.0, second))
        for (second in 5L..42) controller.onSample(sample(second, drops = 4))
        assertTrue(controller.diagnostics.probing)
        for (second in 43L..62) controller.onSample(sample(second, drops = 4).copy(renderTimeMillis = null))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        assertFalse(controller.diagnostics.probing)
        assertFalse(controller.hasStableMeasurement)
    }

    @Test
    fun `a lossless recovered level with real but modest margin is accepted`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..4) controller.onSample(sample(second, 100.0, second))
        for (second in 5L..42) controller.onSample(sample(second, drops = 4))
        for (second in 43L..52) controller.onSample(sample(second, cost = 37.0, drops = 4))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertFalse(controller.diagnostics.probing)
        assertTrue(controller.hasStableMeasurement)
    }

    @Test
    fun `pause and timestamp discontinuities cannot manufacture a failed probe`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..4) controller.onSample(sample(second, 100.0, second))
        for (second in 5L..42) controller.onSample(sample(second, drops = 4))
        controller.onSample(sample(43, drops = 4).copy(playing = false))
        controller.onSample(sample(3600, drops = 4).copy(playing = false))
        for (second in 3601L..3610) controller.onSample(sample(second, drops = 4))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertFalse(controller.diagnostics.probing)
    }

    @Test
    fun `intentional output drops above display rate do not trigger the fast guard`() {
        val controller = Anime4KSmartController(native.copy(framesPerSecond = 120.0), 0)
        val start = controller.currentMode
        for (second in 1L..6) controller.onSample(sample(second, cost = 30.0, drops = 60 * second))
        assertEquals(start, controller.currentMode)
    }

    @Test
    fun `fractional display cadence allows a rounding frame in intentional discards`() {
        val media = native.copy(framesPerSecond = 119.88, displayRefreshRate = 59.94)
        val controller = Anime4KSmartController(media, 0)
        val start = controller.currentMode
        for (second in 1L..6) controller.onSample(sample(second, cost = 30.0, drops = 60 * second))
        assertEquals(start, controller.currentMode)
    }

    @Test
    fun `recent matching calibration is a starting hint but not a permanent ceiling`() {
        val calibration = Anime4KCalibration(Anime4KMode.ModeA, 2, 1000)
        val controller = Anime4KSmartController(native, 0, calibration, 30_000)
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        for (second in 1L..45) controller.onSample(sample(second))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        val expired = Anime4KSmartController(native, 0, calibration, 7 * 60 * 60 * 1000L)
        assertEquals(Anime4KMode.ModeAHq, expired.currentMode)
    }

    @Test
    fun `quality reloads preserve the episode attempt limit and failed workload candidates`() {
        val budget = Anime4KRecoveryBudget()
        val first = Anime4KSmartController(native, 0, recoveryBudget = budget)
        for (second in 1L..4) first.onSample(sample(second, 100.0, second))
        for (second in 5L..42) first.onSample(sample(second, drops = 4))
        for (second in 43L..45) first.onSample(sample(second, 100.0, second))
        assertEquals(1, budget.attempts)
        val replacement = Anime4KSmartController(
            native,
            50_000,
            Anime4KCalibration(Anime4KMode.ModeA, 1, 1),
            1000,
            budget,
        )
        for (second in 51L..300) replacement.onSample(sample(second))
        assertEquals(Anime4KMode.ModeA, replacement.currentMode)
        assertEquals(1, replacement.diagnostics.recoveryAttempts)
    }

    @Test
    fun `a content treatment retains HQ and compatible secondary passes`() {
        val media = native.copy(contentTreatment = Anime4KMode.ModeB)
        assertEquals(Anime4KMode.ModeBHq, Anime4K.maxSmartMode(media))
        assertEquals(Anime4KMode.ModeBPlusHq, Anime4K.maxSmartMode(media.copy(targetWidth = 3840, targetHeight = 2160)))
        assertNotEquals(Anime4K.calibrationKey(native, "", "", "", ""), Anime4K.calibrationKey(media, "", "", "", ""))
    }
}
