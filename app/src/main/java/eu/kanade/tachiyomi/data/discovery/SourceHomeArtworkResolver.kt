package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.entries.anime.model.Anime
import java.io.IOException

/** Resolves incomplete Home artwork through the installed extension, without updating library data. */
class SourceHomeArtworkResolver(
    private val fetch: suspend (Anime) -> Artwork,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Artwork(val cover: String?, val background: String?)
    private data class Key(val source: Long, val url: String)
    private data class Cached(val artwork: Artwork, val expires: Long)
    private val cache = LinkedHashMap<Key, Cached>()
    private val locks = List(16) { Mutex() }
    private val requests = Semaphore(2)

    suspend fun resolve(anime: Anime, refresh: Boolean = false): Artwork = withContext(Dispatchers.IO) {
        val key = Key(anime.source, anime.url)
        require(key.url.isNotBlank()) { "No title address" }
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (!refresh) {
                synchronized(cache) { cache[key] }?.takeIf { it.expires > now() }?.let {
                    return@withContext it.artwork
                }
            }
            val artwork = requests.withPermit {
                try {
                    withTimeout(12_000) { fetch(anime) }
                } catch (error: TimeoutCancellationException) {
                    throw IOException("Artwork details timed out", error)
                }
            }.let { Artwork(it.cover?.takeIf(String::isNotBlank), it.background?.takeIf(String::isNotBlank)) }
            synchronized(cache) {
                cache.remove(key)
                // Empty results may recover on the source; successful URLs can reuse Coil's disk cache.
                val ttl = if (artwork.cover == null && artwork.background == null) 60_000L else 3_600_000L
                cache[key] = Cached(artwork, now() + ttl)
                while (cache.size > 128) cache.remove(cache.keys.first())
            }
            artwork
        }
    }
}
