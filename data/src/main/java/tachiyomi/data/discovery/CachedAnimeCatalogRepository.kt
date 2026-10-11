package tachiyomi.data.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import tachiyomi.domain.discovery.AnimeCatalogCache
import tachiyomi.domain.discovery.AnimeCatalogRemote
import tachiyomi.domain.discovery.AnimeCatalogRepository
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogCacheEntry
import tachiyomi.domain.discovery.CatalogCachePolicy
import tachiyomi.domain.discovery.CatalogFailureReason
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogPage
import tachiyomi.domain.discovery.CatalogRequest
import tachiyomi.domain.discovery.CatalogServiceUnavailableException
import tachiyomi.domain.discovery.SectionState

class CachedAnimeCatalogRepository(
    private val remote: AnimeCatalogRemote,
    private val cache: AnimeCatalogCache,
    private val json: Json,
    private val policy: CatalogCachePolicy = CatalogCachePolicy(),
) : AnimeCatalogRepository {
    // Fixed stripes bound memory while serializing duplicate requests for the same key.
    private val locks = List(32) { Mutex() }

    override fun observe(request: CatalogRequest, refresh: Boolean, offline: Boolean) = observeCached(
        request.cacheKey,
        CatalogPage.serializer(),
        policy.ttl(request.feed),
        refresh,
        offline,
        false,
    ) { remote.fetch(request) }

    override fun observeDetails(id: CatalogId, refresh: Boolean, offline: Boolean) = observeCached(
        "detail:${id.provider}:${id.value}",
        CatalogAnime.serializer(),
        CatalogCachePolicy.DETAILS_TTL,
        refresh,
        offline,
        true,
    ) { remote.details(id) }

    private fun <T> observeCached(
        key: String,
        serializer: KSerializer<T>,
        ttl: Long,
        refresh: Boolean,
        offline: Boolean,
        detail: Boolean,
        fetch: suspend () -> T,
    ): Flow<SectionState<T>> = flow {
        val initial = cache.read(key)
        var value = initial?.let { runCatching { json.decodeFromString(serializer, it.payload) }.getOrNull() }
        val fresh = value != null && initial != null && policy.isFresh(initial, ttl)
        emit(SectionState(value, loading = !offline && (refresh || !fresh), stale = value != null && !fresh))
        if (offline) {
            emit(
                SectionState(
                    value,
                    loading = false,
                    stale = value != null && !fresh,
                    error = if (value ==
                        null
                    ) {
                        "Contenuto non disponibile offline"
                    } else {
                        null
                    },
                ),
            )
            return@flow
        }
        if (fresh && !refresh) return@flow
        try {
            val result = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                val current = cache.read(key)
                val reusable = current != null &&
                    policy.isFresh(current, ttl) &&
                    (!refresh || current != initial)
                val decoded = if (reusable) {
                    current?.let {
                        runCatching { json.decodeFromString(serializer, it.payload) }.getOrNull()
                    }
                } else {
                    null
                }
                decoded ?: fetch().also {
                    cache.write(key, CatalogCacheEntry(json.encodeToString(serializer, it), policy.now()), detail)
                }
            }
            value = result
            emit(SectionState(result, loading = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(
                SectionState(
                    value,
                    loading = false,
                    stale = value != null,
                    error =
                    e.message ?: "Catalogo temporaneamente non disponibile",
                    failureReason = if (e is CatalogServiceUnavailableException) {
                        CatalogFailureReason.SERVICE_UNAVAILABLE
                    } else {
                        null
                    },
                ),
            )
        }
    }.flowOn(Dispatchers.IO)
}
