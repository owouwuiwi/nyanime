package eu.kanade.tachiyomi.data.download.anime.ultra

/** Shared process-wide by controllers; unsupported sensors must also respect the sampling interval. */
internal class UltraThermalHeadroomCache {
    private var lastReadAt = -10_000L
    private var cached: Float? = null

    @Synchronized
    fun sample(nowMs: Long, read: () -> Float): Float? {
        if (nowMs - lastReadAt >= 10_000) {
            cached = read().takeIf { it.isFinite() }
            lastReadAt = nowMs
        }
        return cached
    }
}
