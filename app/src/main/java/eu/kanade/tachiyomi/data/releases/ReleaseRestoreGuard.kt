package eu.kanade.tachiyomi.data.releases

import java.util.concurrent.atomic.AtomicInteger

/** Imported snapshots are not newly published episodes or chapters. */
object ReleaseRestoreGuard {
    private val imports = AtomicInteger()
    val active: Boolean get() = imports.get() > 0

    suspend fun <T> during(block: suspend () -> T): T {
        imports.incrementAndGet()
        return try {
            block()
        } finally {
            imports.decrementAndGet()
        }
    }
}
