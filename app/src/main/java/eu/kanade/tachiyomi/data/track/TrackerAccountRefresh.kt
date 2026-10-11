package eu.kanade.tachiyomi.data.track

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Optional account metadata refresh; logout and a new identity invalidate an in-flight result. */
internal class TrackerAccountRefresh(private val identity: () -> String?) {
    private val mutex = Mutex()
    private val guard = Any()
    private var generation = 0L
    private val refreshing = MutableStateFlow(false)
    val state = refreshing.asStateFlow()

    fun invalidate(action: () -> Unit) = synchronized(guard) {
        generation++
        action()
    }

    suspend fun <T> execute(fetch: suspend (String) -> T, apply: (T) -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        refreshing.value = true
        try {
            val (revision, account) = synchronized(guard) { generation to identity() }
            if (account == null) return false
            val result = fetch(account)
            synchronized(guard) {
                if (generation != revision || identity() != account) return false
                apply(result)
            }
            return true
        } finally {
            refreshing.value = false
            mutex.unlock()
        }
    }
}

data class TrackerAccountSettings(val displayName: String, val scoreFormat: String? = null)

internal fun trackerSearchId(query: String): Long? {
    val value = query.trim()
    if (!value.startsWith("id:", ignoreCase = true)) return null
    return value.substring(3).trim().toLongOrNull()?.takeIf { it > 0 }
}
