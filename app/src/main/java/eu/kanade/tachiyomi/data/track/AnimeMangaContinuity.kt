package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.RelatedMangaLinks
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaCatalogIdResolver
import eu.kanade.tachiyomi.source.MangaCatalogLinkResolver
import eu.kanade.tachiyomi.source.MangaSourceUpdateGate
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.track.manga.model.MangaTrack
import tachiyomi.domain.track.manga.repository.MangaTrackRepository

/** Verified catalog relationships and source-independent continuation checkpoints. */
class AnimeMangaContinuity(
    private val mangaRepository: MangaRepository,
    private val mangaTracks: MangaTrackRepository,
    private val sourceManager: MangaSourceManager,
    private val toLocal: NetworkToLocalManga,
    private val identities: ContinuityIdentityCache,
    private val policy: ContinuitySourcePolicy,
) {
    data class Checkpoint(
        val chapter: Double,
        val note: String,
        val episode: Int?,
        val exactEpisode: Boolean = false,
        val season: Int? = null,
        val page: Int? = null,
    )

    data class Choice(
        val catalogId: Long,
        val catalogMalId: Long?,
        val title: String,
        val coverUrl: String?,
        val format: String?,
        val viaOriginalNovel: Boolean = false,
        val beginning: Checkpoint?,
        val latestAdapted: Checkpoint?,
        val matches: List<Manga>,
        val season: Int? = null,
    ) {
        /** Never interpolate episode numbers into chapter numbers. */
        fun continuationAfter(watchedEpisode: Double?): Double? {
            val end = latestAdapted ?: return null
            val episode = end.episode ?: return null
            if (!end.exactEpisode ||
                end.season != null ||
                watchedEpisode == null ||
                watchedEpisode != episode.toDouble()
            ) {
                return null
            }
            // Reopen the mapped chapter: adaptations can stop midway through it.
            return end.chapter
        }
    }

    sealed interface Result {
        data class Found(
            val choices: List<Choice>,
            val watchedEpisode: Double?,
            val resolvingIds: Set<Long> = emptySet(),
        ) : Result
        data object NoCatalogId : Result
        data object NoMangaRelation : Result
        data object Unavailable : Result
    }

    suspend fun resolve(
        anime: Anime,
        source: AnimeSource,
        trackedAniListId: Long?,
        trackedMalId: Long?,
        watchedEpisode: Double?,
        onProgress: suspend (Result.Found) -> Unit = {},
    ): Result {
        val privateSession = policy.privateAnime(anime.source)
        val stored = SourceTrackingHints.from(anime)
            ?: identities.get("anime", anime.source, anime.url, privateSession)
        // An already supplied ID must not wait behind a fresh source request or auto-tracking.
        val hints = stored ?: if (trackedAniListId == null && trackedMalId == null) {
            ContinuityRequests.optional { AutoTrackOnStart.animeHints(anime, source) }
        } else {
            null
        }
        identities.remember("anime", anime.source, anime.url, hints, privateSession)
        val animeId = hints?.anilistId ?: trackedAniListId ?: (hints?.malId ?: trackedMalId)?.let {
            AniListMediaLookup.resolveId(it, AniListMediaLookup.Type.ANIME, malId = true)?.id
        } ?: AniListMediaLookup.resolveExact(anime.title, AniListMediaLookup.Type.ANIME)?.id
            ?: return Result.NoCatalogId

        val adaptation = AniListMediaLookup.mangaAdaptations(animeId)
        val sourceSeason = anime.seasonNumber.takeIf { it in 1.0..99.0 && it % 1.0 == 0.0 }?.toInt()
        val season = when {
            sourceSeason != null &&
                adaptation.context.season != null &&
                sourceSeason != adaptation.context.season -> null
            else -> sourceSeason ?: adaptation.context.season
        }
        val allowUnscoped = adaptation.context.standaloneSeason && season == 1
        val relations = adaptation.relations.filter { it.format == "MANGA" }
            .ifEmpty { adaptation.relations.filter { it.format == "ONE_SHOT" } }
            .sortedWith(
                compareByDescending<AniListMediaLookup.MangaRelation> { relation ->
                    relation.titles.any { TrackTitleMatcher.normalize(it) == TrackTitleMatcher.normalize(anime.title) }
                }.thenBy { it.id },
            ).take(12)
        if (relations.isEmpty()) return Result.NoMangaRelation

        sourceManager.isInitialized.first { it }
        val sources = policy.manga(sourceManager.getCatalogueSources())
        val sourceIds = sources.map { it.id }.toSet()
        val cachedCopies = relations.flatMap {
            identities.matching("manga", it.id, it.malId, privateSession)
        }.distinct().filter { (id, _) -> id in sourceIds && !policy.privateManga(id) }
            .mapNotNull { (id, url) -> mangaRepository.getMangaByUrlAndSourceId(url, id) }
        val known = (mangaRepository.getMangaFavorites() + mangaRepository.getReadMangaNotInLibrary() + cachedCopies)
            .distinctBy { it.id }.filter { it.source in sourceIds }
        val tracks = mangaTracks.getMangaTracksAsFlow().first().groupBy { it.mangaId }
        return coroutineScope {
            val links = async(Dispatchers.IO) {
                (source as? RelatedMangaLinks)?.let { resolver ->
                    ContinuityRequests.optional(6_000) { resolver.relatedMangaLinks(anime.url) }
                }.orEmpty().take(3)
            }
            val progress = ContinuityProgress(
                relations.map { relation ->
                    Choice(
                        relation.id, relation.malId, relation.titles.firstOrNull().orEmpty(), null,
                        relation.format, relation.viaOriginalNovel, null, null,
                        known.filter { manga ->
                            hasSameIdentity(
                                relation.id,
                                relation.malId,
                                identities.get(
                                    "manga",
                                    manga.source,
                                    manga.url,
                                    privateSession || policy.privateManga(manga.source),
                                ),
                            ) &&
                                !conflictingTracks(relation, tracks[manga.id].orEmpty())
                        },
                        season,
                    )
                },
                Choice::catalogId,
            ) { choices, resolving -> onProgress(Result.Found(choices, watchedEpisode, resolving)) }
            progress.emit()
            relations.map { relation ->
                async(Dispatchers.IO) {
                    val metadata = async {
                        ContinuityRequests.metadata { AdaptationChapterCatalog.mangaBaka(relation.id) }
                    }
                    val matches = findVerifiedManga(relation, known, tracks, sources, links, privateSession) { found ->
                        progress.update(relation.id) { it.copy(matches = found) }
                    }
                    progress.update(relation.id, lookupFinished = true) { it.copy(matches = matches) }
                    val catalog = metadata.await()
                    val beginning = selectCheckpoint(catalog?.beginning.orEmpty(), season, allowUnscoped)
                    val ending = selectCheckpoint(catalog?.ending.orEmpty(), season, allowUnscoped)
                    val fallback = if (beginning == null || ending == null) {
                        matches.firstOrNull()?.let {
                            readMangaUpdatesCheckpoint(it, tracks[it.id].orEmpty(), privateSession)
                        }
                    } else {
                        null
                    }
                    progress.update(relation.id) {
                        it.copy(
                            coverUrl = catalog?.coverUrl,
                            beginning = beginning ?: selectCheckpoint(fallback?.first.orEmpty(), season, allowUnscoped),
                            latestAdapted = ending ?: selectCheckpoint(
                                fallback?.second.orEmpty(),
                                season,
                                allowUnscoped,
                            ),
                        )
                    }
                }
            }.awaitAll()
            Result.Found(progress.result(), watchedEpisode)
        }
    }

    private suspend fun findVerifiedManga(
        relation: AniListMediaLookup.MangaRelation,
        known: List<Manga>,
        tracks: Map<Long, List<MangaTrack>>,
        sources: List<CatalogueSource>,
        links: Deferred<List<String>>,
        privateSession: Boolean,
        onMatches: suspend (List<Manga>) -> Unit,
    ): List<Manga> = coroutineScope {
        val verified = known.filter { manga ->
            hasSameIdentity(
                relation.id,
                relation.malId,
                identities.get(
                    "manga",
                    manga.source,
                    manga.url,
                    privateSession || policy.privateManga(manga.source),
                ),
            ) &&
                !conflictingTracks(relation, tracks[manga.id].orEmpty())
        }.toMutableList()
        if (verified.isNotEmpty()) return@coroutineScope verified
        val names = relation.titles.map(TrackTitleMatcher::normalize).toSet()
        val candidates = known.filter { manga ->
            !conflictingTracks(relation, tracks[manga.id].orEmpty()) &&
                (
                    tracks[manga.id].orEmpty().any {
                        it.trackerId == TrackerManager.ANILIST &&
                            it.remoteId == relation.id ||
                            it.trackerId == 1L &&
                            it.remoteId == relation.malId
                    } ||
                        TrackTitleMatcher.normalize(manga.title) in names
                    )
        }.take(6)
        val mutex = kotlinx.coroutines.sync.Mutex()
        suspend fun accept(remote: SManga, source: CatalogueSource): Boolean {
            val details = verifiedDetails(remote, source, relation) ?: return false
            val local = toLocal.await(details.toDomainManga(source.id))
            if (conflictingTracks(relation, tracks[local.id].orEmpty())) return false
            identities.remember(
                "manga",
                source.id,
                details.url,
                SourceTrackingHints.from(details),
                privateSession || policy.privateManga(source.id),
            )
            mutex.withLock {
                if (verified.none { it.id == local.id }) verified += local
                onMatches(verified.toList())
            }
            return true
        }
        candidates.map { manga ->
            async {
                sources.firstOrNull { it.id == manga.source }?.let { source ->
                    ContinuityRequests.source { accept(manga.toSManga(), source) }
                }
            }
        }.awaitAll()
        if (verified.isNotEmpty()) return@coroutineScope verified.toList()
        // Query candidates by aliases, but open only a copy with matching source-supplied IDs.
        for (batch in sources.chunked(3)) {
            batch.map { source ->
                async {
                    ContinuityRequests.source {
                        if (source is MangaCatalogIdResolver) {
                            val remote = ContinuityRequests.optional(12_000) {
                                source.findMangaByCatalogId("anilist", relation.id)
                                    ?: relation.malId?.let { source.findMangaByCatalogId("myanimelist", it) }
                            }
                            if (remote != null && accept(remote, source)) return@source
                        }
                        if (source is MangaCatalogLinkResolver) {
                            for (link in links.await()) {
                                val remote = ContinuityRequests.optional(6_000) { source.findMangaByCatalogLink(link) }
                                if (remote != null && accept(remote, source)) return@source
                            }
                        }
                        for (title in relation.titles.filter(String::isNotBlank).distinct().take(2)) {
                            val page = ContinuityRequests.optional {
                                source.getSearchManga(1, title, source.getFilterList())
                            } ?: continue
                            for (remote in page.mangas.take(4)) {
                                if (accept(remote, source)) return@source
                            }
                        }
                    }
                }
            }.awaitAll()
            if (verified.isNotEmpty()) break
        }
        verified.toList()
    }

    private suspend fun verifiedDetails(
        remote: SManga,
        source: CatalogueSource,
        relation: AniListMediaLookup.MangaRelation,
    ): SManga? {
        val supplied = SourceTrackingHints.from(remote)
        val details = if (supplied?.anilistId != null || supplied?.malId != null) {
            remote
        } else {
            ContinuityRequests.optional {
                MangaSourceUpdateGate.await(
                    source,
                    remote,
                    emptyList(),
                    fetchDetails = true,
                    fetchChapters = false,
                ).manga
            } ?: return null
        }
        if (!hasSameIdentity(relation.id, relation.malId, SourceTrackingHints.from(details))) return null
        details.url = remote.url
        return details
    }

    private suspend fun readMangaUpdatesCheckpoint(
        manga: Manga,
        tracks: List<MangaTrack>,
        privateSession: Boolean,
    ): Pair<List<Checkpoint>, List<Checkpoint>>? {
        val id = tracks.firstOrNull { it.trackerId == 7L && it.remoteId > 0 }?.remoteId
            ?: identities.get(
                "manga",
                manga.source,
                manga.url,
                privateSession || policy.privateManga(manga.source),
            )
                ?.mangaUpdatesId ?: return null
        val metadata = ContinuityRequests.metadata { AdaptationChapterCatalog.mangaUpdates(id) } ?: return null
        return metadata.beginning to metadata.ending
    }

    private fun conflictingTracks(
        relation: AniListMediaLookup.MangaRelation,
        tracks: List<MangaTrack>,
    ) = tracks.any {
        it.trackerId == TrackerManager.ANILIST &&
            it.remoteId != relation.id ||
            it.trackerId == 1L &&
            relation.malId != null &&
            it.remoteId != relation.malId
    }

    companion object {
        private val chapterPattern = Regex("(?i)\\b(?:chap(?:ter)?|ch\\.?)[ .:#]*(\\d+(?:\\.\\d+)?)\\b")
        private val episodePattern = Regex("(?i)\\b(?:ep(?:isode)?)[ .:#]*(\\d+)\\b")
        private val pagePattern = Regex("(?i)\\bpage[ .:#]*(\\d+)\\b")
        private val seasonEpisodePattern =
            Regex("(?i)\\bS(?:eason)?[ .:#]*(\\d+)[ .:-]*E(?:p(?:isode)?)?[ .:#]*(\\d+)\\b")
        private val seasonPattern = Regex("(?i)\\b(?:s(?:eason)?|stagione)[ .:#]*(\\d{1,2})\\b")
        private val exactEpisodePattern =
            Regex("(?i)\\b(?:adapted|covered|animated)\\s+in\\s+(?:ep(?:isode)?)[ .:#]*\\d+\\b")

        internal fun checkpoint(raw: String?): Checkpoint? = checkpoints(raw).singleOrNull()

        internal fun checkpoints(raw: String?): List<Checkpoint> {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 } ?: return emptyList()
            return text.replace(Regex("(?i)<br\\s*/?>"), "\n").split(Regex("[\\r\\n;]+"))
                .flatMap { line ->
                    val references = chapterPattern.findAll(line).toList()
                    // Repeated chapter mentions in a note belong to the same reference.
                    val groups = references.fold(mutableListOf<MutableList<MatchResult>>()) { groups, reference ->
                        if (groups.lastOrNull()?.last()?.groupValues?.get(1) == reference.groupValues[1]) {
                            groups.last().add(reference)
                        } else {
                            groups.add(mutableListOf(reference))
                        }
                        groups
                    }
                    groups.mapIndexedNotNull { index, group ->
                        val chapter = group.first().groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                            ?: return@mapIndexedNotNull null
                        val start = if (index == 0) 0 else group.first().range.first
                        val end = groups.getOrNull(index + 1)?.first()?.range?.first ?: line.length
                        val note = line.substring(start, end).trim().takeIf { it.length <= 512 }
                            ?: return@mapIndexedNotNull null
                        val seasons = (seasonPattern.findAll(note) + seasonEpisodePattern.findAll(note)).mapNotNull {
                            it.groupValues[1].toIntOrNull()?.takeIf { number -> number > 0 }
                        }.distinct().toList()
                        // Ambiguous prose is not turned into a season/chapter assignment.
                        if (seasons.size > 1) return@mapIndexedNotNull null
                        val seasonEpisode = seasonEpisodePattern.find(note)
                        val episode = seasonEpisode?.groupValues?.get(2)?.toIntOrNull()
                            ?: episodePattern.find(note)?.groupValues?.get(1)?.toIntOrNull()
                        Checkpoint(
                            chapter,
                            note,
                            episode,
                            exactEpisodePattern.containsMatchIn(note),
                            seasons.singleOrNull(),
                            pagePattern.find(note)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 },
                        )
                    }
                }.distinctBy { listOf(it.chapter, it.season, it.episode, it.exactEpisode, it.page) }
        }

        internal fun selectCheckpoint(points: List<Checkpoint>, season: Int?, allowUnscoped: Boolean): Checkpoint? {
            if (season == null) return points.filter { it.season == null }.singleOrNull()
            val scoped = points.filter { it.season == season }
            if (scoped.isNotEmpty()) return scoped.singleOrNull()
            // A series-wide endpoint must not be labelled as the endpoint of a sequel.
            return if (allowUnscoped && points.none { it.season != null }) points.singleOrNull() else null
        }

        internal fun hasSameIdentity(anilistId: Long, malId: Long?, hints: SourceTrackingHints?): Boolean {
            if (hints == null) return false
            if (hints.anilistId != null && hints.anilistId != anilistId) return false
            if (malId != null && hints.malId != null && hints.malId != malId) return false
            return hints.anilistId == anilistId || (malId != null && hints.malId == malId)
        }
    }
}
