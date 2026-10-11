package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.entries.anime.interactor.SyncSeasonsWithSource
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.domain.items.episode.interactor.SyncEpisodesWithSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogSeriesEvidence
import tachiyomi.domain.discovery.SmartTitleMatcher
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.service.EpisodeRecognition
import tachiyomi.domain.source.anime.service.AnimeSourceManager

/**
 * Adapts catalogue entries to the structure actually supplied by extensions.
 * Combined source listings stay combined; no synthetic seasons or episode offsets.
 */
class SmartSourceResolver(
    private val sources: DiscoverySourceService,
    private val manager: AnimeSourceManager,
    private val toLocal: NetworkToLocalAnime,
    private val syncSeasons: SyncSeasonsWithSource,
    private val syncEpisodes: SyncEpisodesWithSource,
    private val base: BasePreferences,
    private val series: CatalogSeriesEvidence,
) {
    suspend fun resolve(catalog: CatalogAnime, status: (String) -> Unit): Anime? {
        val result = withTimeoutOrNull(60_000) { resolveAvailable(catalog, status) }
        if (result == null) status("Nessuna versione verificabile al momento. Puoi riprovare o cambiare fonte.")
        return result
    }

    private suspend fun resolveAvailable(catalog: CatalogAnime, status: (String) -> Unit): Anime? = withContext(
        Dispatchers.IO,
    ) {
        val linked = sources.resolve(catalog)
        if (linked != null) {
            if (base.downloadedOnly().get()) return@withContext linked
            try {
                status("Verifico la fonte già collegata…")
                withTimeout(15_000) { verify(catalog, linked, trusted = true) }?.let { return@withContext it }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                // Try another source below.
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The remembered source may have gone offline since the last visit.
            }
        }
        if (base.downloadedOnly().get()) return@withContext null
        val attempted = mutableSetOf<Pair<Long, String>>()
        for (query in SmartTitleMatcher.searchQueries(catalog)) {
            status("Cerco automaticamente la versione disponibile…")
            // Prefer the first verified result; slow extensions cannot hold every other result hostage.
            // The service starts preferred sources first and caps concurrent requests at three.
            val found = sources.search(query).mapNotNull { result ->
                val candidates = result.items.distinctBy { it.source to it.url }
                    .filter { SmartTitleMatcher.score(catalog, it.title, collection = true) >= 70 }
                    .sortedByDescending { SmartTitleMatcher.score(catalog, it.title) }
                for (candidate in candidates.take(6)) {
                    if (!attempted.add(candidate.source to candidate.url)) continue
                    val sourceName = manager.get(candidate.source)?.name ?: "la fonte"
                    status("Verifico $sourceName e la struttura degli episodi…")
                    try {
                        val verified = withTimeout(15_000) { verify(catalog, candidate) }
                        if (verified != null) {
                            sources.remember(catalog, verified)
                            status("Fonte scelta automaticamente: ${manager.get(verified.source)?.name}")
                            return@mapNotNull verified
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        // A slow candidate does not prevent trying another enabled source.
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Broken source, incomplete listing or challenge: try the next candidate.
                    }
                }
                null
            }.firstOrNull()
            if (found != null) return@withContext found
        }
        status("Non ho trovato una versione compatibile nelle fonti abilitate.")
        null
    }

    private suspend fun verify(catalog: CatalogAnime, candidate: Anime, trusted: Boolean = false): Anime? {
        val source = manager.get(candidate.source)?.takeIf(sources::isEnabled) ?: return null
        val requested = candidate.toSAnime()
        val details = if (candidate.fetchType == FetchType.Seasons) {
            source.getAnimeSeasonUpdate(requested, emptyList(), fetchDetails = true, fetchSeasons = false).anime
        } else {
            source.getAnimeEpisodeUpdate(requested, emptyList(), fetchDetails = true, fetchEpisodes = false).anime
        }.apply {
            url = candidate.url
            if (title.isBlank()) title = candidate.title
        }
        var target = candidate
        if (details.fetch_type == FetchType.Seasons) {
            val seasons = source.getAnimeSeasonUpdate(
                details,
                emptyList(),
                fetchDetails = false,
                fetchSeasons = true,
            ).seasons
            val exact = seasons.sortedByDescending { SmartTitleMatcher.score(catalog, it.title) }
                .firstOrNull { SmartTitleMatcher.score(catalog, it.title) >= 95 }
            val ordinal = SmartTitleMatcher.season(catalog.title)
                ?: if (catalog.relations.none { it.relationship == "PREQUEL" }) 1 else null
            val season = exact ?: seasons.singleOrNull {
                ordinal != null &&
                    (it.season_number.toInt() == ordinal || SmartTitleMatcher.season(it.title) == ordinal)
            } ?: return null
            syncSeasons.await(seasons, candidate.copy(fetchType = FetchType.Seasons), source)
            target = toLocal.await(season.toDomainAnime(candidate.source))
        }
        val resolvedDetails = if (target.id == candidate.id) details else target.toSAnime()
        val episodes = source.getAnimeEpisodeUpdate(
            resolvedDetails,
            emptyList(),
            fetchDetails = false,
            fetchEpisodes = true,
        ).episodes
        if (episodes.isEmpty()) return null
        val titleScore = SmartTitleMatcher.score(catalog, resolvedDetails.title)
        val isCombined = if (!trusted &&
            titleScore < 95 &&
            SmartTitleMatcher.season(details.title) == null &&
            SmartTitleMatcher.score(catalog, details.title, collection = true) >= 85
        ) {
            val minimum = series.minimumCombinedEpisodes(catalog)
            // Specials/duplicates must not make a short first season look like a collection.
            val numbers = episodes.map {
                EpisodeRecognition.parseEpisodeNumber(resolvedDetails.title, it.name, it.episode_number.toDouble())
            }.filter { it > 0 && it % 1.0 == 0.0 }.toSet()
            minimum != null && numbers.containsAll((1..minimum).map(Int::toDouble))
        } else {
            false
        }
        if (!trusted && details.fetch_type != FetchType.Seasons && titleScore < 95 && !isCombined) return null
        // Synchronize under the source's own anime identity so all catalogue seasons share
        // the same progress when an extension exposes a single combined series.
        syncEpisodes.await(episodes, target.copy(fetchType = FetchType.Episodes), source)
        return target
    }
}
