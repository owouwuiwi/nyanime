package tachiyomi.data.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.discovery.SourceHomeCache
import tachiyomi.domain.discovery.SourceHomeCacheEntry
import tachiyomi.domain.discovery.SourceHomeCacheKey
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.repository.AnimeRepository

/** Persist Home artwork and references; personal metadata and progress come from the current local entries. */
class SqlSourceHomeCache(
    private val database: DiscoveryDatabase,
    private val anime: AnimeRepository,
) : SourceHomeCache {
    @Serializable
    private data class Payload(
        val ids: List<Long>,
        val hasNextPage: Boolean,
        val backgrounds: Map<Long, String> = emptyMap(),
        val descriptions: Map<Long, String> = emptyMap(),
        val presentations: List<SourceHomePresentation?> = emptyList(),
        val title: String? = null,
        // Null identifies legacy snapshots that discarded the extension's poster URLs.
        val covers: Map<Long, String>? = null,
    )
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(key: SourceHomeCacheKey): SourceHomeCacheEntry? = withContext(Dispatchers.IO) {
        try {
            val row = database.sourceHomeCacheQueries.find(
                key.home,
                key.source,
                key.revision,
                key.section,
                key.page.toLong(),
            ).executeAsOneOrNull() ?: return@withContext null
            val payload = json.decodeFromString<Payload>(row.payload)
            val covers = payload.covers ?: return@withContext null
            if (payload.ids.size > 200) return@withContext null
            if (payload.presentations.isNotEmpty() &&
                payload.presentations.size != payload.ids.size
            ) {
                return@withContext null
            }
            val items = payload.ids.mapIndexed { index, id ->
                val local = anime.getAnimeById(id)
                local.copy(
                    thumbnailUrl = covers[id] ?: local.thumbnailUrl,
                    backgroundUrl = payload.backgrounds[id] ?: local.backgroundUrl,
                    description = payload.descriptions[id] ?: local.description,
                    memo = payload.presentations.getOrNull(index)?.bounded()?.attachTo(local.memo)
                        ?: SourceHomePresentation.without(local.memo),
                )
            }
            if (items.any { it.source != key.source }) return@withContext null
            SourceHomeCacheEntry(SourceHomePage(items, payload.hasNextPage, payload.title?.take(100)), row.fetched_at)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A cache migration, missing local row or corrupt payload may require refetching, never an empty Home.
            null
        }
    }

    override suspend fun write(key: SourceHomeCacheKey, entry: SourceHomeCacheEntry) = withContext(Dispatchers.IO) {
        if (entry.page.items.size > 200 || entry.page.items.any { it.source != key.source }) return@withContext
        val payload = json.encodeToString(
            Payload(
                entry.page.items.map { it.id },
                entry.page.hasNextPage,
                entry.page.items.mapNotNull { item -> item.backgroundUrl?.take(2048)?.let { item.id to it } }.toMap(),
                entry.page.items.mapNotNull { item -> item.description?.take(8000)?.let { item.id to it } }.toMap(),
                entry.page.items.map { it.homePresentation?.bounded() },
                entry.page.title?.take(100),
                entry.page.items.mapNotNull { item ->
                    item.thumbnailUrl?.takeIf(String::isNotBlank)?.take(2048)?.let { item.id to it }
                }.toMap(),
            ),
        )
        database.transaction {
            database.sourceHomeCacheQueries.put(
                key.home,
                key.source,
                key.revision,
                key.section,
                key.page.toLong(),
                payload,
                entry.fetchedAt,
            )
            database.sourceHomeCacheQueries.discardExpired(entry.fetchedAt - 24 * 60 * 60_000L)
            database.sourceHomeCacheQueries.trim()
        }
    }
}
