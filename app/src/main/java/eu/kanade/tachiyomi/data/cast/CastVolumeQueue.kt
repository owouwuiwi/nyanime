package eu.kanade.tachiyomi.data.cast

/** Main-thread mailbox: one command in flight and at most one replacement target. */
internal class CastVolumeQueue {
    data class Target(val generation: Long, val value: Float)
    private var desired: Target? = null
    private var pending = false
    val hasPending get() = pending

    fun set(generation: Long, value: Float) {
        desired = Target(generation, value.coerceIn(0f, 1f))
        pending = true
    }

    fun adjust(generation: Long, observed: Float, delta: Float) {
        set(generation, (desired?.takeIf { it.generation == generation }?.value ?: observed) + delta)
    }

    fun take(): Target? {
        if (!pending) return null
        pending = false
        return desired
    }

    fun isLatest(target: Target) = desired === target

    fun complete(target: Target) {
        if (desired === target && !pending) desired = null
    }
}
