package eu.kanade.tachiyomi.extension

/** One complete scan per burst. A failed scan is never interpreted as an empty inventory. */
internal class ExtensionInventoryCache<T>(private val clock: () -> Long, initial: List<T>? = null) {
    data class Snapshot<T>(val items: List<T>, val unavailable: Boolean)
    private var saved = initial
    private var nextCheck = Long.MIN_VALUE
    private var failures = 0

    @Synchronized
    fun read(scan: () -> List<T>): Snapshot<T> {
        val now = clock()
        if (now < nextCheck) return Snapshot(saved.orEmpty(), failures > 0)
        return try {
            val complete = scan().toList()
            saved = complete
            failures = 0
            nextCheck = now + 10_000
            Snapshot(complete, false)
        } catch (_: RuntimeException) {
            failures = (failures + 1).coerceAtMost(6)
            nextCheck = now + (1_000L shl (failures - 1))
            Snapshot(saved.orEmpty(), true)
        }
    }

    @Synchronized
    fun invalidate() {
        nextCheck = Long.MIN_VALUE
    }
}
