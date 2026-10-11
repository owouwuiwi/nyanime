package eu.kanade.tachiyomi.ui.player

/**
 * Session-local Adaptive Headroom Governor.
 *
 * Smart starts at the highest preset compatible with the media pipeline and only steps down when
 * complete telemetry windows show that playback cannot keep up. Missing telemetry is treated as
 * unknown, never as a reason to oscillate or to silently reduce quality.
 */
class Anime4KSmartController(
    initialMediaInfo: Anime4KMediaInfo,
    initialTimestampMillis: Long,
    initialCalibration: Anime4KCalibration? = null,
) {
    companion object {
        private const val MIN_SAMPLE_INTERVAL_MILLIS = 750L
        private const val MAX_SAMPLE_GAP_MILLIS = 2_500L
        private const val WARMUP_MILLIS = 2_000L
        private const val WINDOW_MILLIS = 5_000L
        private const val DOWNGRADE_COOLDOWN_MILLIS = 1_500L
        private const val CAPABILITY_SETTLE_MILLIS = 1_500L
        private const val OVERLOADED_WINDOWS_FOR_DOWNGRADE = 2

        private const val HEALTHY_HEADROOM = 1.20
        private const val SEVERE_HEADROOM = 0.85
        private const val OUTPUT_DROP_OVERLOAD = 0.01
        private const val DELAYED_FRAME_OVERLOAD = 0.02
        private const val OUTPUT_DROP_HEALTHY = 0.005
        private const val DECODER_DROP_HEALTHY = 0.001
        private const val DELAYED_FRAME_HEALTHY = 0.01
        private const val MISTIMED_FRAME_HEALTHY = 0.02
    }

    private data class PerformanceWindow(
        val elapsedSeconds: Double,
        val expectedFrames: Double,
        val outputDrops: Long?,
        val decoderDrops: Long?,
        val delayedFrames: Long?,
        val mistimedFrames: Long?,
        val renderCapacity: Double?,
    )

    private var mediaInfo = initialMediaInfo
    private var measuredWorkload = initialMediaInfo
    private var lastSample: Anime4KPerformanceSample? = null
    private var windowStartSample: Anime4KPerformanceSample? = null
    private val renderCapacitySamples = mutableListOf<Double>()
    private var lastTransitionMillis = initialTimestampMillis
    private var warmupUntilMillis = initialTimestampMillis + WARMUP_MILLIS
    private var overloadedWindows = 0
    private var hasEvaluatedWindow = false
    private var lastReason: String? = "maximum compatible start"
    private var successfulProbes = initialCalibration?.successfulProbes ?: 0
    private val shaderFailedModes = mutableSetOf<Anime4KMode>()
    private val performanceBlockedModes = mutableSetOf<Anime4KMode>()
    private var pendingCapabilityMode: Anime4KMode? = null
    private var pendingCapabilitySinceMillis = 0L
    var hasStableMeasurement: Boolean = false
        private set
    private var stableAtMillis = 0L

    var currentMode: Anime4KMode = Anime4K.maxSmartMode(initialMediaInfo)
        private set

    var stableMode: Anime4KMode = currentMode
        private set

    var isSuspended: Boolean = false
        private set

    val calibration: Anime4KCalibration
        get() = Anime4KCalibration(stableMode, successfulProbes, stableAtMillis)

    private var currentDiagnostics = Anime4KSmartDiagnostics(
        profile = Anime4KProfile.Smart,
        mode = currentMode,
        sourceWidth = initialMediaInfo.sourceWidth,
        sourceHeight = initialMediaInfo.sourceHeight,
        targetWidth = initialMediaInfo.targetWidth,
        targetHeight = initialMediaInfo.targetHeight,
        targetFramesPerSecond = Anime4K.effectiveFrameRate(initialMediaInfo),
        reason = lastReason,
    )

    val diagnostics: Anime4KSmartDiagnostics
        get() = currentDiagnostics.copy(
            mode = currentMode,
            probing = false,
            suspended = isSuspended,
            reason = lastReason,
        )

    fun updateMediaInfo(
        updatedMediaInfo: Anime4KMediaInfo,
        nowMillis: Long,
    ): Anime4KSmartAdjustment? {
        val familyChanged = Anime4K.resolveSmartMode(updatedMediaInfo) != Anime4K.resolveSmartMode(mediaInfo)
        val workloadChanged = !Anime4K.sameWorkload(measuredWorkload, updatedMediaInfo)
        mediaInfo = updatedMediaInfo
        if (workloadChanged) {
            measuredWorkload = updatedMediaInfo
            hasStableMeasurement = false
            resetTelemetry(nowMillis)
            lastReason = "media changed"
            updateDiagnostics(Anime4KHealth.Unknown)
        }
        if (isSuspended) return null

        if (
            !familyChanged &&
            Anime4K.isSecondaryMode(currentMode) &&
            !Anime4K.supportsSecondaryPass(updatedMediaInfo)
        ) {
            val fallback = availableAtOrBelow(Anime4K.primaryVariant(currentMode))
            if (fallback != currentMode) {
                return transition(
                    mode = fallback,
                    nowMillis = nowMillis,
                    reason = "secondary pass no longer compatible",
                    suspended = fallback == Anime4KMode.Off,
                )
            }
        }

        // MPV may expose the final output size or frame-rate metadata only after video reconfig.
        // Re-evaluate the ceiling once those facts become available, but never resurrect a preset
        // that already failed shader compilation or playback-budget checks in this session.
        val maximum = highestAvailableMode(updatedMediaInfo)
        if (maximum == currentMode || Anime4K.complexityRank(maximum) <= Anime4K.complexityRank(currentMode)) {
            pendingCapabilityMode = null
        }
        if (
            workloadChanged &&
            maximum != currentMode &&
            (familyChanged || Anime4K.complexityRank(maximum) > Anime4K.complexityRank(currentMode))
        ) {
            if (!familyChanged) {
                if (pendingCapabilityMode != maximum) {
                    pendingCapabilityMode = maximum
                    pendingCapabilitySinceMillis = nowMillis
                }
                return null
            }
            return transition(
                mode = maximum,
                nowMillis = nowMillis,
                reason = "maximum compatible preset resolved",
                suspended = maximum == Anime4KMode.Off,
            )
        }
        return null
    }

    fun onSample(sample: Anime4KPerformanceSample): Anime4KSmartAdjustment? {
        if (isSuspended) return null
        if (!sample.playing) {
            resetTelemetry(sample.timestampMillis)
            return null
        }
        // Resize/zoom animations can cross the 2x boundary repeatedly. Require a stable
        // capability before increasing quality; incompatible passes are removed immediately.
        pendingCapabilityMode?.let { mode ->
            if (sample.timestampMillis - pendingCapabilitySinceMillis >= CAPABILITY_SETTLE_MILLIS &&
                mode == highestAvailableMode(mediaInfo)
            ) {
                return transition(mode, sample.timestampMillis, "maximum compatible preset resolved")
            }
        }
        val previous = lastSample
        if (previous != null &&
            (
                sample.timestampMillis < previous.timestampMillis ||
                    sample.timestampMillis - previous.timestampMillis > MAX_SAMPLE_GAP_MILLIS ||
                    countersReset(previous, sample)
                )
        ) {
            resetTelemetry(sample.timestampMillis)
        }
        if (
            lastSample != null &&
            sample.timestampMillis - lastSample!!.timestampMillis < MIN_SAMPLE_INTERVAL_MILLIS
        ) {
            return null
        }
        lastSample = sample

        // These are measured render-pass costs, never the video's frame cadence (estimated-vf-fps).
        val renderCapacity = Anime4K.renderCapacity(sample, mediaInfo)
        if (renderCapacity != null) renderCapacitySamples += renderCapacity

        if (sample.timestampMillis < warmupUntilMillis) {
            windowStartSample = null
            renderCapacitySamples.clear()
            renderCapacity?.let { renderCapacitySamples += it }
            updateDiagnostics(
                health = collectingHealth(),
                renderCapacity = renderCapacity ?: currentDiagnostics.renderCapacityFramesPerSecond,
            )
            return null
        }

        val windowStart = windowStartSample ?: run {
            windowStartSample = sample
            renderCapacitySamples.clear()
            renderCapacity?.let { renderCapacitySamples += it }
            updateDiagnostics(
                health = collectingHealth(),
                renderCapacity = renderCapacity ?: currentDiagnostics.renderCapacityFramesPerSecond,
            )
            return null
        }
        val elapsedMillis = sample.timestampMillis - windowStart.timestampMillis
        if (elapsedMillis < WINDOW_MILLIS) {
            updateDiagnostics(
                health = collectingHealth(),
                renderCapacity =
                renderCapacitySamples.conservativeCapacityOrNull()
                    ?: currentDiagnostics.renderCapacityFramesPerSecond,
            )
            return null
        }

        val elapsedSeconds = elapsedMillis.toDouble() / 1000.0
        val targetFps = Anime4K.effectiveFrameRate(mediaInfo)
        val window = PerformanceWindow(
            elapsedSeconds = elapsedSeconds,
            expectedFrames = (targetFps * elapsedSeconds).coerceAtLeast(1.0),
            outputDrops = counterDelta(sample.outputDroppedFrames, windowStart.outputDroppedFrames)?.let {
                val intentional = (
                    (Anime4K.playbackFrameRate(mediaInfo) - targetFps).coerceAtLeast(0.0) *
                        elapsedSeconds
                    ).toLong()
                (it - intentional).coerceAtLeast(0)
            },
            decoderDrops = counterDelta(sample.decoderDroppedFrames, windowStart.decoderDroppedFrames),
            delayedFrames = counterDelta(sample.delayedFrames, windowStart.delayedFrames),
            mistimedFrames = counterDelta(sample.mistimedFrames, windowStart.mistimedFrames),
            renderCapacity = renderCapacitySamples.takeIf { it.size >= 3 && it.size >= elapsedSeconds * 0.6 }
                ?.conservativeCapacityOrNull(),
        )
        windowStartSample = sample
        renderCapacitySamples.clear()
        renderCapacity?.let { renderCapacitySamples += it }
        return evaluateWindow(window, sample.timestampMillis)
    }

    /** Discards measurements across a seek without changing the selected quality. */
    fun resetTelemetry(nowMillis: Long? = null) {
        nowMillis?.let { warmupUntilMillis = it + WARMUP_MILLIS }
        lastSample = null
        windowStartSample = null
        renderCapacitySamples.clear()
        overloadedWindows = 0
        hasEvaluatedWindow = false
        updateDiagnostics(Anime4KHealth.Unknown, null, null, null, null, null, null, 0.0)
    }

    fun invalidateCalibration(nowMillis: Long) {
        hasStableMeasurement = false
        resetTelemetry(nowMillis)
    }

    /** Explicit user reactivation after Smart has suspended itself at Off. */
    fun resume(nowMillis: Long) {
        performanceBlockedModes.clear()
        pendingCapabilityMode = null
        currentMode = highestAvailableMode(mediaInfo)
        isSuspended = currentMode == Anime4KMode.Off
        hasStableMeasurement = false
        lastTransitionMillis = nowMillis
        lastReason = "maximum compatible preset reactivated"
        resetTelemetry(nowMillis)
    }

    /** Handles a synchronous MPV failure while applying a candidate mode. */
    fun onModeApplyFailure(nowMillis: Long): Anime4KSmartAdjustment? {
        if (currentMode == Anime4KMode.Off) return null
        shaderFailedModes += currentMode
        val safeFallback = availableAtOrBelow(Anime4K.lessDemandingMode(currentMode))
        return transition(
            mode = safeFallback,
            nowMillis = nowMillis,
            reason = "shader pipeline unavailable",
            suspended = safeFallback == Anime4KMode.Off,
        )
    }

    fun onShaderError(
        nowMillis: Long,
        shaderName: String? = null,
    ): Anime4KSmartAdjustment? {
        if (currentMode == Anime4KMode.Off) return null
        shaderName?.let { failedShader ->
            Anime4KMode.entries
                .filter { failedShader in it.shaderFileNames }
                .forEach(shaderFailedModes::add)
            if (failedShader !in currentMode.shaderFileNames) return null
        }
        shaderFailedModes += currentMode
        val safeFallback = availableAtOrBelow(Anime4K.lessDemandingMode(currentMode))
        return transition(
            mode = safeFallback,
            nowMillis = nowMillis,
            reason = "shader compilation failure",
            suspended = safeFallback == Anime4KMode.Off,
        )
    }

    private fun evaluateWindow(
        window: PerformanceWindow,
        nowMillis: Long,
    ): Anime4KSmartAdjustment? {
        val targetFps = Anime4K.effectiveFrameRate(mediaInfo)
        val outputDropRate = window.rate(window.outputDrops)
        val decoderDropRate = window.rate(window.decoderDrops)
        val delayedFrameRate = window.rate(window.delayedFrames)
        val mistimedFrameRate = window.rate(window.mistimedFrames)
        val headroom = window.renderCapacity?.let { it / targetFps }
        val confidence = telemetryConfidence(
            window.renderCapacity,
            outputDropRate,
            decoderDropRate,
            delayedFrameRate,
            mistimedFrameRate,
        )
        val outputBudgetExceeded = headroom == null || headroom < HEALTHY_HEADROOM

        val severe =
            (headroom != null && headroom < SEVERE_HEADROOM) ||
                ((outputDropRate ?: 0.0) >= 0.03 && outputBudgetExceeded) ||
                ((delayedFrameRate ?: 0.0) >= 0.05 && headroom != null && headroom < 1.05)
        val overloaded =
            severe ||
                (headroom != null && headroom < 0.98) ||
                ((outputDropRate ?: 0.0) >= OUTPUT_DROP_OVERLOAD && outputBudgetExceeded) ||
                ((delayedFrameRate ?: 0.0) >= DELAYED_FRAME_OVERLOAD && headroom != null && headroom < 1.1)
        val healthy =
            window.renderCapacity != null &&
                confidence >= 0.75 &&
                (headroom ?: 0.0) >= HEALTHY_HEADROOM &&
                below(outputDropRate, OUTPUT_DROP_HEALTHY) &&
                below(decoderDropRate, DECODER_DROP_HEALTHY) &&
                below(delayedFrameRate, DELAYED_FRAME_HEALTHY) &&
                below(mistimedFrameRate, MISTIMED_FRAME_HEALTHY)
        val health = when {
            severe -> Anime4KHealth.Severe
            overloaded -> Anime4KHealth.Overloaded
            healthy -> Anime4KHealth.Healthy
            confidence > 0.0 -> Anime4KHealth.Borderline
            else -> Anime4KHealth.Unknown
        }
        hasEvaluatedWindow = true
        updateDiagnostics(
            health = health,
            renderCapacity = window.renderCapacity,
            outputDropRate = outputDropRate,
            decoderDropRate = decoderDropRate,
            delayedFrameRate = delayedFrameRate,
            mistimedFrameRate = mistimedFrameRate,
            headroom = headroom,
            confidence = confidence,
        )

        if (overloaded) {
            overloadedWindows++
            val shouldDowngrade = severe ||
                overloadedWindows >= OVERLOADED_WINDOWS_FOR_DOWNGRADE
            if (shouldDowngrade && nowMillis - lastTransitionMillis >= DOWNGRADE_COOLDOWN_MILLIS) {
                val fallback = availableAtOrBelow(Anime4K.lessDemandingMode(currentMode))
                if (fallback != currentMode) {
                    performanceBlockedModes += currentMode
                    return transition(
                        mode = fallback,
                        nowMillis = nowMillis,
                        reason = "playback headroom exhausted",
                        suspended = fallback == Anime4KMode.Off,
                    )
                }
            }
            return null
        }

        overloadedWindows = 0
        if (!healthy) return null

        if (!hasStableMeasurement || stableMode != currentMode) {
            stableMode = currentMode
            stableAtMillis = nowMillis
            successfulProbes++
            hasStableMeasurement = true
        }

        // Ceiling-first policy: a healthy window confirms the current level but never triggers a
        // speculative upgrade. A manual reactivation or a new media capability update may choose a
        // higher ceiling explicitly.
        return null
    }

    private fun highestAvailableMode(mediaInfo: Anime4KMediaInfo): Anime4KMode {
        return availableAtOrBelow(Anime4K.maxSmartMode(mediaInfo))
    }

    private fun availableAtOrBelow(mode: Anime4KMode): Anime4KMode {
        var candidate = mode
        while (
            candidate != Anime4KMode.Off &&
            (candidate in shaderFailedModes || candidate in performanceBlockedModes)
        ) {
            val fallback = Anime4K.lessDemandingMode(candidate)
            if (fallback == candidate) break
            candidate = fallback
        }
        return candidate
    }

    private fun collectingHealth(): Anime4KHealth =
        if (hasEvaluatedWindow) currentDiagnostics.health else Anime4KHealth.Unknown

    private fun transition(
        mode: Anime4KMode,
        nowMillis: Long,
        reason: String,
        suspended: Boolean = false,
    ): Anime4KSmartAdjustment {
        currentMode = mode
        pendingCapabilityMode = null
        hasStableMeasurement = false
        isSuspended = suspended
        lastTransitionMillis = nowMillis
        lastReason = reason
        resetTelemetry(nowMillis)
        return Anime4KSmartAdjustment(mode, reason)
    }

    private fun updateDiagnostics(
        health: Anime4KHealth,
        renderCapacity: Double? = currentDiagnostics.renderCapacityFramesPerSecond,
        outputDropRate: Double? = currentDiagnostics.outputDropRate,
        decoderDropRate: Double? = currentDiagnostics.decoderDropRate,
        delayedFrameRate: Double? = currentDiagnostics.delayedFrameRate,
        mistimedFrameRate: Double? = currentDiagnostics.mistimedFrameRate,
        headroom: Double? = currentDiagnostics.headroom,
        confidence: Double = currentDiagnostics.confidence,
    ) {
        currentDiagnostics = Anime4KSmartDiagnostics(
            profile = Anime4KProfile.Smart,
            mode = currentMode,
            sourceWidth = mediaInfo.sourceWidth,
            sourceHeight = mediaInfo.sourceHeight,
            targetWidth = mediaInfo.targetWidth,
            targetHeight = mediaInfo.targetHeight,
            targetFramesPerSecond = Anime4K.effectiveFrameRate(mediaInfo),
            renderCapacityFramesPerSecond = renderCapacity,
            renderTimeMillis = renderCapacity?.takeIf { it > 0 }?.let { 1000.0 / it },
            outputDropRate = outputDropRate,
            decoderDropRate = decoderDropRate,
            delayedFrameRate = delayedFrameRate,
            mistimedFrameRate = mistimedFrameRate,
            headroom = headroom,
            confidence = confidence,
            health = health,
            probing = false,
            suspended = isSuspended,
            reason = lastReason,
        )
    }

    private fun telemetryConfidence(
        renderCapacity: Double?,
        outputDropRate: Double?,
        decoderDropRate: Double?,
        delayedFrameRate: Double?,
        mistimedFrameRate: Double?,
    ): Double {
        return (if (renderCapacity != null) 0.40 else 0.0) +
            (if (outputDropRate != null) 0.25 else 0.0) +
            (if (decoderDropRate != null) 0.15 else 0.0) +
            (if (delayedFrameRate != null) 0.10 else 0.0) +
            (if (mistimedFrameRate != null) 0.10 else 0.0)
    }

    private fun below(value: Double?, limit: Double): Boolean = value == null || value < limit

    private fun PerformanceWindow.rate(delta: Long?): Double? = delta?.toDouble()?.div(expectedFrames)

    private fun counterDelta(current: Long?, previous: Long?): Long? {
        return if (current != null && previous != null) {
            if (current >= previous && previous >= 0) current - previous else null
        } else {
            null
        }
    }

    // A slow-side percentile avoids a handful of fast frames hiding an expensive scene.
    private fun List<Double>.conservativeCapacityOrNull(): Double? = if (isEmpty()) null else sorted()[(size - 1) / 4]

    private fun countersReset(previous: Anime4KPerformanceSample, current: Anime4KPerformanceSample): Boolean =
        listOf(
            previous.outputDroppedFrames to current.outputDroppedFrames,
            previous.decoderDroppedFrames to current.decoderDroppedFrames,
            previous.delayedFrames to current.delayedFrames,
            previous.mistimedFrames to current.mistimedFrames,
        ).any { (old, new) -> old != null && new != null && new < old }

    fun suspend(nowMillis: Long): Anime4KSmartAdjustment =
        transition(Anime4KMode.Off, nowMillis, "shader pipeline unavailable", suspended = true)
}
