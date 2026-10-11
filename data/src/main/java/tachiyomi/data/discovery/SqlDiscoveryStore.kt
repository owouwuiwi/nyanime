package tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.discovery.AnimeCatalogCache
import tachiyomi.domain.discovery.AnimeSourceLink
import tachiyomi.domain.discovery.AnimeSourceLinkRepository
import tachiyomi.domain.discovery.CatalogCacheEntry
import tachiyomi.domain.discovery.CatalogId

class SqlDiscoveryStore(private val database: DiscoveryDatabase) : AnimeCatalogCache, AnimeSourceLinkRepository {
    override suspend fun read(key: String): CatalogCacheEntry? = withContext(Dispatchers.IO) {
        database.discoveryQueries.findCache(key).executeAsOneOrNull()?.let {
            CatalogCacheEntry(it.payload, it.fetched_at)
        }
    }

    override suspend fun write(key: String, entry: CatalogCacheEntry, detail: Boolean) = withContext(Dispatchers.IO) {
        database.transaction {
            database.discoveryQueries.putCache(key, entry.payload, entry.fetchedAt, if (detail) 1L else 0L)
            database.discoveryQueries.trimDetails()
            database.discoveryQueries.trimFeeds()
        }
    }

    override suspend fun find(id: CatalogId): AnimeSourceLink? = withContext(Dispatchers.IO) {
        database.discoveryQueries.findLink(id.provider, id.value).executeAsOneOrNull()?.let {
            AnimeSourceLink(id, it.source_id, it.source_url)
        }
    }

    override suspend fun save(link: AnimeSourceLink) = withContext(Dispatchers.IO) {
        database.discoveryQueries.putLink(link.catalogId.provider, link.catalogId.value, link.sourceId, link.sourceUrl)
    }

    override suspend fun remove(id: CatalogId) = withContext(Dispatchers.IO) {
        database.discoveryQueries.removeLink(id.provider, id.value)
    }
}
