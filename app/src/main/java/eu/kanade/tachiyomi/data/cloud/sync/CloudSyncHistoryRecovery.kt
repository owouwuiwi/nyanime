package eu.kanade.tachiyomi.data.cloud.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import nyanime.telegram.sync.SyncRef

internal data class RecoveredSyncItem(val url: String, val metadata: PortableItem)

/** Repairs old orphaned history using exact identities, never title/number guesses or video requests. */
internal class CloudSyncHistoryRecovery(
    private val now: () -> Long,
    private val retryAt: (String) -> Long,
    private val postpone: (String, Long) -> Unit,
    private val allowed: () -> Boolean,
) {
    private data class Cached(val until: Long, val items: List<RecoveredSyncItem>)
    private val cache = linkedMapOf<String, Cached>()
    private var requested = false

    fun beginPass() {
        requested = false
    }

    suspend fun recover(ref: SyncRef, fetch: suspend () -> List<RecoveredSyncItem>): RecoveredSyncItem? {
        require(ref.kind in setOf("manga-history", "anime-history"))
        if (!allowed()) return null
        val key = "history-dependency/" + ref.copy(kind = ref.kind.substringBefore('-') + "-title", item = "").key()
        val time = now()
        cache.entries.removeAll { it.value.until <= time }
        val cached = cache[key]
        if (cached != null) return match(cached.items, ref.item)
        if (requested || time < retryAt(key)) return null
        requested = true
        // Persist the backoff before contacting a provider, including process interruption.
        postpone(key, time + 10 * 60_000)
        val values = try {
            withTimeoutOrNull(15_000) { fetch() } ?: return null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return null
        }
        if (!allowed()) return null
        if (values.size <= 4096) {
            while (cache.isNotEmpty() &&
                (cache.size >= 8 || cache.values.sumOf { it.items.size } + values.size > 4096)
            ) {
                cache.remove(cache.keys.first())
            }
            cache[key] = Cached(time + 10 * 60_000, values)
        }
        return match(values, ref.item)
    }

    private fun match(values: List<RecoveredSyncItem>, url: String): RecoveredSyncItem? =
        values.singleOrNull { it.url == url }?.takeIf { runCatching { it.metadata.validate() }.isSuccess }
}
