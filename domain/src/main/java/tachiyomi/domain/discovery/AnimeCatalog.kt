package tachiyomi.domain.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

@Serializable
data class CatalogId(val provider: String = "anilist", val value: Long)

@Serializable
data class CatalogAnime(
    val id: CatalogId,
    val title: String,
    val alternateTitles: List<String> = emptyList(),
    val malId: Long? = null,
    val cover: String? = null,
    val banner: String? = null,
    val synopsis: String? = null,
    val score: Int? = null,
    val genres: List<String> = emptyList(),
    val format: String? = null,
    val status: String? = null,
    val season: String? = null,
    val year: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val studios: List<String> = emptyList(),
    val airingAt: Long? = null,
    val airingEpisode: Int? = null,
    val relations: List<CatalogRelation> = emptyList(),
)

@Serializable
data class CatalogRelation(val id: CatalogId, val title: String, val cover: String?, val relationship: String)

@Serializable
enum class CatalogFeed { TRENDING, SEASON, TOP, NEXT_SEASON, WEEK, SEARCH }

@Serializable
data class CatalogRequest(
    val feed: CatalogFeed,
    val page: Int = 1,
    val query: String = "",
    val date: String = LocalDate.now().toString(),
    val provider: String? = null,
) {
    val cacheKey: String get() = "${feed.name}:$date:${query.trim()}:$page${provider?.let { ":$it" }.orEmpty()}"
}

@Serializable
data class CatalogPage(
    val items: List<CatalogAnime>,
    val hasNextPage: Boolean = false,
    val provider: String = "anilist",
    val notice: String? = null,
)

data class SectionState<T>(
    val data: T? = null,
    val loading: Boolean = true,
    val stale: Boolean = false,
    val error: String? = null,
    val failureReason: CatalogFailureReason? = null,
)

enum class CatalogFailureReason { SERVICE_UNAVAILABLE }

class CatalogServiceUnavailableException(message: String) : java.io.IOException(message)

interface AnimeCatalogRepository {
    fun observe(
        request: CatalogRequest,
        refresh: Boolean = false,
        offline: Boolean = false,
    ): Flow<SectionState<CatalogPage>>
    fun observeDetails(
        id: CatalogId,
        refresh: Boolean = false,
        offline: Boolean = false,
    ): Flow<SectionState<CatalogAnime>>
}

interface AnimeCatalogRemote {
    suspend fun fetch(request: CatalogRequest): CatalogPage
    suspend fun details(id: CatalogId): CatalogAnime
}

data class CatalogCacheEntry(val payload: String, val fetchedAt: Long)

interface AnimeCatalogCache {
    suspend fun read(key: String): CatalogCacheEntry?
    suspend fun write(key: String, entry: CatalogCacheEntry, detail: Boolean)
}

@Serializable
data class AnimeSourceLink(val catalogId: CatalogId, val sourceId: Long, val sourceUrl: String)

interface AnimeSourceLinkRepository {
    suspend fun find(id: CatalogId): AnimeSourceLink?
    suspend fun save(link: AnimeSourceLink)
    suspend fun remove(id: CatalogId)
}

interface HomeSectionProvider<T> {
    fun observe(): Flow<SectionState<List<T>>>
}

class CatalogCachePolicy(private val clock: Clock = Clock.systemUTC()) {
    fun now(): Long = clock.millis()

    fun isFresh(entry: CatalogCacheEntry, ttlMillis: Long): Boolean =
        now() - entry.fetchedAt in 0 until ttlMillis

    fun ttl(feed: CatalogFeed): Long = if (feed == CatalogFeed.WEEK) 15 * 60_000L else 30 * 60_000L

    companion object {
        const val DETAILS_TTL = 24 * 60 * 60_000L
    }
}

/** Only verified identifiers are evidence. Title similarity never chooses a version. */
object CatalogIdentityMatcher {
    fun uniqueMatch(candidates: List<Long>): Long? = candidates.distinct().singleOrNull()

    fun matches(anime: CatalogAnime, trackerId: Long, remoteId: Long): Boolean =
        (trackerId == 2L && anime.id.provider == "anilist" && remoteId == anime.id.value) ||
            (trackerId == 3L && anime.id.provider == "kitsu" && remoteId == anime.id.value) ||
            (trackerId == 1L && anime.malId != null && remoteId == anime.malId)
}

fun CatalogId.providerLabel(): String = when (provider) {
    "anilist" -> "AniList"
    "kitsu" -> "Kitsu"
    else -> provider
}
