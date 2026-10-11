package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.tachiyomi.animesource.AnimeCatalogIdResolver
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.MangaSourceUpdateGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository

/** Catalog relationships are independent of library membership and tracker authentication. */
class MangaAnimeContinuity(
    private val animeRepository: AnimeRepository,
    private val animeTracks: AnimeTrackRepository,
    private val sourceManager: AnimeSourceManager,
    private val toLocal: NetworkToLocalAnime,
    private val identities: ContinuityIdentityCache,
    private val policy: ContinuitySourcePolicy,
) {
    data class Choice(
        val catalogId: Long,
        val title: String,
        val format: String?,
        val episodes: Int?,
        val year: Int?,
        val coverUrl: String?,
        val viaOriginalNovel: Boolean,
        val matches: List<Anime>,
        val season: Int? = null,
        val beginning: AnimeMangaContinuity.Checkpoint? = null,
        val latestAdapted: AnimeMangaContinuity.Checkpoint? = null,
    )

    sealed interface Result {
        data class Found(val choices: List<Choice>, val resolvingIds: Set<Long> = emptySet()) : Result
        data object NoCatalogId : Result
        data object NoAnimeRelation : Result
        data object Unavailable : Result
    }

    suspend fun resolve(
        manga: Manga,
        source: MangaSource,
        trackedAniListId: Long?,
        trackedMalId: Long?,
        onProgress: suspend (Result.Found) -> Unit = {},
    ): Result {
        val privateSession = policy.privateManga(manga.source)
        val cached = identities.get("manga", manga.source, manga.url, privateSession)
        val hints = cached ?: if (trackedAniListId == null && trackedMalId == null) {
            ContinuityRequests.optional {
                SourceTrackingHints.from(
                    MangaSourceUpdateGate.await(
                        source,
                        manga.toSManga(),
                        emptyList(),
                        fetchDetails = true,
                        fetchChapters = false,
                    ).manga,
                )
            }
        } else {
            null
        }
        identities.remember("manga", manga.source, manga.url, hints, privateSession)
        val mangaId = hints?.anilistId ?: trackedAniListId ?: (hints?.malId ?: trackedMalId)?.let {
            AniListMediaLookup.resolveId(it, AniListMediaLookup.Type.MANGA, malId = true)?.id
        } ?: AniListMediaLookup.resolveExact(manga.title, AniListMediaLookup.Type.MANGA)?.id
            ?: return Result.NoCatalogId
        val relations = AniListMediaLookup.animeAdaptations(mangaId)
        if (relations.isEmpty()) return Result.NoAnimeRelation
        sourceManager.isInitialized.first { it }
        val sources = policy.anime(sourceManager.getAll().filterIsInstance<AnimeCatalogueSource>())
        val sourceIds = sources.map { it.id }.toSet()
        val cachedCopies = relations.flatMap {
            identities.matching("anime", it.id, it.malId, privateSession)
        }.distinct().filter { (id, _) -> id in sourceIds && !policy.privateAnime(id) }
            .mapNotNull { (id, url) -> animeRepository.getAnimeByUrlAndSourceId(url, id) }
        val known = (animeRepository.getAnimeFavorites() + animeRepository.getWatchedAnimeNotInLibrary() + cachedCopies)
            .distinctBy { it.id }.filter { it.source in sourceIds }
        val tracks = animeTracks.getAnimeTracksAsFlow().first().groupBy { it.animeId }
        fun tracked(anime: Anime) = SourceTrackingHints(
            anilistId = tracks[anime.id].orEmpty().firstOrNull { it.trackerId == TrackerManager.ANILIST }?.remoteId,
            malId = tracks[anime.id].orEmpty().firstOrNull { it.trackerId == 1L }?.remoteId,
        )
        fun matches(relation: AniListMediaLookup.AnimeRelation, anime: Anime) = knownIdentityMatches(
            relation.id,
            relation.malId,
            SourceTrackingHints.from(anime) ?: identities.get(
                "anime",
                anime.source,
                anime.url,
                privateSession || policy.privateAnime(anime.source),
            ),
            tracked(anime),
        )
        return coroutineScope {
            val progress = ContinuityProgress(
                relations.map { relation ->
                    Choice(
                        relation.id, relation.title, relation.format, relation.episodes, relation.year,
                        relation.coverUrl, relation.viaOriginalNovel, known.filter { matches(relation, it) },
                        relation.context.season,
                    )
                },
                Choice::catalogId,
            ) { choices, resolving -> onProgress(Result.Found(choices, resolving)) }
            progress.emit()
            val chapterRanges = async(Dispatchers.IO) {
                val primary = ContinuityRequests.metadata { AdaptationChapterCatalog.mangaBaka(mangaId) }
                val fallback = hints?.mangaUpdatesId?.takeIf {
                    relations.any { primary?.forAdaptation(it.format, it.context)?.complete != true }
                }?.let { id -> ContinuityRequests.metadata { AdaptationChapterCatalog.mangaUpdates(id) } }
                relations.associate { relation ->
                    val range = primary?.forAdaptation(relation.format, relation.context)
                        ?: AdaptationChapterCatalog.Metadata().forAdaptation(relation.format, relation.context)
                    relation.id to (
                        fallback?.let {
                            range.withFallback(it.forAdaptation(relation.format, relation.context))
                        } ?: range
                        )
                }
            }
            relations.map { relation ->
                async(Dispatchers.IO) {
                    val copies = known.filter { matches(relation, it) }.toMutableList()
                    val mutex = kotlinx.coroutines.sync.Mutex()
                    suspend fun accept(
                        remote: eu.kanade.tachiyomi.animesource.model.SAnime,
                        source: AnimeCatalogueSource,
                    ): Boolean {
                        val supplied = SourceTrackingHints.from(remote)
                        val details = if (supplied?.anilistId != null || supplied?.malId != null) {
                            remote
                        } else {
                            ContinuityRequests.optional {
                                if (remote.fetch_type == FetchType.Seasons) {
                                    source.getAnimeSeasonUpdate(remote, emptyList(), true, false).anime
                                } else {
                                    source.getAnimeEpisodeUpdate(remote, emptyList(), true, false).anime
                                }
                            } ?: return false
                        }
                        if (!AnimeMangaContinuity.hasSameIdentity(
                                relation.id,
                                relation.malId,
                                SourceTrackingHints.from(details),
                            )
                        ) {
                            return false
                        }
                        details.url = remote.url
                        val local = toLocal.await(details.toDomainAnime(source.id))
                        if (!knownIdentityMatches(
                                relation.id,
                                relation.malId,
                                SourceTrackingHints.from(details),
                                tracked(local),
                            )
                        ) {
                            return false
                        }
                        identities.remember(
                            "anime",
                            source.id,
                            details.url,
                            SourceTrackingHints.from(details),
                            privateSession || policy.privateAnime(source.id),
                        )
                        mutex.withLock {
                            if (copies.none { it.id == local.id }) copies += local
                            progress.update(relation.id) { it.copy(matches = copies.toList()) }
                        }
                        return true
                    }
                    if (copies.isEmpty()) {
                        for (batch in sources.chunked(3)) {
                            batch.map { source ->
                                async {
                                    ContinuityRequests.source {
                                        if (source is AnimeCatalogIdResolver) {
                                            val remote = ContinuityRequests.optional(12_000) {
                                                source.findAnimeByCatalogId("anilist", relation.id)
                                                    ?: relation.malId?.let {
                                                        source.findAnimeByCatalogId("myanimelist", it)
                                                    }
                                            }
                                            if (remote != null && accept(remote, source)) return@source
                                        }
                                        val titles = (listOf(relation.title) + relation.titles)
                                            .filter(String::isNotBlank).distinct().take(2)
                                        for (title in titles) {
                                            val page = ContinuityRequests.optional {
                                                source.getSearchAnime(1, title, source.getFilterList())
                                            } ?: continue
                                            for (remote in page.animes.take(4)) {
                                                if (accept(remote, source)) return@source
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                            if (copies.isNotEmpty()) break
                        }
                    }
                    progress.update(relation.id, lookupFinished = true) { it.copy(matches = copies.toList()) }
                    val range = chapterRanges.await()[relation.id]
                    progress.update(relation.id) {
                        it.copy(beginning = range?.beginning, latestAdapted = range?.ending)
                    }
                }
            }.awaitAll()
            Result.Found(progress.result())
        }
    }

    companion object {
        internal fun knownIdentityMatches(
            id: Long,
            malId: Long?,
            sourceHints: SourceTrackingHints?,
            trackedHints: SourceTrackingHints?,
        ): Boolean {
            // An old title-based tracker binding cannot establish the identity of a source copy.
            if (!AnimeMangaContinuity.hasSameIdentity(id, malId, sourceHints)) return false
            val hints = listOfNotNull(sourceHints, trackedHints)
            if (hints.any {
                    it.anilistId != null &&
                        it.anilistId != id ||
                        malId != null &&
                        it.malId != null &&
                        it.malId != malId
                }
            ) {
                return false
            }
            return true
        }
    }
}
