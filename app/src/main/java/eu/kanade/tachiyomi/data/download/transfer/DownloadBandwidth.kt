package eu.kanade.tachiyomi.data.download.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Throttler
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One optional user speed limit for both queues, regardless of connection count. */
object DownloadBandwidth {
    val throttler: Throttler by lazy {
        val preference = Injekt.get<DownloadPreferences>().downloadSpeedLimit()
        Throttler().also { value ->
            value.bytesPerSecond(preference.get().coerceAtLeast(0).toLong() * 1024)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                preference.changes().collect { value.bytesPerSecond(it.coerceAtLeast(0).toLong() * 1024) }
            }
        }
    }
}
