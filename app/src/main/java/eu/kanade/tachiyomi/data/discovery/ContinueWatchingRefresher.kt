package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.library.anime.AnimeForegroundRefreshGate
import eu.kanade.tachiyomi.data.library.anime.AnimeRefreshSchedule
import eu.kanade.tachiyomi.data.releases.ReleaseEligibility
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleasePolicy
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateAnimeFromRemote
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.source.local.entries.anime.isLocal
import java.util.concurrent.TimeUnit

/** Only checks a few recently watched titles; source Home feeds stay independent. */
class ContinueWatchingRefresher(
    private val base: BasePreferences,
    private val visibility: ResumeVisibility,
    private val sources: AnimeSourceManager,
    private val sourceService: DiscoverySourceService,
    private val incognito: GetAnimeIncognitoState,
    private val update: UpdateAnimeFromRemote,
    private val history: GetAnimeHistory,
    private val getAnime: GetAnime,
    private val schedule: AnimeRefreshSchedule,
) {
    suspend fun refresh(sourceIds: Set<Long>? = null) = withContext(Dispatchers.IO) {
        try {
            refreshAvailable(sourceIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not check recently watched titles" }
        }
    }

    private suspend fun refreshAvailable(sourceIds: Set<Long>?) {
        if (base.incognitoMode().get() || base.downloadedOnly().get()) return

        // A completed last-known episode has no Continue Watching card yet. Include it so a
        // newly released episode can make that card reappear without scanning the library.
        val candidates = ArrayList<Anime>()
        val seenIds = HashSet<Long>()
        val hidden = visibility.hidden.get()
        val now = System.currentTimeMillis()
        for (entry in history.subscribe("").first()) {
            currentCoroutineContext().ensureActive()
            if (!seenIds.add(entry.animeId) || entry.animeId.toString() in hidden) continue
            if (sourceIds != null && entry.coverData.sourceId !in sourceIds) continue
            val anime = getAnime.await(entry.animeId) ?: continue
            if (anime.isLocal() || incognito.await(anime.source)) continue
            if (sources.get(anime.source)?.let(sourceService::isEnabled) != true) continue
            if (ReleaseEligibility.source(ReleaseMedium.ANIME, anime.id) == null ||
                !ReleasePolicy.isDue(ReleaseStore().check(ReleaseMedium.ANIME, anime.id), now, foreground = true)
            ) {
                continue
            }
            candidates += anime
        }

        // Check the oldest attempted titles first, then prefer ongoing titles on ties. A batch
        // is bounded, but titles beyond it are eligible on the next Home visit.
        val ordered = candidates.sortedWith(
            compareBy<Anime> { schedule.lastAttempt(it.id) }.thenBy { schedule.priority(it) },
        )
        var checked = 0
        for (anime in ordered) {
            currentCoroutineContext().ensureActive()
            if (checked >= AnimeRefreshSchedule.MAX_PER_HOME_VISIT) break
            if (base.incognitoMode().get() || base.downloadedOnly().get()) break
            if (anime.id.toString() in visibility.hidden.get()) continue
            val source = sources.get(anime.source)?.takeIf(sourceService::isEnabled) ?: continue
            if (!AnimeForegroundRefreshGate.tryBegin(anime.id, gateClock())) continue

            checked++
            var succeeded = false
            try {
                succeeded = when (anime.fetchType) {
                    FetchType.Episodes -> update.awaitEpisodesUpdate(
                        source = source,
                        anime = anime,
                        fetchEpisodes = true,
                    ).isSuccess
                    FetchType.Seasons -> update.awaitSeasonsUpdate(
                        source = source,
                        anime = anime,
                        fetchSeasons = true,
                    ).isSuccess
                }
                currentCoroutineContext().ensureActive()
            } finally {
                if (!succeeded &&
                    currentCoroutineContext().isActive
                ) {
                    ReleaseStore().markFailure(ReleaseMedium.ANIME, anime.id, System.currentTimeMillis())
                }
                AnimeForegroundRefreshGate.finish(
                    anime.id,
                    gateClock(),
                    successful = succeeded,
                    cancelled = !currentCoroutineContext().isActive,
                )
            }
            delay(REQUEST_SPACING_MS)
        }
    }

    private fun gateClock() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    private companion object {
        const val REQUEST_SPACING_MS = 1_500L
    }
}
