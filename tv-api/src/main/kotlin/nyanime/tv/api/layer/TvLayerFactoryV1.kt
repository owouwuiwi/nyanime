package nyanime.tv.api.layer

import nyanime.tv.api.TvCatalogue
import nyanime.tv.api.TvCatalogueRequest
import nyanime.tv.api.TvChannelLookup
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvGuide
import nyanime.tv.api.TvGuideRequest
import nyanime.tv.api.TvHttpClient
import nyanime.tv.api.TvPlayback
import nyanime.tv.api.TvSource
import nyanime.tv.api.TvSourceCache

/** Attach to an authenticated original source without starting any network calls. */
interface TvLayerFactoryV1 {
    fun attach(source: TvSource, http: TvHttpClient, cache: TvSourceCache): TvLayerV1
}

/** Return null to use the selected source's original operation. Preserve all channel references. */
interface TvLayerV1 {
    suspend fun catalogue(request: TvCatalogueRequest): TvCatalogue? = null
    suspend fun channels(references: List<TvChannelRef>): TvChannelLookup? = null
    suspend fun programmes(request: TvGuideRequest): TvGuide? = null
    suspend fun resolve(channel: TvChannelRef): TvPlayback? = null
}
