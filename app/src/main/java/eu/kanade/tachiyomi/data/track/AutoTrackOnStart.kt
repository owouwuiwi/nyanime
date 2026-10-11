package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.domain.track.manga.interactor.AddMangaTracks
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.model.AnimeTrackSearch
import eu.kanade.tachiyomi.data.track.model.MangaTrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.MangaSourceUpdateGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.domain.track.anime.model.toDbTrack as toAnimeDbTrack
import eu.kanade.domain.track.manga.model.toDbTrack as toMangaDbTrack

/** Runs away from the player/reader loading path and only writes verified, unique matches. */
internal object AutoTrackOnStart {
    private val hintMutex = Mutex()
    private val mangaBindMutex = Mutex()
    private val animeBindMutex = Mutex()
    private val animeHintsCache = LinkedHashMap<Long, Pair<Long, SourceTrackingHints>>(64, 0.75f, true)

    suspend fun animeHints(anime: Anime, source: AnimeSource): SourceTrackingHints? {
        SourceTrackingHints.from(anime)?.let { return it }
        return hintMutex.withLock {
            val cached = animeHintsCache[anime.id]
            if (cached != null && cached.first > System.currentTimeMillis()) return@withLock cached.second
            val hints = try {
                withContext(Dispatchers.IO) { SourceTrackingHints.from(source.getAnimeDetails(anime.toSAnime())) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (hints != null) {
                animeHintsCache[anime.id] = (System.currentTimeMillis() + 60 * 60 * 1000L) to hints
                if (animeHintsCache.size > 64) animeHintsCache.remove(animeHintsCache.keys.first())
            }
            hints
        }
    }

    suspend fun anime(anime: Anime, source: AnimeSource, episodeNumber: Double): Boolean =
        animeAttempt(anime, source, episodeNumber).linked

    suspend fun animeAttempt(anime: Anime, source: AnimeSource, episodeNumber: Double): AutoTrackingAttempt =
        animeBindMutex.withLock {
            AutoTrackingRetry.safely { withTimeout(60_000) { trackAnime(anime, source, episodeNumber) } }
        }

    private suspend fun trackAnime(
        anime: Anime,
        source: AnimeSource,
        episodeNumber: Double,
    ): AutoTrackingAttempt = supervisorScope {
        val manager = Injekt.get<TrackerManager>()
        val getTracks = Injekt.get<GetAnimeTracks>()
        val addTracks = Injekt.get<AddAnimeTracks>()
        val linkedIds = getTracks.await(anime.id).mapTo(mutableSetOf()) { it.trackerId }
        val services = manager.loggedInTrackers().filterIsInstance<AnimeTracker>()
            .filter { (it as Tracker).id !in linkedIds }
        if (services.isEmpty()) return@supervisorScope AutoTrackingAttempt.Complete
        val hints = animeHints(anime, source)
        var catalogueUnavailable = false
        val catalog = try {
            when {
                hints?.anilistId != null && hints.malId != null ->
                    AniListMediaLookup.Match(hints.anilistId, hints.malId, hints.titles, null)
                hints?.anilistId != null ->
                    AniListMediaLookup.resolveId(hints.anilistId, AniListMediaLookup.Type.ANIME, malId = false)
                hints?.malId != null ->
                    AniListMediaLookup.resolveId(hints.malId, AniListMediaLookup.Type.ANIME, malId = true)
                else -> AniListMediaLookup.resolve(anime.title, AniListMediaLookup.Type.ANIME)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalogueUnavailable = true
            null
        }
        if (catalog?.episodes != null &&
            episodeNumber > catalog.episodes
        ) {
            return@supervisorScope AutoTrackingAttempt.Complete
        }
        services.map { service ->
            async {
                val tracker = service as Tracker
                if (getTracks.await(anime.id).any {
                        it.trackerId == tracker.id
                    }
                ) {
                    return@async AutoTrackingAttempt.Complete
                }
                try {
                    val result = if (tracker is EnhancedAnimeTracker) {
                        if (!tracker.accept(source)) return@async AutoTrackingAttempt.Complete
                        tracker.match(anime)
                    } else {
                        findAnime(service, anime.title, catalog, hints, episodeNumber)
                    } ?: return@async if (catalogueUnavailable) {
                        AutoTrackingAttempt.Retry
                    } else {
                        AutoTrackingAttempt.Complete
                    }
                    if (!tracker.isLoggedIn ||
                        getTracks.await(anime.id).any { it.trackerId == tracker.id }
                    ) {
                        return@async AutoTrackingAttempt.Complete
                    }
                    result.anime_id = anime.id
                    addTracks.bind(service, result, anime.id)
                    getTracks.await(anime.id).firstOrNull { it.trackerId == tracker.id }?.let { bound ->
                        if (episodeNumber > 0 &&
                            bound.lastEpisodeSeen <= 0 &&
                            bound.status != service.getWatchingStatus() &&
                            bound.status != service.getCompletionStatus() &&
                            bound.status != service.getRewatchingStatus()
                        ) {
                            service.setRemoteAnimeStatus(bound.toAnimeDbTrack(), service.getWatchingStatus())
                        }
                    }
                    AutoTrackingAttempt.Linked
                } catch (timeout: TimeoutCancellationException) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    AutoTrackingAttempt.Retry
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logcat(LogPriority.WARN, error) { "Automatic anime tracking failed for ${tracker.name}" }
                    AutoTrackingAttempt.Retry
                }
            }
        }.awaitAll().let(AutoTrackingAttempt::combine)
    }

    suspend fun manga(manga: Manga, source: MangaSource, started: Boolean = true): Boolean =
        mangaAttempt(manga, source, started).linked

    suspend fun mangaAttempt(manga: Manga, source: MangaSource, started: Boolean = true): AutoTrackingAttempt =
        mangaBindMutex.withLock {
            AutoTrackingRetry.safely { withTimeout(60_000) { trackManga(manga, source, started) } }
        }

    private suspend fun trackManga(manga: Manga, source: MangaSource, started: Boolean): AutoTrackingAttempt = run {
        supervisorScope {
            val manager = Injekt.get<TrackerManager>()
            val getTracks = Injekt.get<GetMangaTracks>()
            val addTracks = Injekt.get<AddMangaTracks>()
            val linkedIds = getTracks.await(manga.id).mapTo(mutableSetOf()) { it.trackerId }
            val services = manager.loggedInTrackers().filterIsInstance<MangaTracker>()
                .filter { (it as Tracker).id !in linkedIds }
            if (services.isEmpty()) return@supervisorScope AutoTrackingAttempt.Complete
            val hints = try {
                SourceTrackingHints.from(
                    MangaSourceUpdateGate.await(
                        source,
                        manga.toSManga(),
                        emptyList(),
                        fetchDetails = true,
                        fetchChapters = false,
                    ).manga,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            var catalogueUnavailable = false
            val catalog = try {
                when {
                    hints?.anilistId != null && hints.malId != null ->
                        AniListMediaLookup.Match(hints.anilistId, hints.malId, hints.titles, null)
                    hints?.anilistId != null ->
                        AniListMediaLookup.resolveId(hints.anilistId, AniListMediaLookup.Type.MANGA, malId = false)
                    hints?.malId != null ->
                        AniListMediaLookup.resolveId(hints.malId, AniListMediaLookup.Type.MANGA, malId = true)
                    else -> AniListMediaLookup.resolve(manga.title, AniListMediaLookup.Type.MANGA)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                catalogueUnavailable = true
                null
            }
            services.map { service ->
                async {
                    val tracker = service as Tracker
                    if (getTracks.await(manga.id).any {
                            it.trackerId == tracker.id
                        }
                    ) {
                        return@async AutoTrackingAttempt.Complete
                    }
                    try {
                        val result = if (tracker is EnhancedMangaTracker) {
                            if (!tracker.accept(source)) return@async AutoTrackingAttempt.Complete
                            tracker.match(manga)
                        } else {
                            findManga(service, manga.title, catalog, hints)
                        } ?: return@async if (catalogueUnavailable) {
                            AutoTrackingAttempt.Retry
                        } else {
                            AutoTrackingAttempt.Complete
                        }
                        if (!tracker.isLoggedIn ||
                            getTracks.await(manga.id).any { it.trackerId == tracker.id }
                        ) {
                            return@async AutoTrackingAttempt.Complete
                        }
                        result.manga_id = manga.id
                        addTracks.bind(service, result, manga.id)
                        getTracks.await(manga.id).firstOrNull { it.trackerId == tracker.id }?.let { bound ->
                            if (started &&
                                bound.lastChapterRead <= 0 &&
                                bound.status != service.getReadingStatus() &&
                                bound.status != service.getCompletionStatus() &&
                                bound.status != service.getRereadingStatus()
                            ) {
                                service.setRemoteMangaStatus(bound.toMangaDbTrack(), service.getReadingStatus())
                            }
                        }
                        AutoTrackingAttempt.Linked
                    } catch (timeout: TimeoutCancellationException) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        AutoTrackingAttempt.Retry
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Automatic manga tracking failed for ${tracker.name}" }
                        AutoTrackingAttempt.Retry
                    }
                }
            }.awaitAll().let(AutoTrackingAttempt::combine)
        }
    }

    private suspend fun findAnime(
        service: AnimeTracker,
        title: String,
        catalog: AniListMediaLookup.Match?,
        hints: SourceTrackingHints?,
        episodeNumber: Double,
    ): AnimeTrackSearch? {
        val preferredId = when (service) {
            is Anilist -> hints?.anilistId ?: catalog?.id
            is MyAnimeList -> hints?.malId ?: catalog?.malId
            else -> null
        }
        if (service is Anilist && preferredId != null) {
            return withTimeout(12_000) { service.findAnimeById(preferredId) }
                ?.takeIf { it.total_episodes <= 0 || episodeNumber <= it.total_episodes }
        }
        if (service is MyAnimeList && preferredId != null) {
            return withTimeout(8_000) { service.searchAnime("id:$preferredId") }
                ?.singleOrNull {
                    it.remote_id == preferredId &&
                        (it.total_episodes <= 0 || episodeNumber <= it.total_episodes)
                }
        }
        val allTitles = listOf(title) + catalog?.titles.orEmpty() + hints?.titles.orEmpty()
        val queries = allTitles.distinctBy(TrackTitleMatcher::normalize).take(5)
        for (query in queries) {
            val candidates = withTimeout(8_000) { service.searchAnime(query) }.orEmpty()
                .filter { it.total_episodes <= 0 || episodeNumber <= it.total_episodes }
            if (preferredId != null) {
                candidates.singleOrNull { it.remote_id == preferredId }?.let { return it }
            } else {
                val matched = chooseAny(allTitles, candidates) { it.title to it.remote_id }
                if (matched != null) return matched
            }
        }
        return null
    }

    private suspend fun findManga(
        service: MangaTracker,
        title: String,
        catalog: AniListMediaLookup.Match?,
        hints: SourceTrackingHints?,
    ): MangaTrackSearch? {
        val preferredId = when (service) {
            is Anilist -> hints?.anilistId ?: catalog?.id
            is MyAnimeList -> hints?.malId ?: catalog?.malId
            is MangaUpdates -> hints?.mangaUpdatesId
            else -> null
        }
        if (service is Anilist && preferredId != null) {
            return withTimeout(12_000) { service.findMangaById(preferredId) }
        }
        if (service is MyAnimeList && preferredId != null) {
            return withTimeout(8_000) { service.searchManga("id:$preferredId") }
                ?.singleOrNull { it.remote_id == preferredId }
        }
        val allTitles = listOf(title) + catalog?.titles.orEmpty() + hints?.titles.orEmpty()
        val queries = allTitles.distinctBy(TrackTitleMatcher::normalize).take(5)
        for (query in queries) {
            val candidates = withTimeout(8_000) { service.searchManga(query) }.orEmpty()
            if (preferredId != null) {
                candidates.singleOrNull { it.remote_id == preferredId }?.let { return it }
            } else {
                val matched = chooseAny(allTitles, candidates) { it.title to it.remote_id }
                if (matched != null) return matched
            }
        }
        return null
    }

    private fun <T> chooseAny(titles: List<String>, candidates: List<T>, key: (T) -> Pair<String, Long>): T? {
        val matches = titles.mapNotNull { title ->
            TrackTitleMatcher.choose(title, candidates, { listOf(key(it).first) }, { key(it).second })
        }.distinctBy { key(it).second }
        return matches.singleOrNull()
    }
}
