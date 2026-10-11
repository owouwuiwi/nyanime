package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Anime4KSmartStabilityTest {
    private val native = Anime4KMediaInfo(1920, 1080, 1920, 1080, 24.0, 60.0)
    private fun sample(second: Long, cost: Double? = 10.0, drops: Long = 0) =
        Anime4KPerformanceSample(second * 1000, drops, 0, 0, cost, 0)

    @Test
    fun `GPU budget includes redraw cost on high refresh displays`() {
        assertEquals(80.0, Anime4K.renderCapacity(sample(1, 5.0).copy(redrawTimeMillis = 5.0), native)!!, 0.001)
        assertEquals(125.0, Anime4K.renderCapacity(sample(1, 5.0).copy(redrawTimeMillis = 2.0), native)!!, 0.001)
        assertEquals(200.0, Anime4K.renderCapacity(sample(1, 5.0), native)!!, 0.001)
    }

    @Test
    fun `speed changes the render budget without changing measured GPU time`() {
        val fast = native.copy(playbackSpeed = 2.0)
        assertEquals(48.0, Anime4K.effectiveFrameRate(fast))
        assertEquals(100.0, Anime4K.renderCapacity(sample(1), fast))
        assertNull(Anime4K.renderCapacity(sample(1, Double.NaN), fast))
        assertNull(Anime4K.renderCapacity(sample(1, 0.0), fast))
        assertNull(Anime4K.renderCapacity(sample(1, null), fast))
    }

    @Test
    fun `decoder and AV sync problems alone do not degrade shaders`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..30) {
            controller.onSample(
                sample(second, null).copy(
                    decoderDroppedFrames = second * 5,
                    mistimedFrames = second * 5,
                    delayedFrames =
                    second * 5,
                ),
            )
        }
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertFalse(controller.hasStableMeasurement)
    }

    @Test
    fun `excess source cadence is not mistaken for GPU output drops`() {
        val controller = Anime4KSmartController(native.copy(framesPerSecond = 120.0), 0)
        for (second in 1L..30) controller.onSample(sample(second, drops = second * 60))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertEquals(0.0, controller.diagnostics.outputDropRate)
        assertTrue(controller.hasStableMeasurement)
    }

    @Test
    fun `actual output drops still trigger fallback when GPU timers are unavailable`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..7) controller.onSample(sample(second, null, second * 3))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
    }

    @Test
    fun `healthy GPU timings do not blame shaders for unrelated output drops`() {
        val controller = Anime4KSmartController(native.copy(frameRateKnown = false), 0)
        for (second in 1L..20) controller.onSample(sample(second, 5.0, second * 10))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertFalse(controller.hasStableMeasurement)
    }

    @Test
    fun `secondary reentry waits for resize jitter to settle`() {
        val doubled = native.copy(targetWidth = 3840, targetHeight = 2160)
        val narrow = doubled.copy(targetWidth = 3839)
        val controller = Anime4KSmartController(doubled, 0)
        assertEquals(Anime4KMode.ModeAHq, controller.updateMediaInfo(narrow, 100)?.mode)
        assertNull(controller.updateMediaInfo(doubled, 200))
        assertNull(controller.onSample(sample(1)))
        controller.updateMediaInfo(narrow, 1100)
        controller.updateMediaInfo(doubled, 1200)
        assertNull(controller.onSample(sample(2)))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertEquals(Anime4KMode.ModeAPlusHq, controller.onSample(sample(3))?.mode)
    }

    @Test
    fun `decoder reconfiguration invalidates calibration but keeps failed modes`() {
        val controller = Anime4KSmartController(native, 0)
        controller.onShaderError(500, "Anime4K_Restore_CNN_VL.glsl")
        for (second in 1L..8) controller.onSample(sample(second))
        assertTrue(controller.hasStableMeasurement)
        controller.invalidateCalibration(9000)
        assertFalse(controller.hasStableMeasurement)
        controller.resume(10000)
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
    }

    @Test
    fun `paused and buffering windows cannot downgrade or confirm quality`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..20) controller.onSample(sample(second, 200.0, second * 20).copy(playing = false))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertEquals(Anime4KHealth.Unknown, controller.diagnostics.health)
        assertFalse(controller.hasStableMeasurement)
        for (second in 21L..27) controller.onSample(sample(second, drops = 400))
        assertTrue(controller.hasStableMeasurement)
    }

    @Test
    fun `counter resets discard the entire partial window`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..5) controller.onSample(sample(second, drops = 100))
        controller.onSample(sample(6, drops = 0))
        controller.onSample(sample(7, drops = 0))
        assertEquals(Anime4KHealth.Unknown, controller.diagnostics.health)
        assertFalse(controller.hasStableMeasurement)
        for (second in 8L..13) controller.onSample(sample(second))
        assertTrue(controller.hasStableMeasurement)
    }

    @Test
    fun `long sampling gaps never count as healthy rendered frames`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..4) controller.onSample(sample(second))
        controller.onSample(sample(100, 200.0, 300))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertEquals(Anime4KHealth.Unknown, controller.diagnostics.health)
        assertFalse(controller.hasStableMeasurement)
    }

    @Test
    fun `seek warmup excludes old counters and shader compilation costs`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..4) controller.onSample(sample(second, 200.0))
        controller.resetTelemetry(4500)
        controller.onSample(sample(5, 500.0, 200))
        controller.onSample(sample(6, 500.0, 200))
        for (second in 7L..12) controller.onSample(sample(second, drops = 200))
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertTrue(controller.hasStableMeasurement)
    }

    @Test
    fun `a single timing snapshot cannot calibrate a whole window`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..7) controller.onSample(sample(second, if (second == 4L) 10.0 else null))
        assertFalse(controller.hasStableMeasurement)
        assertNull(controller.diagnostics.renderTimeMillis)
    }

    @Test
    fun `calibration is written only after confirming a healthy window`() {
        val controller = Anime4KSmartController(native, 0)
        assertFalse(controller.hasStableMeasurement)
        for (second in 1L..7) controller.onSample(sample(second))
        assertTrue(controller.hasStableMeasurement)
        assertEquals(1, controller.calibration.successfulProbes)
        assertEquals(7000L, controller.calibration.updatedAtMillis)
        for (second in 8L..12) controller.onSample(sample(second))
        assertEquals(1, controller.calibration.successfulProbes)
        controller.onShaderError(13000)
        assertFalse(controller.hasStableMeasurement)
    }

    @Test
    fun `refresh jitter preserves telemetry and failed performance decisions`() {
        val controller = Anime4KSmartController(native, 0)
        for (second in 1L..7) controller.onSample(sample(second, 100.0))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        for (second in 8L..20) {
            controller.updateMediaInfo(native.copy(framesPerSecond = 23.976, displayRefreshRate = 59.94), second * 1000)
            controller.onSample(sample(second))
        }
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        assertTrue(controller.hasStableMeasurement)
        controller.updateMediaInfo(native.copy(playbackSpeed = 2.0), 21000)
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
    }

    @Test
    fun `resolution changes replace the source family even with equal complexity`() {
        val controller = Anime4KSmartController(native.copy(targetWidth = 3840, targetHeight = 2160), 0)
        val changed = controller.updateMediaInfo(native.copy(sourceWidth = 1280, sourceHeight = 720), 1000)
        assertEquals(Anime4KMode.ModeBHq, changed?.mode)
    }

    @Test
    fun `shared shader failure skips every dependent preset`() {
        val controller = Anime4KSmartController(native, 0)
        assertEquals(Anime4KMode.Off, controller.onShaderError(1000, "Anime4K_Clamp_Highlights.glsl")?.mode)
        assertTrue(controller.isSuspended)
        controller.resume(2000)
        assertEquals(Anime4KMode.Off, controller.currentMode)
        assertTrue(controller.isSuspended)
    }

    @Test
    fun `a delayed error from a replaced preset does not punish the current one`() {
        val controller = Anime4KSmartController(native, 0)
        assertEquals(Anime4KMode.ModeA, controller.onShaderError(1000, "Anime4K_Restore_CNN_VL.glsl")?.mode)
        assertNull(controller.onShaderError(1100, "Anime4K_Restore_CNN_VL.glsl"))
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
        controller.updateMediaInfo(native.copy(playbackSpeed = 2.0), 1200)
        assertEquals(Anime4KMode.ModeA, controller.currentMode)
    }

    @Test
    fun `synchronous failures terminate at off with consistent diagnostics`() {
        val controller = Anime4KSmartController(native.copy(targetWidth = 3840, targetHeight = 2160), 0)
        repeat(3) { assertNotNull(controller.onModeApplyFailure(it * 100L)) }
        assertNull(controller.onModeApplyFailure(400))
        assertEquals(Anime4KMode.Off, controller.diagnostics.mode)
        assertTrue(controller.diagnostics.suspended)
        assertFalse(controller.hasStableMeasurement)
    }

    @Test
    fun `forced suspension is reflected by the controller and can be reactivated`() {
        val controller = Anime4KSmartController(native, 0)
        controller.suspend(1000)
        assertEquals(Anime4KMode.Off, controller.diagnostics.mode)
        assertTrue(controller.diagnostics.suspended)
        controller.resume(2000)
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertFalse(controller.isSuspended)
    }

    @Test
    fun `successful compilation messages are not errors`() {
        assertFalse(Anime4K.isShaderError("Shader Anime4K_Restore_CNN_VL.glsl compiled successfully"))
        assertTrue(Anime4K.isShaderError("Shader Anime4K_Restore_CNN_VL.glsl compilation failed"))
    }

    @Test
    fun `secondary pass requires a complete twofold scale on both axes`() {
        assertFalse(Anime4K.supportsSecondaryPass(native.copy(targetWidth = 3839, targetHeight = 2160)))
        assertTrue(Anime4K.supportsSecondaryPass(native.copy(targetWidth = 3840, targetHeight = 2160)))
        assertFalse(Anime4K.supportsSecondaryPass(native.copy(sourceWidth = 0)))
    }

    @Test
    fun `letterbox margins and cropped zoom determine the rendered area`() {
        assertEquals(
            Anime4KGeometry(1920, 1080, 1920, 1080),
            Anime4KGeometry.resolve(
                1920,
                1080,
                osdWidth = 2400,
                osdHeight = 1080,
                marginLeft = 240,
                marginRight = 240,
                marginTop = 0,
                marginBottom = 0,
            ),
        )
        assertEquals(
            Anime4KGeometry(1920, 1080, 2400, 1350),
            Anime4KGeometry.resolve(
                1920,
                1080,
                osdWidth = 1920,
                osdHeight = 1080,
                marginLeft = -240,
                marginRight = -240,
                marginTop = -135,
                marginBottom = -135,
            ),
        )
    }

    @Test
    fun `rotation and portrait videos keep aligned axes`() {
        assertEquals(
            Anime4KGeometry(1080, 1920, 1080, 1920),
            Anime4KGeometry.resolve(1920, 1080, 90, surfaceWidth = 1080, surfaceHeight = 2400),
        )
        assertEquals(0.5625, Anime4K.upscaleScale(native.copy(sourceWidth = 1080, sourceHeight = 1920)))
        assertEquals(
            Anime4KGeometry(1080, 1920, 1080, 1920),
            Anime4KGeometry.resolve(1920, 1080, -90, surfaceWidth = 1080, surfaceHeight = 1920),
        )
    }

    @Test
    fun `missing surface waits while PiP and anamorphic inputs fit their actual area`() {
        assertNull(Anime4KGeometry.resolve(1920, 1080))
        assertEquals(
            Anime4KGeometry(1920, 1080, 480, 270),
            Anime4KGeometry.resolve(1920, 1080, surfaceWidth = 480, surfaceHeight = 320),
        )
        assertEquals(
            Anime4KGeometry(720, 576, 1440, 1080),
            Anime4KGeometry.resolve(720, 576, displayAspect = 4.0 / 3.0, surfaceWidth = 1920, surfaceHeight = 1080),
        )
    }
}
