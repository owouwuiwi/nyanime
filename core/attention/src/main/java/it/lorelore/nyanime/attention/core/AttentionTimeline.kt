package it.lorelore.nyanime.attention.core

/** A sampled estimate, never proof of attention or comprehension. Mixed bits preserve revisits. */
data class AttentionTraceChunk(val key: String, val incognito: Boolean, val masks: ByteArray)

/**
 * Five-second media-position bins contain Watching/Away/Closed/Unknown evidence bits.
 * Only consecutive, eligible playback observations contribute. Nothing is inferred across seeks,
 * missing lifecycle ticks, privacy changes, buffering or pauses. No face measurements are retained.
 */
class AttentionTimeline {
    private data class Sample(val at: Long, val position: Long, val signal: AttentionSignal, val speed: Float)
    private var key: String? = null
    private var incognito = false
    private var revision = 0L
    private var previous: Sample? = null
    private var lastTick = -1L
    private var signal = AttentionSignal.Unknown
    private var signalSince = 0L
    private var lastFlush = 0L
    private var masks = ByteArray(0)
    private var dirty = false

    fun tick(now: Long, observation: AttentionObservation?, frame: AttentionPlayback?): AttentionTraceChunk? {
        if (frame == null ||
            !frame.allowed ||
            !frame.playing ||
            frame.buffering ||
            frame.key.isBlank() ||
            frame.positionMs !in 0..MAX_POSITION_MS ||
            now < 0
        ) {
            return stop()
        }
        var completed: AttentionTraceChunk? = null
        if (key != frame.key || incognito != frame.incognito || revision != frame.revision) {
            completed = stop()
            key = frame.key
            incognito = frame.incognito
            revision = frame.revision
            lastFlush = now
        }
        if (now <= lastTick) return completed
        val interrupted = lastTick >= 0 && now - lastTick > 1_200
        lastTick = now
        val evidence = observation?.takeIf { it.atMs <= now && now - it.atMs <= 1_000 }?.signal
            ?: AttentionSignal.Unknown
        if (interrupted || evidence != signal) {
            signal = evidence
            signalSince = now
        }
        val speed = frame.playbackSpeed.takeIf { it.isFinite() }?.coerceIn(0.1f, 8f) ?: 1f
        val current = Sample(now, frame.positionMs, evidence, speed)
        val before = previous
        if (interrupted || before == null) {
            previous = current
        } else {
            val elapsed = now - before.at
            val advance = frame.positionMs - before.position
            val plausibleAdvance = elapsed * maxOf(before.speed, speed) * 1.5 + 250
            if (advance < 0 || elapsed > 1_200 || advance > plausibleAdvance) {
                previous = current
                signalSince = now
            } else if (advance > 0) {
                val confirmation = when (evidence) {
                    AttentionSignal.Watching -> 600
                    AttentionSignal.Away -> 1_000
                    AttentionSignal.EyesClosed -> 2_000
                    AttentionSignal.Unknown -> 0
                }
                val stable = before.signal == evidence && now - signalSince >= confirmation
                mark(before.position, frame.positionMs, if (stable) evidence else AttentionSignal.Unknown)
                previous = current
            }
            // Keep a stationary position's original time to support coarse player clocks.
        }
        if (completed != null) return completed
        if (now - lastFlush < 15_000) return null
        lastFlush = now
        return drain()
    }

    fun stop(): AttentionTraceChunk? {
        val completed = drain()
        key = null
        previous = null
        lastTick = -1
        signal = AttentionSignal.Unknown
        signalSince = 0
        masks = ByteArray(0)
        return completed
    }

    private fun mark(start: Long, end: Long, evidence: AttentionSignal) {
        if (end <= start) return
        val first = (start / BIN_MS).toInt()
        val last = ((end - 1) / BIN_MS).toInt()
        if (last >= MAX_BINS) return
        if (masks.size <= last) masks = masks.copyOf(last + 1)
        val bit = when (evidence) {
            AttentionSignal.Watching -> 1
            AttentionSignal.Away -> 2
            AttentionSignal.EyesClosed -> 4
            AttentionSignal.Unknown -> 8
        }
        for (index in first..last) masks[index] = (masks[index].toInt() or bit).toByte()
        dirty = true
    }

    private fun drain(): AttentionTraceChunk? {
        val identity = key ?: return null
        if (!dirty) return null
        val result = AttentionTraceChunk(identity, incognito, masks.copyOf())
        masks.fill(0)
        dirty = false
        return result
    }

    companion object {
        const val BIN_MS = 5_000L
        const val MAX_POSITION_MS = 24 * 60 * 60 * 1_000L
        const val MAX_BINS = (MAX_POSITION_MS / BIN_MS).toInt()
    }
}

/** Versioned, bounded portable representation; zero means unobserved, not absence of a gaze. */
object AttentionTraceCodec {
    private const val PREFIX = "1:5000:"
    fun encode(masks: ByteArray): String {
        require(masks.size <= AttentionTimeline.MAX_BINS)
        require(masks.all { it.toInt() in 0..15 })
        return PREFIX + masks.joinToString("") { it.toInt().toString(16) }.trimEnd('0')
    }

    fun decode(value: String): ByteArray? {
        if (!value.startsWith(PREFIX) || value.length > PREFIX.length + AttentionTimeline.MAX_BINS) return null
        val encoded = value.substring(PREFIX.length)
        if (encoded.any { it !in "0123456789abcdef" }) return null
        return ByteArray(encoded.length) { encoded[it].digitToInt(16).toByte() }
    }

    fun merge(first: ByteArray, second: ByteArray): ByteArray {
        require(maxOf(first.size, second.size) <= AttentionTimeline.MAX_BINS)
        return ByteArray(maxOf(first.size, second.size)) {
            ((first.getOrNull(it)?.toInt() ?: 0) or (second.getOrNull(it)?.toInt() ?: 0)).toByte()
        }
    }
}
