package eu.kanade.tachiyomi.ui.reader.loader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Shared by adjacent chapters; prefetch cannot occupy the last visible-page slot. */
internal class ReaderLoadBudget(parallelism: Int = 3, backgroundLimit: Int = 2) {
    init {
        require(parallelism > backgroundLimit && backgroundLimit > 0)
    }

    private val total = Semaphore(parallelism)
    private val background = Semaphore(backgroundLimit)
    private val images = List(16) { Mutex() }

    /** Reused image URLs across pages/chapters must not race a disk-cache editor. */
    suspend fun <T> image(url: String, block: suspend () -> T): T =
        images[(url.hashCode() and Int.MAX_VALUE) % images.size].withLock { block() }

    suspend fun <T> load(foreground: Boolean, block: suspend () -> T): T = if (foreground) {
        total.withPermit { block() }
    } else {
        background.withPermit { total.withPermit { block() } }
    }
}
