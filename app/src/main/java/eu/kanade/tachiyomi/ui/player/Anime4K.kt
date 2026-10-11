package eu.kanade.tachiyomi.ui.player

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.aniyomi.AYMR
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

enum class Anime4KMode(
    val titleRes: StringResource,
    val shaderFileNames: List<String>,
) {
    Off(
        titleRes = AYMR.strings.pref_anime4k_off,
        shaderFileNames = emptyList(),
    ),
    ModeA(
        titleRes = AYMR.strings.pref_anime4k_mode_a,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        ),
    ),
    ModeB(
        titleRes = AYMR.strings.pref_anime4k_mode_b,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_Soft_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        ),
    ),
    ModeC(
        titleRes = AYMR.strings.pref_anime4k_mode_c,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        ),
    ),
    ModeAPlus(
        titleRes = AYMR.strings.pref_anime4k_mode_a_plus,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        ),
    ),
    ModeBPlus(
        titleRes = AYMR.strings.pref_anime4k_mode_b_plus,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_Soft_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Restore_CNN_Soft_S.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        ),
    ),
    ModeCPlusA(
        titleRes = AYMR.strings.pref_anime4k_mode_c_plus_a,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        ),
    ),
    ModeAHq(
        titleRes = AYMR.strings.pref_anime4k_mode_a_hq,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_VL.glsl",
            "Anime4K_Upscale_CNN_x2_VL.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
        ),
    ),
    ModeBHq(
        titleRes = AYMR.strings.pref_anime4k_mode_b_hq,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_Soft_VL.glsl",
            "Anime4K_Upscale_CNN_x2_VL.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
        ),
    ),
    ModeCHq(
        titleRes = AYMR.strings.pref_anime4k_mode_c_hq,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
        ),
    ),
    ModeAPlusHq(
        titleRes = AYMR.strings.pref_anime4k_mode_a_plus_hq,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_VL.glsl",
            "Anime4K_Upscale_CNN_x2_VL.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
        ),
    ),
    ModeBPlusHq(
        titleRes = AYMR.strings.pref_anime4k_mode_b_plus_hq,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_Soft_VL.glsl",
            "Anime4K_Upscale_CNN_x2_VL.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Restore_CNN_Soft_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
        ),
    ),
    ModeCPlusAHq(
        titleRes = AYMR.strings.pref_anime4k_mode_c_plus_a_hq,
        shaderFileNames = listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
        ),
    ),
}

enum class Anime4KProfile {
    Off,
    Smart,
    Maximum,
    Custom,
}

data class Anime4KEpisodeProfile(
    val profile: Anime4KProfile,
    val customMode: Anime4KMode = Anime4KMode.Off,
)

data class Anime4KMediaInfo(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int,
    val targetHeight: Int,
    val framesPerSecond: Double,
    val displayRefreshRate: Double? = null,
    val frameRateKnown: Boolean = true,
    val playbackSpeed: Double = 1.0,
)

data class Anime4KPerformanceSample(
    val timestampMillis: Long,
    val outputDroppedFrames: Long?,
    val decoderDroppedFrames: Long?,
    val delayedFrames: Long?,
    val renderTimeMillis: Double?,
    val mistimedFrames: Long? = null,
    val redrawTimeMillis: Double? = null,
    val playing: Boolean = true,
)

enum class Anime4KHealth {
    Unknown,
    Healthy,
    Borderline,
    Overloaded,
    Severe,
}

data class Anime4KSmartDiagnostics(
    val profile: Anime4KProfile = Anime4KProfile.Off,
    val mode: Anime4KMode = Anime4KMode.Off,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val targetWidth: Int? = null,
    val targetHeight: Int? = null,
    val targetFramesPerSecond: Double? = null,
    val renderCapacityFramesPerSecond: Double? = null,
    val renderTimeMillis: Double? = null,
    val outputDropRate: Double? = null,
    val decoderDropRate: Double? = null,
    val delayedFrameRate: Double? = null,
    val mistimedFrameRate: Double? = null,
    val headroom: Double? = null,
    val confidence: Double = 0.0,
    val health: Anime4KHealth = Anime4KHealth.Unknown,
    val probing: Boolean = false,
    val suspended: Boolean = false,
    val reason: String? = null,
)

data class Anime4KCalibration(
    val maxStableMode: Anime4KMode,
    val successfulProbes: Int,
    val updatedAtMillis: Long,
)

data class Anime4KSmartAdjustment(
    val mode: Anime4KMode,
    val reason: String,
)

data class Anime4KSelection(
    val profile: Anime4KProfile,
    val mode: Anime4KMode,
)

object Anime4KSelectionRules {
    private val off = Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off)

    fun toggleSmart(current: Anime4KSelection, resolvedMode: Anime4KMode): Anime4KSelection {
        return if (current.profile == Anime4KProfile.Smart) {
            off
        } else {
            Anime4KSelection(Anime4KProfile.Smart, resolvedMode)
        }
    }

    fun toggleMaximum(current: Anime4KSelection): Anime4KSelection {
        return if (current.profile == Anime4KProfile.Maximum) {
            off
        } else {
            Anime4KSelection(Anime4KProfile.Maximum, Anime4KMode.ModeAPlusHq)
        }
    }

    fun selectCustom(current: Anime4KSelection, requestedMode: Anime4KMode): Anime4KSelection {
        return if (
            requestedMode == Anime4KMode.Off ||
            (current.profile == Anime4KProfile.Custom && current.mode == requestedMode)
        ) {
            off
        } else {
            Anime4KSelection(Anime4KProfile.Custom, requestedMode)
        }
    }
}

object Anime4K {
    const val VERSION = "4.0.1"
    const val SHADER_REVISION = "4.0.1"
    const val CALIBRATION_REVISION = "gpu-telemetry-3"

    /** Anime4K documents secondary passes for an upscale ratio of roughly 2x or higher. */
    private const val SECONDARY_PASS_MIN_SCALE = 2.0

    internal val bundledShaderNames: List<String> = Anime4KMode.entries
        .flatMap { it.shaderFileNames }
        .distinct()

    private val secondaryModes = setOf(
        Anime4KMode.ModeAPlus,
        Anime4KMode.ModeAPlusHq,
        Anime4KMode.ModeBPlus,
        Anime4KMode.ModeBPlusHq,
        Anime4KMode.ModeCPlusA,
        Anime4KMode.ModeCPlusAHq,
    )

    /**
     * MPV reports shader compilation failures asynchronously through its log observer.
     * Restrict matching to the bundled shader names so a broken user shader does not
     * silently change the Anime4K preference.
     */
    fun isShaderError(message: String): Boolean {
        val lowerCaseMessage = message.lowercase()
        val failureMarker = listOf(
            "error",
            "fail",
            "invalid",
            "not found",
            "unable",
        ).any(lowerCaseMessage::contains)
        return failureMarker &&
            lowerCaseMessage.contains("shader") &&
            shaderNameInError(message) != null
    }

    fun shaderNameInError(message: String): String? {
        val lowerCaseMessage = message.lowercase()
        return bundledShaderNames.firstOrNull { lowerCaseMessage.contains(it.lowercase()) }
    }

    /** Chooses the least expensive useful preset for this source family. */
    fun resolveSmartMode(mediaInfo: Anime4KMediaInfo): Anime4KMode {
        val sourceShortSide = min(mediaInfo.sourceWidth, mediaInfo.sourceHeight).coerceAtLeast(1)
        return when {
            sourceShortSide >= 900 -> Anime4KMode.ModeA
            sourceShortSide >= 600 -> Anime4KMode.ModeB
            else -> Anime4KMode.ModeC
        }
    }

    fun maxSmartMode(mediaInfo: Anime4KMediaInfo): Anime4KMode {
        val primary = resolveSmartMode(mediaInfo)
        return when (primary) {
            Anime4KMode.ModeA -> if (supportsSecondaryPass(mediaInfo)) {
                Anime4KMode.ModeAPlusHq
            } else {
                Anime4KMode.ModeAHq
            }
            Anime4KMode.ModeB -> if (supportsSecondaryPass(mediaInfo)) {
                Anime4KMode.ModeBPlusHq
            } else {
                Anime4KMode.ModeBHq
            }
            Anime4KMode.ModeC -> if (supportsSecondaryPass(mediaInfo)) {
                Anime4KMode.ModeCPlusAHq
            } else {
                Anime4KMode.ModeCHq
            }
            else -> primary
        }
    }

    /** Returns the effective upscale ratio, taking the limiting axis and invalid dimensions into account. */
    fun upscaleScale(mediaInfo: Anime4KMediaInfo): Double {
        if (minOf(mediaInfo.sourceWidth, mediaInfo.sourceHeight, mediaInfo.targetWidth, mediaInfo.targetHeight) <=
            0
        ) {
            return 0.0
        }
        return min(
            mediaInfo.targetWidth.toDouble() / mediaInfo.sourceWidth,
            mediaInfo.targetHeight.toDouble() / mediaInfo.sourceHeight,
        )
    }

    fun supportsSecondaryPass(mediaInfo: Anime4KMediaInfo): Boolean {
        return mediaInfo.frameRateKnown &&
            upscaleScale(mediaInfo) >= SECONDARY_PASS_MIN_SCALE &&
            effectiveFrameRate(mediaInfo) in 1.0..31.0
    }

    /**
     * The real rendering budget is limited by both playback speed and the display refresh rate.
     * A 120 fps file on a 60 Hz screen does not need a 120 fps shader budget, while 2x playback
     * does need twice the normal filter throughput.
     */
    fun effectiveFrameRate(mediaInfo: Anime4KMediaInfo): Double {
        val playbackFps = playbackFrameRate(mediaInfo)
        val displayFps = mediaInfo.displayRefreshRate
            ?.takeIf { it.isFinite() && it > 0.0 }
        return min(playbackFps, displayFps ?: playbackFps)
    }

    fun playbackFrameRate(mediaInfo: Anime4KMediaInfo): Double {
        val sourceFps = mediaInfo.framesPerSecond
            .takeIf { it.isFinite() && it > 0.0 }
            ?: 24.0
        val playbackSpeed = mediaInfo.playbackSpeed
            .takeIf { it.isFinite() && it > 0.0 }
            ?: 1.0
        val playbackFps = (sourceFps * playbackSpeed)
            .takeIf { it.isFinite() && it > 0.0 }
            ?: sourceFps
        return playbackFps
    }

    /** vo-passes costs are nanoseconds converted by the Lua adapter; cadence is not GPU capacity. */
    fun renderCapacity(sample: Anime4KPerformanceSample, mediaInfo: Anime4KMediaInfo): Double? {
        val fresh = sample.renderTimeMillis?.takeIf { it.isFinite() && it > 0.0 && it < 10_000.0 }
            ?: return null
        val targetFps = effectiveFrameRate(mediaInfo)
        val refresh = mediaInfo.displayRefreshRate?.takeIf { it.isFinite() && it > 0.0 } ?: targetFps
        val redraw = sample.redrawTimeMillis?.takeIf { it.isFinite() && it >= 0.0 && it < 10_000.0 } ?: 0.0
        // Repeated presentations can still cost GPU time on high-refresh displays.
        val cost = fresh + redraw * ((refresh / targetFps) - 1.0).coerceAtLeast(0.0)
        return (1000.0 / cost).takeIf { it.isFinite() && it > 0.0 }
    }

    /** Small refresh-rate jitter must not reset the governor or invalidate a measured window. */
    fun sameWorkload(first: Anime4KMediaInfo, second: Anime4KMediaInfo): Boolean {
        fun close(a: Double, b: Double): Boolean = abs(a - b) <= maxOf(abs(a), abs(b), 1.0) * 0.02
        return first.sourceWidth == second.sourceWidth &&
            first.sourceHeight == second.sourceHeight &&
            close(first.targetWidth.toDouble(), second.targetWidth.toDouble()) &&
            close(first.targetHeight.toDouble(), second.targetHeight.toDouble()) &&
            first.frameRateKnown == second.frameRateKnown &&
            close(playbackFrameRate(first), playbackFrameRate(second)) &&
            close(effectiveFrameRate(first), effectiveFrameRate(second)) &&
            close(first.displayRefreshRate ?: 0.0, second.displayRefreshRate ?: 0.0) &&
            supportsSecondaryPass(first) == supportsSecondaryPass(second)
    }

    fun resolveFramesPerSecond(estimated: Double?, container: Double?): Double {
        return estimated?.takeIf { it.isFinite() && it > 0.0 }
            ?: container?.takeIf { it.isFinite() && it > 0.0 }
            ?: 24.0
    }

    /**
     * Builds a stable, device-agnostic calibration key. Runtime capabilities are used as a
     * cache key only; no device model is ever used to select a preset.
     */
    fun calibrationKey(
        mediaInfo: Anime4KMediaInfo,
        renderer: String?,
        api: String?,
        driver: String?,
        hwdec: String?,
        calibrationRevision: String = CALIBRATION_REVISION,
    ): String {
        fun clean(value: String?): String = value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.replace('|', '_')
            ?: "unknown"

        fun bucket(value: Int): Int = (value.coerceAtLeast(1) / 16) * 16
        fun refreshBucket(value: Double?): Int = round(
            (value ?: 0.0).coerceAtLeast(0.0) * 2.0,
        ).toInt()
        fun fpsBucket(value: Double): Int = round(value.coerceIn(1.0, 240.0) * 2.0).toInt()

        return listOf(
            clean(calibrationRevision),
            SHADER_REVISION,
            clean(renderer),
            clean(api),
            clean(driver),
            clean(hwdec),
            bucket(mediaInfo.sourceWidth),
            bucket(mediaInfo.sourceHeight),
            bucket(mediaInfo.targetWidth),
            bucket(mediaInfo.targetHeight),
            refreshBucket(mediaInfo.displayRefreshRate),
            fpsBucket(effectiveFrameRate(mediaInfo)),
        ).joinToString("|")
    }

    fun complexityRank(mode: Anime4KMode): Int = when (mode) {
        Anime4KMode.Off -> 0
        Anime4KMode.ModeA,
        Anime4KMode.ModeB,
        Anime4KMode.ModeC,
        -> 1
        Anime4KMode.ModeAHq,
        Anime4KMode.ModeBHq,
        Anime4KMode.ModeCHq,
        Anime4KMode.ModeAPlus,
        Anime4KMode.ModeBPlus,
        Anime4KMode.ModeCPlusA,
        -> 2
        Anime4KMode.ModeAPlusHq,
        Anime4KMode.ModeBPlusHq,
        Anime4KMode.ModeCPlusAHq,
        -> 3
    }

    fun smartButtonLabel(mode: Anime4KMode): String = when (mode) {
        Anime4KMode.Off -> "SM"
        Anime4KMode.ModeA -> "A"
        Anime4KMode.ModeAPlus -> "A+"
        Anime4KMode.ModeB -> "B"
        Anime4KMode.ModeBPlus -> "B+"
        Anime4KMode.ModeC -> "C"
        Anime4KMode.ModeCPlusA -> "C+A"
        Anime4KMode.ModeAHq -> "A HQ"
        Anime4KMode.ModeAPlusHq -> "A+ HQ"
        Anime4KMode.ModeBHq -> "B HQ"
        Anime4KMode.ModeBPlusHq -> "B+ HQ"
        Anime4KMode.ModeCHq -> "C HQ"
        Anime4KMode.ModeCPlusAHq -> "C+A HQ"
    }

    /** Removes one quality/load step while preserving the source family and HQ state when possible. */
    fun lessDemandingMode(mode: Anime4KMode): Anime4KMode = when (mode) {
        Anime4KMode.ModeAPlusHq -> Anime4KMode.ModeAPlus
        Anime4KMode.ModeBPlusHq -> Anime4KMode.ModeBPlus
        Anime4KMode.ModeCPlusAHq -> Anime4KMode.ModeCPlusA
        Anime4KMode.ModeAHq -> Anime4KMode.ModeA
        Anime4KMode.ModeBHq -> Anime4KMode.ModeB
        Anime4KMode.ModeCHq -> Anime4KMode.ModeC
        Anime4KMode.ModeAPlus -> Anime4KMode.ModeA
        Anime4KMode.ModeBPlus -> Anime4KMode.ModeB
        Anime4KMode.ModeCPlusA -> Anime4KMode.ModeC
        Anime4KMode.ModeA,
        Anime4KMode.ModeB,
        Anime4KMode.ModeC,
        -> Anime4KMode.Off
        Anime4KMode.Off -> Anime4KMode.Off
    }

    /** Adds one quality/load step, never enabling a secondary pass below the safe scale. */
    fun moreDemandingMode(mode: Anime4KMode, mediaInfo: Anime4KMediaInfo): Anime4KMode? {
        val secondaryAllowed = supportsSecondaryPass(mediaInfo)
        return when (mode) {
            Anime4KMode.Off -> when {
                min(mediaInfo.sourceWidth, mediaInfo.sourceHeight) >= 900 -> Anime4KMode.ModeA
                min(mediaInfo.sourceWidth, mediaInfo.sourceHeight) >= 600 -> Anime4KMode.ModeB
                else -> Anime4KMode.ModeC
            }
            Anime4KMode.ModeA -> if (secondaryAllowed) Anime4KMode.ModeAPlus else Anime4KMode.ModeAHq
            Anime4KMode.ModeAPlus -> Anime4KMode.ModeAPlusHq
            Anime4KMode.ModeAHq -> if (secondaryAllowed) Anime4KMode.ModeAPlusHq else null
            Anime4KMode.ModeAPlusHq -> null
            Anime4KMode.ModeB -> if (secondaryAllowed) Anime4KMode.ModeBPlus else Anime4KMode.ModeBHq
            Anime4KMode.ModeBPlus -> Anime4KMode.ModeBPlusHq
            Anime4KMode.ModeBHq -> if (secondaryAllowed) Anime4KMode.ModeBPlusHq else null
            Anime4KMode.ModeBPlusHq -> null
            Anime4KMode.ModeC -> if (secondaryAllowed) Anime4KMode.ModeCPlusA else Anime4KMode.ModeCHq
            Anime4KMode.ModeCPlusA -> Anime4KMode.ModeCPlusAHq
            Anime4KMode.ModeCHq -> if (secondaryAllowed) Anime4KMode.ModeCPlusAHq else null
            Anime4KMode.ModeCPlusAHq -> null
        }
    }

    /** Converts a secondary mode to its corresponding primary mode without losing HQ quality. */
    fun primaryVariant(mode: Anime4KMode): Anime4KMode = when (mode) {
        Anime4KMode.ModeAPlus -> Anime4KMode.ModeA
        Anime4KMode.ModeAPlusHq -> Anime4KMode.ModeAHq
        Anime4KMode.ModeBPlus -> Anime4KMode.ModeB
        Anime4KMode.ModeBPlusHq -> Anime4KMode.ModeBHq
        Anime4KMode.ModeCPlusA -> Anime4KMode.ModeC
        Anime4KMode.ModeCPlusAHq -> Anime4KMode.ModeCHq
        else -> mode
    }

    fun isSecondaryMode(mode: Anime4KMode): Boolean = mode in secondaryModes
}
