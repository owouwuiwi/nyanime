package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Public metadata lookup; it does not access a user's tracker account or publish viewing history. */
internal object AniListMediaLookup {
    enum class Type { ANIME, MANGA }

    data class Match(val id: Long, val malId: Long?, val titles: List<String>, val episodes: Int?)
    data class MangaRelation(
        val id: Long,
        val malId: Long?,
        val titles: List<String>,
        val format: String?,
        val viaOriginalNovel: Boolean = false,
    )

    data class AdaptationContext(val season: Int?, val standaloneSeason: Boolean)
    data class MangaAdaptations(val relations: List<MangaRelation>, val context: AdaptationContext)

    data class AnimeRelation(
        val id: Long,
        val malId: Long?,
        val title: String,
        val format: String?,
        val episodes: Int?,
        val year: Int?,
        val coverUrl: String?,
        val viaOriginalNovel: Boolean = false,
        val context: AdaptationContext = AdaptationContext(null, false),
        val titles: List<String> = listOf(title),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val seasonPattern =
        Regex(
            "(?i)\\b(?:season|stagione|saison|staffel|temporada|сезон)\\s*(\\d{1,2})\\b|" +
                "\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b|(?:第|ภาค\\s*)(\\d{1,2})(?:期|\\b)",
        )
    private val partPattern = Regex("(?i)\\bpart\\s*[2-9]\\b")
    private val cacheGates = List(16) { Mutex() }
    private data class CacheEntry(val expiresAt: Long, val match: Match?)
    private val cache = LinkedHashMap<Pair<Type, String>, CacheEntry>(128, 0.75f, true)
    private val relationCache = LinkedHashMap<Long, Pair<Long, MangaAdaptations>>(64, 0.75f, true)
    private val animeRelationGates = List(8) { Mutex() }
    private val animeRelationCache = LinkedHashMap<Long, Pair<Long, List<AnimeRelation>>>(64, 0.75f, true)
    private val relationClient = client.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun animeAdaptations(mangaId: Long): List<AnimeRelation> = animeRelationGates[
        (
            mangaId.hashCode() and
                Int.MAX_VALUE
            ) %
            animeRelationGates.size,
    ].withLock {
        if (mangaId !in 1..Int.MAX_VALUE.toLong()) return@withLock emptyList()
        synchronized(animeRelationCache) {
            animeRelationCache[mangaId]
        }?.takeIf { it.first > System.currentTimeMillis() }?.let {
            return@withLock it.second
        }
        val edges = fetchAnimeRelationEdges(mangaId)
        val direct = animeRelations(edges, viaOriginalNovel = false)
        val result = if (direct.isNotEmpty()) {
            direct
        } else {
            edges.asSequence()
                .filter { it.relationType == "ADAPTATION" && it.node?.format == "NOVEL" }
                .mapNotNull { it.node?.id }
                .distinct().take(2).toList()
                .flatMap { animeRelations(fetchAnimeRelationEdges(it), viaOriginalNovel = true) }
        }.distinctBy { it.id }.sortedWith(
            compareByDescending<AnimeRelation> { it.format == "TV" || it.format == "TV_SHORT" }
                .thenBy { it.year ?: Int.MAX_VALUE }.thenBy { it.id },
        )
        synchronized(animeRelationCache) {
            animeRelationCache[mangaId] = (System.currentTimeMillis() + 24 * 60 * 60 * 1000L) to result
            if (animeRelationCache.size > 64) animeRelationCache.remove(animeRelationCache.keys.first())
        }
        result
    }

    private suspend fun fetchAnimeRelationEdges(mangaId: Long): List<RelationEdge> = withContext(Dispatchers.IO) {
        val query = """
            query AnimeAdaptations(${'$'}id: Int) {
              Media(id: ${'$'}id, type: MANGA) {
                relations {
                  edges { relationType node {
                    id idMal type format episodes seasonYear coverImage { large }
                    title { romaji english native } synonyms
                    relations { edges { relationType node { id type format } } }
                  } }
                }
              }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", query)
            putJsonObject("variables") { put("id", mangaId) }
        }.toString().toRequestBody(jsonMime)
        relationClient.newCall(POST("https://graphql.anilist.co", body = body)).awaitSuccess().use { response ->
            requireNotNull(json.decodeFromString<RelationResponse>(response.body.string()).data?.media) {
                "Catalog relationship metadata unavailable"
            }.relations?.edges.orEmpty()
        }
    }

    internal fun parseAnimeRelations(raw: String, viaOriginalNovel: Boolean = false): List<AnimeRelation> =
        animeRelations(
            json.decodeFromString<RelationResponse>(raw).data?.media?.relations?.edges.orEmpty(),
            viaOriginalNovel,
        )

    private fun animeRelations(edges: List<RelationEdge>, viaOriginalNovel: Boolean): List<AnimeRelation> =
        edges.mapNotNull { edge ->
            val node = edge.node ?: return@mapNotNull null
            if (edge.relationType != "ADAPTATION" || node.type != "ANIME" || node.format == "MUSIC" || node.id <= 0) {
                return@mapNotNull null
            }
            val title = node.title?.english?.takeIf { it.isNotBlank() } ?: node.names.firstOrNull()
                ?: return@mapNotNull null
            AnimeRelation(
                node.id,
                node.idMal?.takeIf { it > 0 },
                title,
                node.format,
                node.episodes?.takeIf { it > 0 },
                node.seasonYear,
                node.coverImage?.large,
                viaOriginalNovel,
                adaptationContext(node),
                node.names,
            )
        }.distinctBy { it.id }

    /** Only catalog IDs are sent. A title match never establishes an adaptation relationship. */
    suspend fun mangaAdaptations(animeId: Long): MangaAdaptations =
        cacheGates[(animeId.hashCode() and Int.MAX_VALUE) % cacheGates.size].withLock {
            if (animeId !in 1..Int.MAX_VALUE.toLong()) {
                return@withLock MangaAdaptations(emptyList(), AdaptationContext(null, false))
            }
            synchronized(relationCache) {
                relationCache[animeId]
            }?.takeIf { it.first > System.currentTimeMillis() }?.let { return@withLock it.second }
            val relations = fetchMangaAdaptations(animeId)
            synchronized(relationCache) {
                relationCache[animeId] = (System.currentTimeMillis() + 24 * 60 * 60 * 1000L) to relations
                if (relationCache.size > 64) relationCache.remove(relationCache.keys.first())
            }
            relations
        }

    private suspend fun fetchMangaAdaptations(animeId: Long): MangaAdaptations = withContext(Dispatchers.IO) {
        val query = """
            query Adaptations(${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id type format title { romaji english native } synonyms
                relations {
                  edges { relationType node { id idMal type format title { romaji english native } synonyms } }
                }
              }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", query)
            putJsonObject("variables") { put("id", animeId) }
        }.toString().toRequestBody(jsonMime)
        val media = client.newCall(POST("https://graphql.anilist.co", body = body)).awaitSuccess().use { response ->
            requireNotNull(json.decodeFromString<RelationResponse>(response.body.string()).data?.media) {
                "Catalog relationship metadata unavailable"
            }
        }
        val edges = media.relations?.edges.orEmpty()
        val context = adaptationContext(media)
        val direct = edges.mapNotNull { edge ->
            val node = edge.node ?: return@mapNotNull null
            if (edge.relationType != "ADAPTATION" || node.type != "MANGA" || node.format == "NOVEL") {
                return@mapNotNull null
            }
            MangaRelation(node.id, node.idMal, node.names, node.format)
        }.distinctBy { it.id }
        if (direct.isNotEmpty()) return@withContext MangaAdaptations(direct, context)

        // Some anime adapt a novel that has its own manga adaptations. Keep that relationship indirect.
        val novelIds = edges.asSequence()
            .filter { it.relationType == "ADAPTATION" && it.node?.format == "NOVEL" }
            .mapNotNull { it.node?.id }
            .distinct()
            .take(2)
            .toList()
        val indirect = mutableListOf<MangaRelation>()
        for (novelId in novelIds) indirect += fetchNovelMangaAdaptations(novelId)
        MangaAdaptations(indirect.distinctBy { it.id }, context)
    }

    internal fun parseAdaptationContext(raw: String): AdaptationContext =
        adaptationContext(requireNotNull(json.decodeFromString<RelationResponse>(raw).data?.media))

    private fun adaptationContext(media: Media): AdaptationContext {
        val serial = media.format in setOf("TV", "TV_SHORT")
        val edges = media.relations?.edges.orEmpty()
        val previousSeason = edges.any {
            it.relationType == "PREQUEL" && it.node?.type == "ANIME" && it.node.format in setOf("TV", "TV_SHORT")
        }
        val nextSeason = edges.any {
            it.relationType == "SEQUEL" && it.node?.type == "ANIME" && it.node.format in setOf("TV", "TV_SHORT")
        }
        // Broadcast quarters and release years are not season ordinals.
        val explicit = seasonOrdinal(media.names)
        val partOnly = media.names.any { partPattern.containsMatchIn(it) }
        val conflictingSeasons = explicit == null && media.names.any { seasonPattern.containsMatchIn(it) }
        val season = explicit ?: if (serial && !previousSeason && !partOnly && !conflictingSeasons) 1 else null
        return AdaptationContext(season, serial && !previousSeason && !nextSeason && season == 1)
    }

    internal fun seasonOrdinal(titles: List<String>): Int? {
        return titles.flatMap { title ->
            seasonPattern.findAll(title).mapNotNull { match ->
                (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value)
                    ?.toIntOrNull()?.takeIf { it > 0 }
            }.toList()
        }.distinct().singleOrNull()
    }

    private suspend fun fetchNovelMangaAdaptations(novelId: Long): List<MangaRelation> {
        val query = """
            query NovelAdaptations(${'$'}id: Int) {
              Media(id: ${'$'}id, type: MANGA) {
                relations {
                  edges { relationType node { id idMal type format title { romaji english native } synonyms } }
                }
              }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", query)
            putJsonObject("variables") { put("id", novelId) }
        }.toString().toRequestBody(jsonMime)
        return client.newCall(POST("https://graphql.anilist.co", body = body)).awaitSuccess().use { response ->
            json.decodeFromString<RelationResponse>(response.body.string())
                .data?.media?.relations?.edges.orEmpty()
                .mapNotNull { edge ->
                    val node = edge.node ?: return@mapNotNull null
                    if (edge.relationType != "ADAPTATION" || node.type != "MANGA" || node.format == "NOVEL") {
                        return@mapNotNull null
                    }
                    MangaRelation(node.id, node.idMal, node.names, node.format, viaOriginalNovel = true)
                }
        }
    }

    /** Fallback only for an exact, unique catalog alias; edition numbers remain significant. */
    suspend fun resolveExact(title: String, type: Type): Match? {
        val normalized = TrackTitleMatcher.normalize(title)
        if (normalized.length !in 3..256) return null
        return cached(type to "continuity:$normalized") {
            fetch(normalized, type)?.takeIf {
                it.titles.any { alias -> TrackTitleMatcher.normalize(alias) == normalized }
            }
        }
    }

    suspend fun resolve(title: String, type: Type): Match? = cached(type to TrackTitleMatcher.normalize(title)) {
        fetch(title, type)
    }

    suspend fun resolveId(id: Long, type: Type, malId: Boolean): Match? {
        if (id !in 1..Int.MAX_VALUE.toLong()) return null
        return cached(type to "${if (malId) "mal" else "al"}:$id") { fetchId(id, type, malId) }
    }

    private suspend fun cached(key: Pair<Type, String>, fetch: suspend () -> Match?): Match? =
        cacheGates[(key.hashCode() and Int.MAX_VALUE) % cacheGates.size].withLock {
            synchronized(cache) { cache[key] }?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
                return@withLock it.match
            }
            val match = fetch()
            synchronized(cache) {
                cache[key] = CacheEntry(System.currentTimeMillis() + 60 * 60 * 1000L, match)
                if (cache.size > 128) cache.remove(cache.keys.first())
            }
            match
        }

    private suspend fun fetch(title: String, type: Type): Match? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val query = """
            query Search(${'$'}title: String) {
              Page(perPage: 50) {
                pageInfo { hasNextPage }
                media(search: ${'$'}title, type: ${type.name}) {
                  id idMal episodes title { romaji english native } synonyms
                }
              }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", query)
            putJsonObject("variables") { put("title", title) }
        }.toString().toRequestBody(jsonMime)
        val response = client.newCall(POST("https://graphql.anilist.co", body = body)).awaitSuccess().use {
            json.decodeFromString<SearchResponse>(it.body.string())
        }
        val page = response.data?.page ?: return@withContext null
        if (page.pageInfo?.hasNextPage == true) return@withContext null
        val media = TrackTitleMatcher.choose(title, page.media, { item -> item.names }, { it.id })
            ?: return@withContext null
        media.toMatch()
    }

    private suspend fun fetchId(id: Long, type: Type, malId: Boolean): Match? = withContext(Dispatchers.IO) {
        val field = if (malId) "idMal" else "id"
        val query = """
            query Lookup(${'$'}id: Int) {
              Media($field: ${'$'}id, type: ${type.name}) {
                id idMal episodes title { romaji english native } synonyms
              }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", query)
            putJsonObject("variables") { put("id", id) }
        }.toString().toRequestBody(jsonMime)
        client.newCall(POST("https://graphql.anilist.co", body = body)).awaitSuccess().use {
            json.decodeFromString<IdResponse>(it.body.string()).data?.media?.toMatch()
        }
    }

    private fun Media.toMatch() = Match(id, idMal?.takeIf { it > 0 }, names, episodes?.takeIf { it > 0 })

    @Serializable
    private data class SearchResponse(val data: SearchData? = null)

    @Serializable
    private data class SearchData(@kotlinx.serialization.SerialName("Page") val page: SearchPage? = null)

    @Serializable
    private data class IdResponse(val data: IdData? = null)

    @Serializable
    private data class IdData(@kotlinx.serialization.SerialName("Media") val media: Media? = null)

    @Serializable
    private data class RelationResponse(val data: RelationData? = null)

    @Serializable
    private data class RelationData(@kotlinx.serialization.SerialName("Media") val media: Media? = null)

    @Serializable
    private data class RelationConnection(val edges: List<RelationEdge> = emptyList())

    @Serializable
    private data class RelationEdge(val relationType: String? = null, val node: Media? = null)

    @Serializable
    private data class SearchPage(
        val pageInfo: PageInfo? = null,
        val media: List<Media> = emptyList(),
    )

    @Serializable
    private data class PageInfo(val hasNextPage: Boolean = false)

    @Serializable
    private data class Media(
        val id: Long = 0,
        val idMal: Long? = null,
        val episodes: Int? = null,
        val type: String? = null,
        val format: String? = null,
        val title: MediaTitles? = null,
        val synonyms: List<String> = emptyList(),
        val seasonYear: Int? = null,
        val coverImage: CoverImage? = null,
        val relations: RelationConnection? = null,
    ) {
        val names: List<String> get() = listOfNotNull(title?.romaji, title?.english, title?.native) + synonyms
    }

    @Serializable
    private data class CoverImage(val large: String? = null)

    @Serializable
    private data class MediaTitles(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
    )
}
