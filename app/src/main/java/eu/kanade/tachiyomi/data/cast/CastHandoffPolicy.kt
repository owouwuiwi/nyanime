package eu.kanade.tachiyomi.data.cast

object CastHandoffPolicy {
    fun startPosition(
        remotePositionMs: Long?,
        loadingEpisode: Boolean,
        savedPositionMs: Long,
        seen: Boolean,
        preserveSeenPosition: Boolean,
        localPositionMs: Long,
    ): Long = when {
        remotePositionMs != null -> remotePositionMs
        loadingEpisode && seen && !preserveSeenPosition -> 0
        loadingEpisode -> savedPositionMs
        else -> localPositionMs
    }.coerceAtLeast(0)

    fun observation(previous: CastPlayback, next: CastPlayback): CastPlayback {
        val duration = next.durationMs.takeIf { it > 0 } ?: previous.durationMs
        val position = when {
            next.finished && duration > 0 -> duration
            next.buffering -> previous.positionMs
            else -> next.positionMs.coerceAtLeast(0)
        }
        return next.copy(
            durationMs = duration,
            positionMs = if (duration > 0) position.coerceAtMost(duration) else position,
        )
    }
}
