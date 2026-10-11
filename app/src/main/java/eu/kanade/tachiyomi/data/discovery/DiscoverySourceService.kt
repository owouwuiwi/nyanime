package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.discovery.AnimeSourceLink
import tachiyomi.domain.discovery.AnimeSourceLinkRepository
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogIdentityMatcher
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository

data class DiscoverySource(val id: Long, val name: String, val language: String, val supportsLatest: Boolean)
data class SourceSearchResult(
    val source: DiscoverySource,
    val items: List<Anime> = emptyList(),
    val error: String? = null,
)

class DiscoverySourceService(
    private val manager: AnimeSourceManager,
    private val preferences: SourcePreferences,
    private val extensions: AnimeExtensionManager,
    private val links: AnimeSourceLinkRepository,
    private val tracks: AnimeTrackRepository,
    private val getAnime: GetAnime,
    private val byUrl: GetAnimeByUrlAndSourceId,
    private val toLocal: NetworkToLocalAnime,
    private val incognito: GetAnimeIncognitoState,
    private val base: BasePreferences,
) {
    private val semaphore = Semaphore(3)

    suspend fun available(): List<DiscoverySource> {
        manager.isInitialized.first { it }
        return manager.getAll().filter(::isEnabled).sortedWith(
            compareBy<AnimeSource>(
                { it.id != preferences.lastUsedAnimeSource().get() },
                { it.id.toString() !in preferences.pinnedAnimeSources().get() },
                { it.name.lowercase() },
            ),
        ).map { DiscoverySource(it.id, it.name, it.lang, it.supportsLatest) }
    }

    fun isEnabled(source: AnimeSource): Boolean {
        if (source.id.toString() in preferences.disabledAnimeSources().get()) return false
        if (source.lang !in preferences.enabledLanguages().get()) return false
        val extension = extensions.installedExtensionsFlow.value.firstOrNull { extension ->
            extension.sources.any {
                it.id ==
                    source.id
            }
        }
        if (extension == null) return false
        return preferences.showNsfwSource().get() || !extension.isNsfw
    }

    suspend fun resolve(anime: CatalogAnime): Anime? = withContext(Dispatchers.IO) {
        available()
        val remembered = links.find(anime.id)
        if (remembered != null) {
            val source = manager.get(remembered.sourceId)
            if (source != null && isEnabled(source)) {
                byUrl.await(remembered.sourceUrl, remembered.sourceId)?.let { return@withContext it }
            }
            // Do not delete: a temporarily disabled extension may be enabled again.
        }
        val ids = tracks.getAnimeTracksAsFlow().first().filter {
            CatalogIdentityMatcher.matches(anime, it.trackerId, it.remoteId)
        }.map { it.animeId }.distinct()
        val eligible = ids.mapNotNull { getAnime.await(it) }.filter {
            manager.get(it.source)?.let(::isEnabled) == true
        }
        eligible.singleOrNull()
    }

    suspend fun remember(catalog: CatalogAnime, anime: Anime) {
        require(manager.get(anime.source)?.let(::isEnabled) == true)
        if (!incognito.await(anime.source)) links.save(AnimeSourceLink(catalog.id, anime.source, anime.url))
    }

    fun search(query: String, limit: Int = Int.MAX_VALUE): Flow<SourceSearchResult> = channelFlow {
        if (base.downloadedOnly().get()) return@channelFlow
        if (query.isBlank()) return@channelFlow
        val sources = available().take(limit)
        coroutineScope {
            sources.forEach { source ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val items = withTimeout(20_000) {
                                val engine = manager.get(source.id) ?: error("Fonte non disponibile")
                                val result = engine.getSearchAnime(1, query.trim(), engine.getFilterList())
                                result.animes.take(30).map { toLocal.await(it.toDomainAnime(source.id)) }
                            }
                            send(SourceSearchResult(source, items))
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            send(SourceSearchResult(source, error = "La fonte non ha risposto in tempo"))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            send(SourceSearchResult(source, error = e.message ?: "Fonte non disponibile"))
                        }
                    }
                }
            }
        }
    }

    suspend fun sourceFeed(sourceId: Long, latest: Boolean): List<Anime> = withContext(Dispatchers.IO) {
        if (base.downloadedOnly().get()) return@withContext emptyList()
        val source = manager.get(sourceId)?.takeIf(::isEnabled) ?: error("Fonte non disponibile")
        if (latest && !source.supportsLatest) return@withContext emptyList()
        semaphore.withPermit {
            withTimeout(20_000) {
                val page = if (latest) source.getLatestUpdates(1) else source.getPopularAnime(1)
                page.animes.take(20).map { toLocal.await(it.toDomainAnime(source.id)) }
            }
        }
    }
}
