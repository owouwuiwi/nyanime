package eu.kanade.tachiyomi.ui.player

/** Episode-owned budget, retained when a quality reload creates a new governor. */
class Anime4KRecoveryBudget {
    internal var attempts = 0
    internal var lastAttemptMillis: Long? = null
    internal val failedModes = mutableMapOf<String, MutableSet<Anime4KMode>>()
}

/**
 * Session-local Adaptive Headroom Governor.
 *
 * Smart starts at a recently measured level or the highest compatible preset. Consecutive GPU
 * overloads with actual losses have a fast guard; sustained windows govern calibration/recovery.
 * Missing telemetry is treated as
 * unknown, never as a reason to oscillate or to silently reduce quality.
 */
class Anime4KSmartController(
    initialMediaInfo: Anime4KMediaInfo,
    initialTimestampMillis: Long,
    initialCalibration: Anime4KCalibration? = null,
    initialCalibrationAgeMillis: Long? = null,
    private val recoveryBudget: Anime4KRecoveryBudget = Anime4KRecoveryBudget(),
) {
    companion object {
        private const val MIN_SAMPLE_INTERVAL_MILLIS = 750L
        private const val MAX_SAMPLE_GAP_MILLIS = 2_500L
        private const val WARMUP_MILLIS = 2_000L
        private const val WINDOW_MILLIS = 5_000L
        private const val DOWNGRADE_COOLDOWN_MILLIS = 1_500L
        private const val CAPABILITY_SETTLE_MILLIS = 1_500L
        private const val OVERLOADED_WINDOWS_FOR_DOWNGRADE = 2
        private const val RECOVERY_STABLE_MILLIS = 30_000L
        private const val RECOVERY_INTERVAL_MILLIS = 120_000L
        private const val RECOVERY_MAX_ATTEMPTS = 2
        private const val RECOVERY_VERIFY_MILLIS = 15_000L
        private const val CALIBRATION_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
        private const val RECOVERY_HEADROOM = 1.35

        // GPU timestamps exclude CPU scheduling, queueing and presentation. When
        // rendering occupies at least half the frame interval, repeated output
        // losses can exhaust the complete pipeline despite nominal GPU headroom.
        private const val SUBSTANTIAL_RENDER_HEADROOM = 2.0
        private const val PRESENTATION_DROP_RATE = 0.03

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
    private var fastOverloads = 0
    private var fastPresentationLosses = 0
    private var recoveryStableSinceMillis: Long? = null
    private var recoveryPreviousMode: Anime4KMode? = null
    private var recoveryPlayingMillis = 0L
    private var pendingCapabilityMode: Anime4KMode? = null
    private var pendingCapabilitySinceMillis = 0L
    var hasStableMeasurement: Boolean = false
        private set
    private var stableAtMillis = 0L

    var currentMode: Anime4KMode = initialCalibration?.maxStableMode?.takeIf { mode ->
        initialCalibrationAgeMillis != null &&
            initialCalibrationAgeMillis in 0..CALIBRATION_MAX_AGE_MILLIS &&
            initialCalibration.successfulProbes > 0 &&
            mode != Anime4KMode.Off &&
            generateSequence(Anime4K.maxSmartMode(initialMediaInfo)) { candidate ->
                Anime4K.lessDemandingMode(candidate).takeIf { it != candidate }
            }.any { it == mode }
    } ?: Anime4K.maxSmartMode(initialMediaInfo)
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
            probing = recoveryPreviousMode != null,
            suspended = isSuspended,
            reason = lastReason,
            recoveryAttempts = recoveryBudget.attempts,
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
            recoveryPreviousMode = null
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
        var previous = lastSample
        if (previous != null &&
            (
                sample.timestampMillis < previous.timestampMillis ||
                    sample.timestampMillis - previous.timestampMillis > MAX_SAMPLE_GAP_MILLIS ||
                    countersReset(previous, sample)
                )
        ) {
            resetTelemetry(sample.timestampMillis)
            previous = null
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

        // Averages can hide a newly expensive scene. Require two fresh GPU overruns AND
        // real output losses; decoder failures, missing timing and isolated spikes do not qualify.
        val recentCost = sample.lastRenderTimeMillis?.takeIf { it.isFinite() && it > 0 && it < 10_000 }
        val recentCapacity = Anime4K.renderCapacity(
            sample.copy(renderTimeMillis = recentCost ?: sample.renderTimeMillis),
            mediaInfo,
        )
        val measuredPrevious = previous?.takeIf { it.timestampMillis >= warmupUntilMillis }
        val actualOutputLosses = measuredPrevious?.let {
            val elapsed = (sample.timestampMillis - it.timestampMillis) / 1000.0
            counterDelta(sample.outputDroppedFrames, it.outputDroppedFrames)?.let { drops ->
                (
                    drops -
                        kotlin.math.ceil(
                            (Anime4K.playbackFrameRate(mediaInfo) - Anime4K.effectiveFrameRate(mediaInfo))
                                .coerceAtLeast(0.0) *
                                elapsed,
                        ).toLong()
                    ).coerceAtLeast(0)
            }
        }
        fastOverloads = if ((actualOutputLosses ?: 0) > 0 &&
            recentCapacity != null &&
            recentCapacity < Anime4K.effectiveFrameRate(mediaInfo)
        ) {
            fastOverloads + 1
        } else {
            0
        }
        if (fastOverloads >= 2 && sample.timestampMillis - lastTransitionMillis >= DOWNGRADE_COOLDOWN_MILLIS) {
            return reduceQuality(sample.timestampMillis, "consecutive GPU overload with frame loss")
        }
        val substantialRenderLoad = mediaInfo.frameRateKnown &&
            recentCapacity != null &&
            recentCapacity <= SUBSTANTIAL_RENDER_HEADROOM * Anime4K.effectiveFrameRate(mediaInfo)
        val presentationLoss = measuredPrevious?.let {
            val elapsed = (sample.timestampMillis - it.timestampMillis) / 1000.0
            val threshold = maxOf(
                2L,
                kotlin.math.ceil(Anime4K.effectiveFrameRate(mediaInfo) * elapsed * PRESENTATION_DROP_RATE).toLong(),
            )
            substantialRenderLoad &&
                counterDelta(sample.decoderDroppedFrames, it.decoderDroppedFrames) == 0L &&
                actualOutputLosses != null &&
                actualOutputLosses >= threshold
        } == true
        fastPresentationLosses = if (presentationLoss) fastPresentationLosses + 1 else 0
        if (fastPresentationLosses >= 2 &&
            sample.timestampMillis - lastTransitionMillis >= DOWNGRADE_COOLDOWN_MILLIS
        ) {
            return reduceQuality(sample.timestampMillis, "sustained presentation loss with substantial render cost")
        }
        if (recoveryPreviousMode != null) {
            previous?.takeIf { it.timestampMillis >= warmupUntilMillis }?.let {
                recoveryPlayingMillis += sample.timestampMillis - it.timestampMillis
            }
            if (recoveryPlayingMillis >= RECOVERY_VERIFY_MILLIS) {
                return failedProbeFallback(sample.timestampMillis, "quality recovery could not be verified")
            }
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
        fastOverloads = 0
        fastPresentationLosses = 0
        recoveryStableSinceMillis = null
        recoveryPlayingMillis = 0
        updateDiagnostics(Anime4KHealth.Unknown, null, null, null, null, null, null, 0.0)
    }

    fun invalidateCalibration(nowMillis: Long) {
        hasStableMeasurement = false
        resetTelemetry(nowMillis)
    }

    /** Explicit user reactivation after Smart has suspended itself at Off. */
    fun resume(nowMillis: Long) {
        performanceBlockedModes.clear()
        recoveryPreviousMode = null
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
        failedProbeFallback(nowMillis, "recovery shader pipeline unavailable")?.let { return it }
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
        failedProbeFallback(nowMillis, "recovery shader compilation failure")?.let { return it }
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
        val presentationBudgetExceeded = mediaInfo.frameRateKnown &&
            window.decoderDrops == 0L &&
            headroom != null &&
            headroom <= SUBSTANTIAL_RENDER_HEADROOM

        val severe =
            (headroom != null && headroom < SEVERE_HEADROOM) ||
                ((outputDropRate ?: 0.0) >= 0.03 && outputBudgetExceeded) ||
                ((delayedFrameRate ?: 0.0) >= 0.05 && headroom != null && headroom < 1.05)
        val overloaded =
            severe ||
                (headroom != null && headroom < 0.98) ||
                (
                    (outputDropRate ?: 0.0) >= OUTPUT_DROP_OVERLOAD &&
                        (outputBudgetExceeded || presentationBudgetExceeded)
                    ) ||
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

        val lossless = window.outputDrops == 0L &&
            window.decoderDrops == 0L &&
            window.delayedFrames == 0L &&
            (window.mistimedFrames == null || window.mistimedFrames == 0L)
        if (recoveryPreviousMode != null && overloaded) {
            return reduceQuality(nowMillis, "quality recovery exceeded playback budget")
        }
        // A higher preset needs an actual lossless measured window. Unknown or borderline
        // telemetry has a bounded trial above; it must never leave the UI probing forever.
        if (recoveryPreviousMode != null &&
            lossless &&
            confidence >= 0.75 &&
            headroom != null &&
            headroom >= 1.05
        ) {
            performanceBlockedModes.remove(currentMode)
            recoveryPreviousMode = null
            lastReason = "higher quality confirmed"
            markStable(nowMillis)
        }

        if (overloaded) {
            recoveryStableSinceMillis = null
            overloadedWindows++
            val shouldDowngrade = severe ||
                overloadedWindows >= OVERLOADED_WINDOWS_FOR_DOWNGRADE
            if (shouldDowngrade && nowMillis - lastTransitionMillis >= DOWNGRADE_COOLDOWN_MILLIS) {
                return reduceQuality(nowMillis, "playback headroom exhausted")
            }
            return null
        }

        overloadedWindows = 0
        if (!healthy) {
            recoveryStableSinceMillis = null
            return null
        }

        if (recoveryPreviousMode == null) markStable(nowMillis)
        if (!lossless || headroom == null || headroom < RECOVERY_HEADROOM) {
            recoveryStableSinceMillis = null
            return null
        }
        val stableSince = recoveryStableSinceMillis ?: nowMillis.also { recoveryStableSinceMillis = it }
        val lastRecovery = recoveryBudget.lastAttemptMillis
        if (nowMillis - stableSince >= RECOVERY_STABLE_MILLIS &&
            recoveryBudget.attempts < RECOVERY_MAX_ATTEMPTS &&
            (lastRecovery == null || nowMillis - lastRecovery >= RECOVERY_INTERVAL_MILLIS)
        ) {
            val candidate = Anime4K.moreDemandingMode(currentMode, mediaInfo)
            if (candidate != null &&
                candidate !in shaderFailedModes &&
                candidate !in recoveryBudget.failedModes[workloadKey()].orEmpty()
            ) {
                recoveryPreviousMode = currentMode
                recoveryBudget.attempts++
                recoveryBudget.lastAttemptMillis = nowMillis
                return transition(candidate, nowMillis, "cautious quality recovery")
            }
        }
        return null
    }

    private fun workloadKey(): String = Anime4K.calibrationKey(mediaInfo, "", "", "", "")

    private fun markStable(nowMillis: Long) {
        if (!hasStableMeasurement || stableMode != currentMode) {
            stableMode = currentMode
            stableAtMillis = nowMillis
            successfulProbes++
            hasStableMeasurement = true
        }
    }

    private fun failedProbeFallback(nowMillis: Long, reason: String): Anime4KSmartAdjustment? {
        val previous = recoveryPreviousMode ?: return null
        recoveryBudget.failedModes.getOrPut(workloadKey()) { mutableSetOf() } += currentMode
        recoveryPreviousMode = null
        val fallback = availableAtOrBelow(previous)
        return transition(fallback, nowMillis, reason, suspended = fallback == Anime4KMode.Off)
    }

    private fun reduceQuality(nowMillis: Long, reason: String): Anime4KSmartAdjustment? {
        performanceBlockedModes += currentMode
        failedProbeFallback(nowMillis, "quality recovery reverted")?.let { return it }
        val fallback = availableAtOrBelow(Anime4K.lessDemandingMode(currentMode))
        return if (fallback != currentMode) {
            transition(fallback, nowMillis, reason, suspended = fallback == Anime4KMode.Off)
        } else {
            null
        }
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
