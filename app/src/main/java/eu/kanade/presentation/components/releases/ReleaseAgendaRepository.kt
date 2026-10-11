package eu.kanade.presentation.components.releases

import android.app.Application
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.AiringCatalogReference
import eu.kanade.tachiyomi.data.releases.AiringIssue
import eu.kanade.tachiyomi.data.releases.AiringRepository
import eu.kanade.tachiyomi.data.releases.ChapterScheduleRepository
import eu.kanade.tachiyomi.data.releases.ReleaseEligibility
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleasePreferences
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.model.asMangaCover
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Shared local-only projection for the agenda and its home-screen widget. */
internal class ReleaseAgendaRepository {
    private val store = ReleaseStore()
    private val app: Application = Injekt.get()
    data class Snapshot(
        val items: List<ReleaseAgendaItem>,
        val warnings: Map<ReleaseMedium, String> = emptyMap(),
        val issues: List<AiringIssue> = emptyList(),
    )
    fun snapshots(scope: ReleaseMedium? = null): Flow<Snapshot> {
        val snapshots = if (scope == null) {
            combine(anime(), manga()) { a, m -> Snapshot(a.items + m.items, a.warnings + m.warnings, a.issues) }
        } else if (scope == ReleaseMedium.ANIME) {
            anime()
        } else {
            manga()
        }
        return combine(
            snapshots,
            Injekt.get<eu.kanade.domain.ui.UiPreferences>().dismissedLibraryUpdates().changes(),
            ReleasePreferences().dismissedAgenda.changes(),
        ) {
                snapshot,
                dismissed,
                agendaDismissed,
            ->
            snapshot.copy(
                items = snapshot.items.filterNot {
                    ReleaseAgendaActions.dismissed(it, agendaDismissed) ||
                        it.dismissalKey in dismissed ||
                        it.choices.any { choice -> choice.dismissalKey in dismissed }
                },
            )
        }
    }
    private fun anime(): Flow<Snapshot> {
        val repository = AiringRepository()
        return combine(
            repository.effectiveEvents(),
            store.monitoredFlow(ReleaseMedium.ANIME),
            store.noticeFlow(ReleaseMedium.ANIME),
            repository.caches(),
        ) { events, ids, notices, caches ->
            val items = mutableListOf<ReleaseAgendaItem>()
            val anime: AnimeRepository = Injekt.get()
            val episodes: EpisodeRepository = Injekt.get()
            val issues = mutableListOf<AiringIssue>()
            val works = mutableListOf<Anime>()
            val presentByEntry = mutableMapOf<Long, Set<Double>>()
            val watchedByEntry = mutableMapOf<Long, Set<Double>>()
            val groupedEvents = events.groupBy { it.entryId }
            val groupedNotices = notices.groupBy { it.entryId }
            for (id in ids) {
                if (ReleaseEligibility.source(ReleaseMedium.ANIME, id) == null) continue
                val cache = caches[id]
                val entryEvents = groupedEvents[id].orEmpty()
                val entryNotices = groupedNotices[id].orEmpty()
                val entry = anime.getAnimeById(id)
                val presentation = entry.homePresentation ?: SourceHomePresentation()
                val hints = SourceTrackingHints.from(entry)
                val tracks = Injekt.get<AnimeTrackRepository>().getTracksByAnimeId(id)
                val hasId = AiringCatalogReference.from(entry, tracks).hasId ||
                    tracks.any { it.trackerId == TrackerManager.SIMKL && it.remoteId > 0 }
                AiringIssue.reason(cache, hasId)?.let { reason ->
                    issues += AiringIssue(id, entry.title, entry.asAnimeCover(), reason)
                }
                val trackIds = buildMap {
                    tracks.forEach { track ->
                        when (track.trackerId) {
                            TrackerManager.ANILIST -> put("anilist", track.remoteId)
                            1L -> put("myanimelist", track.remoteId)
                        }
                    }
                }
                val hintIds = buildMap {
                    hints?.anilistId?.let { put("anilist", it) }
                    hints?.malId?.let { put("myanimelist", it) }
                }
                val eventIds = entryEvents.map { it.catalogId }.filter { it > 0 }.distinct()
                val ids = ReleaseAgendaMerge.verifiedIds(
                    presentation.catalogIds,
                    hintIds,
                    trackIds,
                    eventIds.singleOrNull()?.let { mapOf("anilist" to it) }.orEmpty(),
                )
                val identity = if (ids == null || eventIds.size > 1) {
                    SourceHomePresentation() // Contradictory IDs also disable the title/year fallback.
                } else {
                    presentation.copy(catalogIds = ids, choices = emptyList())
                }
                works += entry.copy(memo = identity.attachTo(entry.memo))
                val sourceLabel = Injekt.get<AnimeSourceManager>().getOrStub(entry.source).name
                val present = episodes.getEpisodeByAnimeId(id)
                val numbers = present.map { it.episodeNumber }.toSet()
                presentByEntry[id] = numbers
                watchedByEntry[id] = present.filter { it.seen }.map { it.episodeNumber }.toSet()
                val byId = present.associateBy { it.id }
                for (event in entryEvents) {
                    if (event.airingAt <= System.currentTimeMillis() && event.episode.toDouble() in numbers) continue
                    items += ReleaseAgendaItem(
                        "anime-planned-$id-${event.episode}",
                        id,
                        entry.title,
                        entry.asAnimeCover(),
                        event.airingAt,
                        scheduleBroadcastLabel(app, event),
                        number = event.episode.toDouble(),
                        sourceLabel = sourceLabel,
                        broadcasts = event.variants,
                        agendaKeys = setOf(
                            ReleaseAgendaActions.key(
                                ReleaseMedium.ANIME,
                                entry.source,
                                entry.url,
                                event.episode.toDouble(),
                            ),
                        ),
                    )
                }
                for (notice in entryNotices) {
                    val item = byId[notice.itemId]?.takeUnless { it.seen } ?: continue
                    if (notice.sourceAt <= 0) continue
                    items += ReleaseAgendaItem(
                        "anime-available-${item.id}",
                        id,
                        entry.title,
                        entry.asAnimeCover(),
                        notice.sourceAt,
                        app.getString(
                            R.string.release_content_available,
                            item.name,
                        ),
                        item.id,
                        dismissalKey = "${item.dateFetch}|anime|${entry.source}|${entry.title}|${item.name}",
                        number = item.episodeNumber,
                        sourceLabel = sourceLabel,
                        agendaKeys = setOf(
                            ReleaseAgendaActions.key(
                                ReleaseMedium.ANIME,
                                entry.source,
                                entry.url,
                                item.episodeNumber,
                                item.url,
                            ),
                        ),
                    )
                }
            }
            val warning = when {
                issues.any {
                    it.reason == AiringIssue.Reason.UNAVAILABLE
                } -> app.getString(R.string.release_calendar_error)
                issues.any { it.reason == AiringIssue.Reason.UNVERIFIED_ID } ->
                    app.getString(R.string.release_calendar_unresolved)
                issues.isNotEmpty() -> app.getString(R.string.release_calendar_pending)
                else -> null
            }
            Snapshot(
                ReleaseAgendaMerge.merge(items, works, presentByEntry, watchedByEntry),
                warning?.let { mapOf(ReleaseMedium.ANIME to it) }.orEmpty(),
                issues,
            )
        }
    }

    private fun manga(): Flow<Snapshot> = combine(
        ChapterScheduleRepository().events(),
        store.monitoredFlow(ReleaseMedium.MANGA),
        store.noticeFlow(ReleaseMedium.MANGA),
    ) { events, ids, notices ->
        val items = mutableListOf<ReleaseAgendaItem>()
        val manga: MangaRepository = Injekt.get()
        val chapters: ChapterRepository = Injekt.get()
        val groupedEvents = events.groupBy { it.entryId }
        val groupedNotices = notices.groupBy { it.entryId }
        for (id in ids) {
            if (ReleaseEligibility.source(ReleaseMedium.MANGA, id) == null) continue
            val entryEvents = groupedEvents[id].orEmpty()
            val entryNotices = groupedNotices[id].orEmpty()
            if (entryEvents.isEmpty() && entryNotices.isEmpty()) continue
            val entry = manga.getMangaById(id)
            val present = chapters.getChapterByMangaId(id)
            val numbers = present.map { it.chapterNumber }.toSet()
            val byId = present.associateBy { it.id }
            for (event in entryEvents) {
                if (event.release.releaseAt <= System.currentTimeMillis() && event.release.number in numbers) continue
                items += ReleaseAgendaItem(
                    "manga-planned-$id-${event.release.number}",
                    id,
                    entry.title,
                    entry.asMangaCover(),
                    event.release.releaseAt,
                    app.getString(R.string.release_chapter_planned, event.release.number.toString()),
                    medium = ReleaseMedium.MANGA,
                    number = event.release.number,
                    agendaKeys = setOf(
                        ReleaseAgendaActions.key(ReleaseMedium.MANGA, entry.source, entry.url, event.release.number),
                    ),
                )
            }
            for (notice in entryNotices) {
                val item = byId[notice.itemId]?.takeUnless { it.read } ?: continue
                if (notice.sourceAt <= 0) continue
                items += ReleaseAgendaItem(
                    "manga-available-${item.id}",
                    id,
                    entry.title,
                    entry.asMangaCover(),
                    notice.sourceAt,
                    app.getString(
                        R.string.release_content_available,
                        item.name,
                    ),
                    item.id,
                    ReleaseMedium.MANGA,
                    dismissalKey = "${item.dateFetch}|manga|${entry.source}|${entry.title}|${item.name}",
                    number = item.chapterNumber,
                    agendaKeys = setOf(
                        ReleaseAgendaActions.key(
                            ReleaseMedium.MANGA,
                            entry.source,
                            entry.url,
                            null,
                            item.url,
                        ),
                    ),
                    // Keep available editions separate; an earlier hidden announcement still applies.
                    plannedAgendaKeys = if (item.chapterNumber.isFinite() && item.chapterNumber > 0) {
                        setOf(
                            ReleaseAgendaActions.key(ReleaseMedium.MANGA, entry.source, entry.url, item.chapterNumber),
                        )
                    } else {
                        emptySet()
                    },
                )
            }
        }
        Snapshot(items)
    }
}
