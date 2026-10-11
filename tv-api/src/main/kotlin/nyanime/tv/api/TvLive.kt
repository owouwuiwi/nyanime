package nyanime.tv.api

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Public room data is a channel identity, never a playable URL or authentication. */
@Serializable
data class TvRoomSelection(val channel: TvChannelRef, val name: String, val revision: Long) {
    fun valid(): Boolean =
        TvRules.valid(channel) &&
            name.isNotBlank() &&
            name.length <= 240 &&
            name.none(Char::isISOControl) &&
            revision > 0
}

@Serializable
data class TvLiveReference(val selectionRevision: Long, val broadcastUtc: Long, val sentUtc: Long) {
    fun valid(): Boolean = selectionRevision > 0 && broadcastUtc > 0 && sentUtc > 0
}

data class TvLiveObservation(
    val positionSeconds: Double,
    val clock: TvClock?,
    val paused: Boolean,
    val buffering: Boolean,
    val canChangeSpeed: Boolean,
) {
    fun broadcastUtc(): Long? {
        val reference = clock ?: return null
        if (!positionSeconds.isFinite() ||
            !reference.mediaPositionSeconds.isFinite() ||
            reference.utcMillis <= 0
        ) {
            return null
        }
        val delta = (positionSeconds - reference.mediaPositionSeconds) * 1000.0
        if (!delta.isFinite() || abs(delta) > 7 * TvRules.DAY) return null
        return runCatching { Math.addExact(reference.utcMillis, delta.toLong()) }.getOrNull()?.takeIf { it > 0 }
    }
}

sealed interface TvLiveAdjustment {
    data object Unverified : TvLiveAdjustment
    data object LocalHold : TvLiveAdjustment
    data object AwaitingReference : TvLiveAdjustment
    data class Aligned(val driftMs: Long, val speed: Double = 1.0) : TvLiveAdjustment
    data class Gradual(val driftMs: Long, val speed: Double) : TvLiveAdjustment
    data class Limited(val driftMs: Long) : TvLiveAdjustment
}

/** Live rooms never wait for peers or propagate pause/buffering. Only verified clocks guide alignment. */
object TvLivePolicy {
    fun adjustment(
        local: TvLiveObservation,
        remote: TvLiveReference?,
        revision: Long,
        nowUtc: Long,
        hostClockOffsetMs: Long? = null,
    ): TvLiveAdjustment {
        if (local.paused || local.buffering) return TvLiveAdjustment.LocalHold
        val localUtc = local.broadcastUtc() ?: return TvLiveAdjustment.Unverified
        if (remote == null || !remote.valid() || remote.selectionRevision != revision || hostClockOffsetMs == null) {
            return TvLiveAdjustment.AwaitingReference
        }
        val age =
            runCatching { Math.subtractExact(nowUtc, Math.addExact(remote.sentUtc, hostClockOffsetMs)) }.getOrNull()
                ?: return TvLiveAdjustment.AwaitingReference
        if (age !in 0..8_000) return TvLiveAdjustment.AwaitingReference
        val drift = runCatching { Math.subtractExact(localUtc, Math.addExact(remote.broadcastUtc, age)) }.getOrNull()
            ?: return TvLiveAdjustment.Unverified
        if (abs(drift) <= 3000) return TvLiveAdjustment.Aligned(drift)
        if (!local.canChangeSpeed) return TvLiveAdjustment.Limited(drift)
        // Small proportional correction avoids seeks and repeated preparation interruptions.
        val correction = (drift / 180_000.0).coerceIn(-0.03, 0.03)
        return TvLiveAdjustment.Gradual(drift, (1.0 - correction).coerceIn(0.97, 1.03))
    }
}
