package eu.kanade.tachiyomi.data.download.anime.ultra

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Also yields native remuxing when resource admission changes; cancellation reaches FFmpegKit. */
internal object UltraWorkMonitor {
    suspend fun <T> run(check: () -> Unit, work: suspend () -> T): T = coroutineScope {
        check()
        val pending = async { work() }
        while (!pending.isCompleted) {
            check()
            delay(500)
        }
        pending.await()
    }
}
