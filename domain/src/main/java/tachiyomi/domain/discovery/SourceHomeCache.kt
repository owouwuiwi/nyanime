package tachiyomi.domain.discovery

/** Only public, unqueried feed pages may use this cache. Source links and library state are separate. */
data class SourceHomeCacheKey(
    val home: String,
    val source: Long,
    val revision: String,
    val section: String,
    val page: Int,
)
data class SourceHomeCacheEntry(val page: SourceHomePage, val fetchedAt: Long)

interface SourceHomeCache {
    suspend fun read(key: SourceHomeCacheKey): SourceHomeCacheEntry?
    suspend fun write(key: SourceHomeCacheKey, entry: SourceHomeCacheEntry)
}
