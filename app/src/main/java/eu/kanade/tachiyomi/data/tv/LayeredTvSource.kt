package eu.kanade.tachiyomi.data.tv

import eu.kanade.tachiyomi.data.rin.RinArchive
import nyanime.tv.api.TvCacheEntry
import nyanime.tv.api.TvCatalogueRequest
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvGuideRequest
import nyanime.tv.api.TvSource
import nyanime.tv.api.TvSourceCache
import nyanime.tv.api.layer.TvLayerV1
import java.util.concurrent.atomic.AtomicBoolean

internal class TvLayerBinding(
    private val layer: TvLayerV1,
    val revision: String,
    private val failed: () -> Unit,
) {
    private val rejected = AtomicBoolean(false)
    val incompatible get() = rejected.get()

    suspend fun <T> await(block: suspend TvLayerV1.() -> T?): T? {
        if (incompatible) return null
        return try {
            layer.block()
        } catch (_: LinkageError) {
            if (rejected.compareAndSet(false, true)) failed()
            null
        }
    }
}

internal class LayeredTvSource(
    private val original: TvSource,
    private val bindings: Map<String, TvLayerBinding>,
) : TvSource {
    override val id get() = original.id
    override val name get() = original.name
    override val language get() = original.language
    override val capabilities get() = original.capabilities.let { base ->
        base.copy(
            channelLookup = base.channelLookup || bindings["catalog"]?.incompatible == false,
            guide = base.guide || bindings["guide"]?.incompatible == false,
        )
    }

    override suspend fun catalogue(request: TvCatalogueRequest) =
        bindings["catalog"]?.await { catalogue(request) } ?: original.catalogue(request)

    override suspend fun channels(references: List<TvChannelRef>) =
        bindings["catalog"]?.await { channels(references) } ?: original.channels(references)

    override suspend fun programmes(request: TvGuideRequest) =
        bindings["guide"]?.await { programmes(request) } ?: original.programmes(request)

    override suspend fun resolve(channel: TvChannelRef) =
        bindings["player"]?.await { resolve(channel) } ?: original.resolve(channel)
}

/** Keep layered responses separate from original responses while sharing the same disk quota. */
internal class TvLayerCache(private val cache: TvSourceCache, private val namespace: String) : TvSourceCache {
    override suspend fun get(key: String): TvCacheEntry? = cache.get(cacheKey(key))

    override suspend fun put(key: String, entry: TvCacheEntry) = cache.put(cacheKey(key), entry)

    private fun cacheKey(key: String): String {
        require(key.isNotEmpty() && key.length <= 2048)
        return "layer:" + RinArchive.sha256("$namespace:$key".toByteArray())
    }
}
