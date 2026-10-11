package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.base.BasePreferences
import tachiyomi.domain.discovery.AnimeCatalogCache
import tachiyomi.domain.discovery.CatalogCacheEntry

class PrivateDiscoveryCache(
    private val delegate: AnimeCatalogCache,
    private val preferences: BasePreferences,
) : AnimeCatalogCache {
    override suspend fun read(key: String): CatalogCacheEntry? =
        if (preferences.incognitoMode().get()) null else delegate.read(key)

    override suspend fun write(key: String, entry: CatalogCacheEntry, detail: Boolean) {
        if (!preferences.incognitoMode().get()) delegate.write(key, entry, detail)
    }
}
