package nyanime.tv.api

import kotlinx.serialization.Serializable

/** TV ABI v1. Only a trusted extension interprets channel references and remote data. */
interface TvSourceFactory {
    fun create(http: TvHttpClient, cache: TvSourceCache): List<TvSource>
}

interface TvSource {
    val id: Long
    val name: String
    val language: String
    val capabilities: TvCapabilities
    suspend fun catalogue(request: TvCatalogueRequest): TvCatalogue

    /** Optional metadata lookup. It must not probe or start playable streams. */
    suspend fun channels(references: List<TvChannelRef>): TvChannelLookup =
        TvChannelLookup(emptyList(), unavailable = references.toSet())
    suspend fun programmes(request: TvGuideRequest): TvGuide
    suspend fun resolve(channel: TvChannelRef): TvPlayback
}

/** Networking and cancellation belong to the host; parsing belongs to the extension. */
fun interface TvHttpClient {
    suspend fun get(url: String, headers: Map<String, String>): TvHttpResponse
}

data class TvHttpResponse(
    val body: String,
    val finalUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val code: Int = 200,
)

/** Private, bounded extension cache. It never forms part of a public link or a backup. */
interface TvSourceCache {
    suspend fun get(key: String): TvCacheEntry?
    suspend fun put(key: String, entry: TvCacheEntry)
}

@Serializable
data class TvCacheEntry(
    val content: String,
    val savedAt: Long,
    val etag: String? = null,
    val lastModified: String? = null,
)

@Serializable
data class TvCapabilities(
    val homeTitle: Map<String, String>,
    val initialCountry: String? = null,
    val guide: Boolean = false,
    val countries: List<TvFacet> = emptyList(),
    val languages: List<TvFacet> = emptyList(),
    val categories: List<TvFacet> = emptyList(),
    val attribution: String? = null,
    val channelLookup: Boolean = false,
)

@Serializable
data class TvFacet(val id: String, val labels: Map<String, String>) {
    fun label(language: String): String = labels[language] ?: labels["en"] ?: id
}

@Serializable
data class TvChannelRef(val sourceId: Long, val id: String)

@Serializable
data class TvChannel(
    val ref: TvChannelRef,
    val name: String,
    val logoUrl: String? = null,
    val country: String? = null,
    val languages: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val feedName: String? = null,
    val adult: Boolean = false,
    val guide: TvGuideAvailability = TvGuideAvailability.UNKNOWN,
    val availability: Set<TvAvailability> = emptySet(),
)

@Serializable
enum class TvAvailability { GEO_RESTRICTED, INTERMITTENT }

@Serializable
enum class TvGuideAvailability { UNKNOWN, AVAILABLE, NONE }

@Serializable
data class TvCatalogueRequest(
    val country: String? = null,
    val language: String? = null,
    val category: String? = null,
    val cursor: String? = null,
    val refresh: Boolean = false,
)

@Serializable
data class TvHomeSection(
    val id: String,
    val labels: Map<String, String>,
    val channels: List<TvChannelRef>,
    val category: String? = null,
)

@Serializable
data class TvCatalogue(
    val channels: List<TvChannel>,
    val nextCursor: String? = null,
    val sections: List<TvHomeSection> = emptyList(),
    /** Discovered facets travel with the cached catalogue, including across app restarts. */
    val options: TvCapabilities? = null,
)

/** Used for personal channels outside the currently selected catalogue. */
@Serializable
data class TvChannelLookup(
    val channels: List<TvChannel>,
    val unavailable: Set<TvChannelRef> = emptySet(),
    val failed: Set<TvChannelRef> = emptySet(),
)

@Serializable
data class TvGuideRequest(val channels: List<TvChannelRef>, val startUtc: Long, val endUtc: Long)

@Serializable
data class TvProgramme(
    val id: String,
    val channel: TvChannelRef,
    val title: String,
    val startUtc: Long,
    val endUtc: Long,
    val description: String? = null,
    val imageUrl: String? = null,
)

/** Missing guide data and a failed request are different states, even in a partial response. */
@Serializable
data class TvGuide(
    val programmes: List<TvProgramme>,
    val unavailable: Set<TvChannelRef> = emptySet(),
    val failed: Set<TvChannelRef> = emptySet(),
    val availableFromUtc: Long? = null,
    val availableUntilUtc: Long? = null,
)

@Serializable
data class TvTrack(val url: String, val label: String)

@Serializable
data class TvStream(
    val id: String,
    val url: String,
    val label: String,
    val headers: Map<String, String> = emptyMap(),
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val audio: List<TvTrack> = emptyList(),
    val subtitles: List<TvTrack> = emptyList(),
    val mimeType: String? = null,
    val rewind: Boolean = false,
    val adjustableSpeed: Boolean = false,
    /** Only supplied when the extension can verify the media-position/UTC mapping. */
    val clock: TvClock? = null,
)

@Serializable
data class TvClock(val mediaPositionSeconds: Double, val utcMillis: Long)

@Serializable
data class TvPlayback(val channel: TvChannel, val streams: List<TvStream>)
