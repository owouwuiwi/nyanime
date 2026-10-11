package it.lorelore.nyanime.attention.core

/** Camera estimates are evidence, never proof of cognitive attention or sleep. */
enum class AttentionSignal { Watching, Away, EyesClosed, Unknown }
enum class AttentionReason { Distraction, EyesClosed }
enum class RecoveryMode { Off, Ask, Automatic }
enum class AttentionMode { Automatic, ObserveOnly }

data class AttentionOptions(
    val enabled: Boolean = false,
    val mode: AttentionMode = AttentionMode.Automatic,
    val pauseAway: Boolean = true,
    val pauseClosed: Boolean = true,
    val awayMs: Long = 3_000,
    val closedMs: Long = 10_000,
    val returnMs: Long = 1_500,
    val resumeAway: Boolean = true,
    val resumeClosed: Boolean = false,
    val recovery: RecoveryMode = RecoveryMode.Ask,
    val marginMs: Long = 3_000,
    val cameraWaitMs: Long = 60_000,
    val sensitivity: Float = 1f,
) {
    fun safe() = copy(
        awayMs = awayMs.coerceIn(2_000, 60_000),
        closedMs = closedMs.coerceIn(3_000, 120_000),
        returnMs = returnMs.coerceIn(1_000, 10_000),
        marginMs = marginMs.coerceIn(0, 30_000),
        cameraWaitMs = cameraWaitMs.coerceIn(10_000, 180_000),
        sensitivity = sensitivity.takeIf { it.isFinite() }?.coerceIn(0.7f, 1.3f) ?: 1f,
    )
}

data class AttentionObservation(val atMs: Long, val signal: AttentionSignal)

data class AttentionPlayback(
    val key: String,
    val positionMs: Long,
    val playing: Boolean,
    val buffering: Boolean = false,
    val allowed: Boolean = true,
    val seekable: Boolean = true,
    val incognito: Boolean = false,
    val revision: Long = 0,
    val renderDrops: Long? = null,
    val playbackSpeed: Float = 1f,
)

sealed interface AttentionAction {
    data class Remember(val positionMs: Long) : AttentionAction
    data class Pause(val reason: AttentionReason, val recoveryMs: Long?) : AttentionAction
    data class Resume(val recoveryMs: Long?) : AttentionAction
    data class Recover(val positionMs: Long) : AttentionAction
}

/** Pure temporal policy. All time is monotonic and all commands remain owned by one media epoch. */
class AttentionPolicy {
    private var key: String? = null
    private var revision = 0L
    private var lastTick = -1L
    private var since = 0L
    private var signal = AttentionSignal.Unknown
    private var anchor: Long? = null
    private var interruptionConfirmed = false
    private var pauseAt = 0L
    private var resumeExpired = false
    var pauseReason: AttentionReason? = null
        private set

    fun reset() {
        key = null
        pauseReason = null
        resumeExpired = false
        clearEvidence()
        lastTick = -1
    }

    private fun clearEvidence() {
        signal = AttentionSignal.Unknown
        anchor = null
        interruptionConfirmed = false
        since = 0
    }

    fun cameraNeeded(now: Long, frame: AttentionPlayback, options: AttentionOptions): Boolean {
        if (!options.enabled || !frame.allowed) return false
        if (options.mode == AttentionMode.ObserveOnly) return frame.playing && !frame.buffering
        if (frame.playing) return true
        val reason = pauseReason ?: return false
        val automatic = if (reason == AttentionReason.Distraction) options.resumeAway else options.resumeClosed
        return automatic && !resumeExpired && now - pauseAt < options.cameraWaitMs
    }

    fun tick(
        now: Long,
        observation: AttentionObservation?,
        frame: AttentionPlayback,
        options: AttentionOptions,
    ): List<AttentionAction> {
        val settings = options.safe()
        if (!settings.enabled || !frame.allowed || frame.key != key || frame.revision != revision) {
            reset()
            key = frame.key
            revision = frame.revision
        }
        if (!settings.enabled || !frame.allowed || settings.mode == AttentionMode.ObserveOnly) {
            if (settings.mode == AttentionMode.ObserveOnly) reset()
            return emptyList()
        }
        if (lastTick >= now) return emptyList()
        val gap = lastTick >= 0 && now - lastTick > 1_200
        lastTick = now
        if (gap || frame.buffering) clearEvidence()
        if (frame.buffering) return emptyList()
        if (pauseReason != null && !resumeExpired && now - pauseAt >= settings.cameraWaitMs) {
            // Expired ownership must never be revived by an old or later camera result.
            resumeExpired = true
            clearEvidence()
        }
        if (!frame.playing && pauseReason == null) {
            clearEvidence()
            return emptyList()
        }
        val current = observation?.takeIf { it.atMs <= now && now - it.atMs <= 1_000 }?.signal
            ?: AttentionSignal.Unknown
        if (current != signal) {
            signal = current
            since = now
            if (current == AttentionSignal.Unknown) {
                anchor = null
                interruptionConfirmed = false
            } else if (current != AttentionSignal.Watching && anchor == null && frame.playing) {
                anchor = (frame.positionMs - settings.marginMs).coerceAtLeast(0)
            }
        }
        if (current == AttentionSignal.Unknown) return emptyList()
        val elapsed = now - since
        if (current == AttentionSignal.Watching) {
            if (elapsed < settings.returnMs) return emptyList()
            val rewind = anchor?.takeIf {
                interruptionConfirmed &&
                    frame.seekable &&
                    settings.recovery == RecoveryMode.Automatic
            }
            anchor = null
            interruptionConfirmed = false
            if (pauseReason != null && cameraNeeded(now, frame, settings)) {
                pauseReason = null
                return listOf(AttentionAction.Resume(rewind))
            }
            if (rewind != null && frame.playing) return listOf(AttentionAction.Recover(rewind))
            return emptyList()
        }
        val closed = current == AttentionSignal.EyesClosed
        val delay = if (closed) settings.closedMs else settings.awayMs
        if (elapsed < delay || !frame.playing || pauseReason != null) return emptyList()
        val firstConfirmation = !interruptionConfirmed
        interruptionConfirmed = true
        val shouldPause = if (closed) settings.pauseClosed else settings.pauseAway
        if (!shouldPause) {
            return if (firstConfirmation && frame.seekable && settings.recovery != RecoveryMode.Off) {
                listOf(AttentionAction.Remember(anchor ?: frame.positionMs))
            } else {
                emptyList()
            }
        }
        pauseReason = if (closed) AttentionReason.EyesClosed else AttentionReason.Distraction
        pauseAt = now
        resumeExpired = false
        return listOf(
            AttentionAction.Pause(
                checkNotNull(pauseReason),
                anchor?.takeIf { frame.seekable && settings.recovery != RecoveryMode.Off },
            ),
        )
    }
}
