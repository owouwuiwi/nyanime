package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class AiringEvent(
    val entryId: Long,
    val episode: Int,
    val airingAt: Long,
    val catalogId: Long,
    val remindedAt: Long = 0,
    val variants: List<ScheduleBroadcast> = emptyList(),
    val scheduleStatus: String = "",
)
data class AiringCache(
    val verifiedAt: Long = 0,
    val attemptedAt: Long = 0,
    val status: String = "UNRESOLVED",
    val totalEpisodes: Int = 0,
    val finished: Boolean = false,
)

/** Public catalog metadata only. Source identification remains the extension's responsibility. */
class AiringRepository(private val db: AnimeDatabaseHandler = Injekt.get()) {
    fun effectiveEvents(): Flow<List<AiringEvent>> {
        val preferences = AnimeSchedulePreferences()
        return kotlinx.coroutines.flow.combine(
            events(),
            AnimeScheduleRepository().records(),
            preferences.enabled.changes(),
            preferences.connected.changes(),
            preferences.preferredType.changes(),
        ) { base, records, enabled, connected, type ->
            if (enabled && connected) {
                ScheduleParser.overlay(
                    base,
                    AnimeScheduleRepository().validRecords(records),
                    ScheduleAirType.entries.firstOrNull {
                        it.name == type
                    } ?: ScheduleAirType.SUB,
                    System.currentTimeMillis(),
                )
            } else {
                base
            }
        }
    }
    fun caches(): Flow<Map<Long, AiringCache>> = db.subscribeToList {
        airingQueries.getCaches { id, verified, attempted, status, total, finished ->
            id to AiringCache(verified, attempted, status, total.toInt(), finished != 0L)
        }
    }.map { it.toMap() }

    fun events(): Flow<List<AiringEvent>> = db.subscribeToList {
        airingQueries.getEvents { entry, episode, time, catalog, reminded ->
            AiringEvent(entry, episode.toInt(), time, catalog, reminded)
        }
    }

    suspend fun effectiveEntryEvents(id: Long): List<AiringEvent> {
        val base = entryEvents(id)
        val preferences = AnimeSchedulePreferences()
        if (!preferences.enabled.get() || !preferences.connected.get()) return base
        val records = AnimeScheduleRepository().record(id)?.let { listOf(it) }.orEmpty()
        return ScheduleParser.overlay(
            base,
            AnimeScheduleRepository().validRecords(records),
            preferences.type,
            System.currentTimeMillis(),
        )
    }

    suspend fun entryEvents(id: Long): List<AiringEvent> = db.awaitList {
        airingQueries.getEntryEvents(id) { entry, episode, time, catalog, reminded ->
            AiringEvent(entry, episode.toInt(), time, catalog, reminded)
        }
    }

    suspend fun cache(id: Long): AiringCache = db.awaitOneOrNull {
        airingQueries.getCache(id) { _, verified, attempted, status, total, finished ->
            AiringCache(verified, attempted, status, total.toInt(), finished != 0L)
        }
    } ?: AiringCache()

    suspend fun refresh(
        anime: Anime,
        force: Boolean = false,
    ): AiringCache = locks[anime.id.hashCode() and 63].withLock {
        val now = System.currentTimeMillis()
        val cached = cache(anime.id)
        val next = entryEvents(anime.id).firstOrNull { it.airingAt > now }
        if (!AiringRefreshPolicy.shouldRefresh(cached, now, next != null, force)) {
            return@withLock cached
        }
        try {
            val tracks = Injekt.get<AnimeTrackRepository>().getTracksByAnimeId(anime.id)
            val reference = AiringCatalogReference.from(anime, tracks)
            if (reference.anilistId == null && reference.malId == null) {
                // Preserve the existing calendar integration for a verified alternative tracker ID.
                val manager = Injekt.get<TrackerManager>()
                val legacy = tracks.filter { it.trackerId == TrackerManager.SIMKL }.mapNotNull { track ->
                    manager.get(track.trackerId)?.let {
                        eu.kanade.tachiyomi.ui.entries.anime.track.AnimeTrackItem(track, it)
                    }
                }
                if (legacy.isNotEmpty()) {
                    val result = eu.kanade.tachiyomi.util.AniChartApi().loadAiringTime(anime, legacy, force)
                    if (result != null) {
                        val event = result.takeIf { it.first in 1..65535 && it.second > 0 }
                        db.await(inTransaction = true) {
                            event?.let { airingQueries.upsertEvent(anime.id, it.first.toLong(), it.second * 1000, 0) }
                            airingQueries.removeAbsent(anime.id, listOfNotNull(event?.first?.toLong()))
                            val mask = Anime.ANIME_AIRING_EPISODE_MASK or Anime.ANIME_AIRING_TIME_MASK
                            val value = (result.first.toLong() shl 8) or (result.second shl 24)
                            airingQueries.setNextAiring(mask, if (event == null) 0 else value and mask, anime.id)
                        }
                        val resolved = cached.copy(
                            verifiedAt = now,
                            attemptedAt = now,
                            status = if (event == null) "UNANNOUNCED" else "AVAILABLE",
                        )
                        saveCache(anime.id, resolved)
                        return@withLock resolved
                    }
                }
                val unresolved = cached.copy(attemptedAt = now, status = "UNRESOLVED")
                saveCache(anime.id, unresolved)
                return@withLock unresolved
            }
            val result = ArrayList<AiringEvent>()
            var page = 1
            var total = 0
            var finished = false
            var currentReference = reference
            do {
                val body = catalog.page(currentReference, page)
                val metadata = Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("Media")?.jsonObject
                // Reuse the verified cross-catalog mapping for subsequent schedule pages.
                currentReference = AiringCatalogReference(
                    requireNotNull(metadata?.get("id")?.jsonPrimitive?.longOrNull),
                    metadata?.get("idMal")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
                )
                total = metadata?.get("episodes")?.jsonPrimitive?.intOrNull ?: 0
                finished = metadata?.get("status")?.jsonPrimitive?.content == "FINISHED"
                val parsed = parsePage(body, anime.id)
                result.addAll(parsed.first)
                val more = parsed.second
                page++
                // Reject a broken pagination response instead of replacing valid cache with a partial list.
                check(!more || page <= 100) { "Airing pagination did not terminate" }
            } while (more)
            val recentlyAired = entryEvents(anime.id).filter {
                it.airingAt <= now &&
                    now - it.airingAt < 2 * ReleasePolicy.DAY
            }
            val valid = (result + recentlyAired).distinctBy { it.episode }.sortedBy { it.airingAt }
            val status = if (valid.none { it.airingAt > now }) "UNANNOUNCED" else "AVAILABLE"
            db.await(inTransaction = true) {
                valid.forEach { airingQueries.upsertEvent(anime.id, it.episode.toLong(), it.airingAt, it.catalogId) }
                airingQueries.removeAbsent(anime.id, valid.map { it.episode.toLong() })
                airingQueries.cacheResult(anime.id, now, now, status, total.toLong(), if (finished) 1L else 0L)
                val upcoming = valid.firstOrNull { it.airingAt > now }
                val mask = Anime.ANIME_AIRING_EPISODE_MASK or Anime.ANIME_AIRING_TIME_MASK
                val value = ((upcoming?.episode?.toLong() ?: 0) shl 8) or ((upcoming?.airingAt?.div(1000) ?: 0) shl 24)
                airingQueries.setNextAiring(mask, value and mask, anime.id)
            }
            if (cached.status == "UNAVAILABLE") {
                logcat(LogPriority.INFO) { "Airing verification recovered for entry ${anime.id}" }
            }
            AiringCache(now, now, status, total, finished)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiringCatalogIdentityException) {
            logcat(LogPriority.WARN, e) { "Airing catalog identity unresolved for entry ${anime.id}" }
            cached.copy(attemptedAt = System.currentTimeMillis(), status = "UNRESOLVED")
                .also { saveCache(anime.id, it) }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Airing verification failed for entry ${anime.id}" }
            cached.copy(attemptedAt = System.currentTimeMillis(), status = "UNAVAILABLE")
                .also { saveCache(anime.id, it) }
        }
    }

    suspend fun reminded(event: AiringEvent) {
        if (event.variants.isNotEmpty()) AnimeScheduleRepository().reminded(event)
        // Also consume the fallback reminder so switching providers cannot notify twice.
        db.await { airingQueries.markReminded(System.currentTimeMillis(), event.entryId, event.episode.toLong()) }
    }

    internal suspend fun markUnavailable(id: Long, startedAt: Long) = locks[id.hashCode() and 63].withLock {
        val cache = cache(id)
        if (cache.verifiedAt < startedAt) {
            saveCache(id, cache.copy(attemptedAt = System.currentTimeMillis(), status = "UNAVAILABLE"))
        }
    }

    private suspend fun saveCache(id: Long, cache: AiringCache) = db.await {
        airingQueries.cacheResult(
            id,
            cache.verifiedAt,
            cache.attemptedAt,
            cache.status,
            cache.totalEpisodes.toLong(),
            if (cache.finished) 1L else 0L,
        )
    }

    internal fun parsePage(body: String, entryId: Long): Pair<List<AiringEvent>, Boolean> {
        val root = Json.parseToJsonElement(body).jsonObject
        require(root["errors"] == null) { "Airing query failed" }
        val media = requireNotNull(root["data"]?.jsonObject?.get("Media") as? JsonObject)
        val catalogId = requireNotNull(media["id"]?.jsonPrimitive?.longOrNull).also { require(it > 0) }
        val schedule = requireNotNull(media["airingSchedule"] as? JsonObject)
        val nodes = requireNotNull(schedule["nodes"]?.jsonArray)
        val more = requireNotNull(schedule["pageInfo"]?.jsonObject?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull)
        return nodes.map {
            val node = it.jsonObject
            val episode = requireNotNull(node["episode"]?.jsonPrimitive?.intOrNull).also { number ->
                require(number in 1..65535)
            }
            val seconds = requireNotNull(node["airingAt"]?.jsonPrimitive?.longOrNull).also { time ->
                require(time in 1..Long.MAX_VALUE / 1000)
            }
            AiringEvent(entryId, episode, seconds * 1000, catalogId)
        } to more
    }

    companion object {
        private val locks = Array(64) { Mutex() }
        private val catalog by lazy {
            AiringCatalogClient(AiringHttpClient.create(Injekt.get<NetworkHelper>().apiClient))
        }
    }
}
