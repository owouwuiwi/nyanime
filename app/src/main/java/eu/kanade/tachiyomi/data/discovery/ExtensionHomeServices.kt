package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.sync.Semaphore
import tachiyomi.data.discovery.CachedSourceHomeRepository
import tachiyomi.data.discovery.MergedSourceHomeRepository
import tachiyomi.domain.discovery.SourceHomeCache
import tachiyomi.domain.discovery.SourceHomeGateway
import tachiyomi.domain.discovery.SourceHomeRepository
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.source.anime.service.AnimeSourceManager

/** Bound each route to one capability; share a concurrency budget and a bounded per-home memory cache. */
class ExtensionHomeServices(
    private val registry: ExtensionHomeRegistry,
    private val manager: AnimeSourceManager,
    private val toLocal: NetworkToLocalAnime,
    private val cache: SourceHomeCache,
) {
    data class Bound(val gateway: SourceHomeGateway, val repository: SourceHomeRepository)
    private val requests = Semaphore(3)
    private val services = LinkedHashMap<String, Bound>()
    val merged = MergedSourceHomeRepository({ forHome(it.key).repository }, registry::groupAccess)
    fun observeGroup(id: String) = registry.observeGroup(id)

    @Synchronized
    fun forHome(key: String): Bound {
        services.remove(key)?.let { existing ->
            services[key] = existing
            return existing
        }
        val gateway = ExtensionHomeGateway(key, registry, manager, toLocal, requests)
        val bound = Bound(gateway, CachedSourceHomeRepository(gateway, capacity = 16, persistent = cache))
        services[key] = bound
        while (services.size > 8) services.remove(services.keys.first())
        return bound
    }
}
