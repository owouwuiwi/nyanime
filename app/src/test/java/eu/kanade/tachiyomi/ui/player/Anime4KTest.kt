package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Anime4KTest {

    @Test
    fun `smart uses a single fast pass for native 1080p`() {
        val mode = Anime4K.resolveSmartMode(
            Anime4KMediaInfo(
                sourceWidth = 1920,
                sourceHeight = 1080,
                targetWidth = 2400,
                targetHeight = 1080,
                framesPerSecond = 24.0,
            ),
        )

        assertEquals(Anime4KMode.ModeA, mode)
    }

    @Test
    fun `smart avoids a secondary pass below the documented x2 upscale`() {
        val mode = Anime4K.resolveSmartMode(
            Anime4KMediaInfo(
                sourceWidth = 1280,
                sourceHeight = 720,
                targetWidth = 1920,
                targetHeight = 1080,
                framesPerSecond = 24.0,
            ),
        )

        assertEquals(Anime4KMode.ModeB, mode)
    }

    @Test
    fun `smart starts conservatively at x2 upscale`() {
        val mode = Anime4K.resolveSmartMode(
            Anime4KMediaInfo(
                sourceWidth = 1920,
                sourceHeight = 1080,
                targetWidth = 3840,
                targetHeight = 2160,
                framesPerSecond = 24.0,
            ),
        )

        assertEquals(Anime4KMode.ModeA, mode)
    }

    @Test
    fun `smart stays on the primary pass when source FPS is unknown`() {
        val mode = Anime4K.resolveSmartMode(
            Anime4KMediaInfo(
                sourceWidth = 1920,
                sourceHeight = 1080,
                targetWidth = 3840,
                targetHeight = 2160,
                framesPerSecond = 24.0,
                frameRateKnown = false,
            ),
        )

        assertEquals(Anime4KMode.ModeA, mode)
    }

    @Test
    fun `smart avoids a double pass for high frame rate video`() {
        val mode = Anime4K.resolveSmartMode(
            Anime4KMediaInfo(
                sourceWidth = 854,
                sourceHeight = 480,
                targetWidth = 1920,
                targetHeight = 1080,
                framesPerSecond = 60.0,
            ),
        )

        assertEquals(Anime4KMode.ModeC, mode)
    }

    @Test
    fun `smart accounts for playback speed when selecting a secondary pass`() {
        val mode = Anime4K.resolveSmartMode(
            Anime4KMediaInfo(
                sourceWidth = 1920,
                sourceHeight = 1080,
                targetWidth = 3840,
                targetHeight = 2160,
                framesPerSecond = 24.0,
                playbackSpeed = 2.0,
            ),
        )

        assertEquals(Anime4KMode.ModeA, mode)
    }

    @Test
    fun `smart uses the display refresh rate as the rendering budget`() {
        val mediaInfo = Anime4KMediaInfo(
            sourceWidth = 1920,
            sourceHeight = 1080,
            targetWidth = 3840,
            targetHeight = 2160,
            framesPerSecond = 120.0,
            displayRefreshRate = 60.0,
        )

        assertEquals(60.0, Anime4K.effectiveFrameRate(mediaInfo))
        assertEquals(false, Anime4K.supportsSecondaryPass(mediaInfo))
    }

    @Test
    fun `smart permits secondary pass at 24 and 30 fps on 60 and 90 hz`() {
        assertEquals(
            true,
            Anime4K.supportsSecondaryPass(
                upscaledMediaInfo().copy(framesPerSecond = 24.0, displayRefreshRate = 60.0),
            ),
        )
        assertEquals(
            true,
            Anime4K.supportsSecondaryPass(
                upscaledMediaInfo().copy(framesPerSecond = 30.0, displayRefreshRate = 90.0),
            ),
        )
        assertEquals(
            false,
            Anime4K.supportsSecondaryPass(
                upscaledMediaInfo().copy(framesPerSecond = 60.0, displayRefreshRate = 120.0),
            ),
        )
    }

    @Test
    fun `smart falls back safely when mpv fps properties are unavailable`() {
        assertEquals(24.0, Anime4K.resolveFramesPerSecond(null, null))
        assertEquals(23.976, Anime4K.resolveFramesPerSecond(null, 23.976))
        assertEquals(60.0, Anime4K.resolveFramesPerSecond(60.0, 24.0))
    }

    @Test
    fun `smart button reports the effective preset`() {
        assertEquals("SM", Anime4K.smartButtonLabel(Anime4KMode.Off))
        assertEquals("A", Anime4K.smartButtonLabel(Anime4KMode.ModeA))
        assertEquals("A+", Anime4K.smartButtonLabel(Anime4KMode.ModeAPlus))
        assertEquals("B", Anime4K.smartButtonLabel(Anime4KMode.ModeB))
        assertEquals("B+", Anime4K.smartButtonLabel(Anime4KMode.ModeBPlus))
        assertEquals("C", Anime4K.smartButtonLabel(Anime4KMode.ModeC))
        assertEquals("C+A", Anime4K.smartButtonLabel(Anime4KMode.ModeCPlusA))
    }

    @Test
    fun `shader errors identify only bundled Anime4K shaders`() {
        assertEquals(
            "Anime4K_Restore_CNN_VL.glsl",
            Anime4K.shaderNameInError("failed to compile Anime4K_Restore_CNN_VL.glsl"),
        )
        assertEquals(null, Anime4K.shaderNameInError("failed to compile custom_shader.glsl"))
    }

    @Test
    fun `last click wins and clicking the active profile disables it`() {
        val smart = Anime4KSelectionRules.toggleSmart(
            Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off),
            Anime4KMode.ModeB,
        )
        val maximum = Anime4KSelectionRules.toggleMaximum(smart)
        val custom = Anime4KSelectionRules.selectCustom(maximum, Anime4KMode.ModeC)
        val disabled = Anime4KSelectionRules.selectCustom(custom, Anime4KMode.ModeC)

        assertEquals(Anime4KSelection(Anime4KProfile.Smart, Anime4KMode.ModeB), smart)
        assertEquals(Anime4KSelection(Anime4KProfile.Maximum, Anime4KMode.ModeAPlusHq), maximum)
        assertEquals(Anime4KSelection(Anime4KProfile.Custom, Anime4KMode.ModeC), custom)
        assertEquals(Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off), disabled)
        assertEquals(
            Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off),
            Anime4KSelectionRules.toggleMaximum(maximum),
        )
        assertEquals(
            Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off),
            Anime4KSelectionRules.toggleSmart(smart, Anime4KMode.ModeA),
        )
    }

    @Test
    fun `smart controller starts at the highest compatible preset`() {
        val mediaInfo = Anime4KMediaInfo(
            sourceWidth = 1920,
            sourceHeight = 1080,
            targetWidth = 3840,
            targetHeight = 2160,
            framesPerSecond = 24.0,
        )
        val controller = Anime4KSmartController(mediaInfo, initialTimestampMillis = 0L)

        assertEquals(Anime4KMode.ModeAPlusHq, controller.currentMode)
        assertEquals(false, controller.isSuspended)
    }

    @Test
    fun `smart controller downgrades from the maximum after overload`() {
        val controller = Anime4KSmartController(upscaledMediaInfo(), initialTimestampMillis = 0L)

        assertEquals(Anime4KMode.ModeAPlusHq, controller.currentMode)
        controller.onSample(healthySample(1L))
        for (second in 2L..7L) {
            controller.onSample(healthySample(second, filterFps = 15.0))
        }

        assertEquals(Anime4KMode.ModeAPlus, controller.currentMode)

        for (second in 8L..30L) controller.onSample(healthySample(second))
        assertEquals(Anime4KMode.ModeAPlus, controller.currentMode)
    }

    @Test
    fun `smart controller suspends at off after severe overload until resume`() {
        val controller = Anime4KSmartController(nativeMediaInfo(), initialTimestampMillis = 0L)

        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        controller.onSample(healthySample(1L))
        for (second in 2L..7L) {
            controller.onSample(healthySample(second, filterFps = 10.0))
        }
        assertEquals(Anime4KMode.ModeA, controller.currentMode)

        for (second in 8L..14L) {
            controller.onSample(healthySample(second, filterFps = 10.0))
        }

        assertEquals(Anime4KMode.Off, controller.currentMode)
        assertEquals(true, controller.isSuspended)

        for (second in 15L..20L) controller.onSample(healthySample(second))
        assertEquals(Anime4KMode.Off, controller.currentMode)

        controller.resume(21_000L)
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertEquals(false, controller.isSuspended)
    }

    @Test
    fun `smart controller does not upgrade without filter telemetry`() {
        val controller = Anime4KSmartController(upscaledMediaInfo(), initialTimestampMillis = 0L)

        for (second in 1L..40L) {
            controller.onSample(healthySample(second, filterFps = null))
        }

        assertEquals(Anime4KMode.ModeAPlusHq, controller.currentMode)
    }

    @Test
    fun `smart diagnostics keep the last evaluated health while collecting a new window`() {
        val controller = Anime4KSmartController(upscaledMediaInfo(), initialTimestampMillis = 0L)

        for (second in 1L..7L) controller.onSample(healthySample(second))
        assertEquals(Anime4KHealth.Healthy, controller.diagnostics.health)

        controller.onSample(healthySample(8L, filterFps = null))

        assertEquals(Anime4KHealth.Healthy, controller.diagnostics.health)
    }

    @Test
    fun `smart controller starts at the maximum despite an older lower calibration`() {
        val controller = Anime4KSmartController(
            initialMediaInfo = upscaledMediaInfo(),
            initialTimestampMillis = 0L,
            initialCalibration = Anime4KCalibration(
                maxStableMode = Anime4KMode.ModeAPlus,
                successfulProbes = 3,
                updatedAtMillis = 1L,
            ),
        )

        assertEquals(Anime4KMode.ModeAPlusHq, controller.currentMode)
    }

    @Test
    fun `smart raises the ceiling when late media metadata enables a secondary pass`() {
        val incompleteMediaInfo = upscaledMediaInfo().copy(frameRateKnown = false)
        val controller = Anime4KSmartController(incompleteMediaInfo, initialTimestampMillis = 0L)

        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        val adjustment = controller.updateMediaInfo(
            upscaledMediaInfo(),
            nowMillis = 2_000L,
        )

        assertEquals(null, adjustment)
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
        assertEquals(Anime4KMode.ModeAPlusHq, controller.onSample(healthySample(4L))?.mode)
        assertEquals(Anime4KMode.ModeAPlusHq, controller.currentMode)
    }

    @Test
    fun `smart reaches the highest compatible single pass when secondary is unavailable`() {
        val mediaInfo = upscaledMediaInfo().copy(
            framesPerSecond = 60.0,
            displayRefreshRate = 120.0,
        )
        val controller = Anime4KSmartController(mediaInfo, initialTimestampMillis = 0L)

        for (second in 1L..30L) {
            controller.onSample(healthySample(second, filterFps = 100.0))
        }

        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
    }

    @Test
    fun `smart leaves a secondary pass when the video no longer supports it`() {
        val controller = Anime4KSmartController(upscaledMediaInfo(), initialTimestampMillis = 0L)

        for (second in 1L..12L) controller.onSample(healthySample(second))
        assertEquals(Anime4KMode.ModeAPlusHq, controller.currentMode)

        val adjustment = controller.updateMediaInfo(
            upscaledMediaInfo().copy(
                targetWidth = 1920,
                targetHeight = 1080,
            ),
            nowMillis = 20_000L,
        )

        assertEquals(Anime4KMode.ModeAHq, adjustment?.mode)
        assertEquals(Anime4KMode.ModeAHq, controller.currentMode)
    }

    @Test
    fun `less demanding keeps HQ as fast before removing the secondary pass`() {
        assertEquals(Anime4KMode.ModeAPlus, Anime4K.lessDemandingMode(Anime4KMode.ModeAPlusHq))
        assertEquals(Anime4KMode.ModeA, Anime4K.lessDemandingMode(Anime4KMode.ModeAPlus))
        assertEquals(Anime4KMode.ModeA, Anime4K.lessDemandingMode(Anime4KMode.ModeAHq))
        assertEquals(Anime4KMode.Off, Anime4K.lessDemandingMode(Anime4KMode.ModeA))
    }

    @Test
    fun `smart does not retry a preset that failed shader compilation`() {
        val controller = Anime4KSmartController(upscaledMediaInfo(), initialTimestampMillis = 0L)

        for (second in 1L..12L) controller.onSample(healthySample(second))
        assertEquals(Anime4KMode.ModeAPlusHq, controller.currentMode)
        controller.onShaderError(nowMillis = 12_000L)
        assertEquals(Anime4KMode.ModeAPlus, controller.currentMode)
        controller.resume(13_000L)

        for (second in 14L..45L) controller.onSample(healthySample(second))

        assertEquals(Anime4KMode.ModeAPlus, controller.currentMode)
    }

    @Test
    fun `maximum reaches HQ without a secondary pass at native resolution`() {
        val mediaInfo = nativeMediaInfo()

        assertEquals(Anime4KMode.ModeAHq, Anime4K.maxSmartMode(mediaInfo))
        assertEquals(false, Anime4K.supportsSecondaryPass(mediaInfo))
    }

    @Test
    fun `calibration key changes with renderer refresh and output`() {
        val base = upscaledMediaInfo()
        val first = Anime4K.calibrationKey(base, "renderer-a", "vulkan", "driver-a", "hwdec")
        val differentRenderer = Anime4K.calibrationKey(base, "renderer-b", "vulkan", "driver-a", "hwdec")
        val differentOutput = Anime4K.calibrationKey(
            base.copy(targetWidth = 2560, targetHeight = 1440, displayRefreshRate = 120.0),
            "renderer-a",
            "vulkan",
            "driver-a",
            "hwdec",
        )

        assertEquals(false, first == differentRenderer)
        assertEquals(false, first == differentOutput)
        assertEquals(
            false,
            first == Anime4K.calibrationKey(
                base,
                "renderer-a",
                "vulkan",
                "driver-a",
                "hwdec",
                calibrationRevision = "ahg-3",
            ),
        )
    }

    private fun upscaledMediaInfo() = Anime4KMediaInfo(
        sourceWidth = 1920,
        sourceHeight = 1080,
        targetWidth = 3840,
        targetHeight = 2160,
        framesPerSecond = 24.0,
    )

    private fun nativeMediaInfo() = Anime4KMediaInfo(
        sourceWidth = 1920,
        sourceHeight = 1080,
        targetWidth = 1920,
        targetHeight = 1080,
        framesPerSecond = 24.0,
    )

    private fun healthySample(
        timestampSeconds: Long,
        outputDroppedFrames: Long = 0L,
        filterFps: Double? = 50.0,
    ) = Anime4KPerformanceSample(
        timestampMillis = timestampSeconds * 1000L,
        outputDroppedFrames = outputDroppedFrames,
        decoderDroppedFrames = 0L,
        delayedFrames = 0L,
        renderTimeMillis = filterFps?.let { 1000.0 / it },
        mistimedFrames = 0L,
    )
}
