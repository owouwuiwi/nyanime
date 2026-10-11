package eu.kanade.tachiyomi.data.releases

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupReleaseSubscription
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Stored in each content database: notices commit with the episode/chapter snapshot. */
class ReleaseStore(
    private val anime: AnimeDatabaseHandler = Injekt.get(),
    private val manga: MangaDatabaseHandler = Injekt.get(),
) {
    data class Notice(
        val itemId: Long,
        val entryId: Long,
        val createdAt: Long,
        val sourceAt: Long = 0,
        val number: Double = -1.0,
    )

    fun subscriptionFlow(medium: ReleaseMedium, id: Long): Flow<ReleaseSubscription?> = when (medium) {
        ReleaseMedium.ANIME -> anime.subscribeToOneOrNull {
            releaseMonitorQueries.getSubscription(id) { _, mode, availability, reminder ->
                subscription(mode, availability, reminder)
            }
        }
        ReleaseMedium.MANGA -> manga.subscribeToOneOrNull {
            releaseMonitorQueries.getSubscription(id) { _, mode, availability, reminder ->
                subscription(mode, availability, reminder)
            }
        }
    }

    suspend fun subscription(medium: ReleaseMedium, id: Long): ReleaseSubscription = when (medium) {
        ReleaseMedium.ANIME -> anime.awaitOneOrNull {
            releaseMonitorQueries.getSubscription(id) { _, mode, availability, reminder ->
                subscription(mode, availability, reminder)
            }
        }
        ReleaseMedium.MANGA -> manga.awaitOneOrNull {
            releaseMonitorQueries.getSubscription(id) { _, mode, availability, reminder ->
                subscription(mode, availability, reminder)
            }
        }
    } ?: ReleaseSubscription()

    suspend fun setSubscription(medium: ReleaseMedium, id: Long, value: ReleaseSubscription) {
        setSubscriptions(medium, mapOf(id to value))
    }

    /** A merged title changes all its concrete subscriptions in one transaction. */
    suspend fun setSubscriptions(medium: ReleaseMedium, values: Map<Long, ReleaseSubscription>) {
        when (medium) {
            ReleaseMedium.ANIME -> anime.await(inTransaction = true) {
                values.forEach { (id, value) ->
                    releaseMonitorQueries.setSubscription(
                        id,
                        value.mode.name,
                        value.availability.toDb(),
                        value.reminder.toDb(),
                    )
                    releaseMonitorQueries.makeDue(id)
                }
            }
            ReleaseMedium.MANGA -> manga.await(inTransaction = true) {
                values.forEach { (id, value) ->
                    releaseMonitorQueries.setSubscription(
                        id,
                        value.mode.name,
                        value.availability.toDb(),
                        value.reminder.toDb(),
                    )
                    releaseMonitorQueries.makeDue(id)
                }
            }
        }
    }

    suspend fun monitoredIds(medium: ReleaseMedium): List<Long> = when (medium) {
        ReleaseMedium.ANIME -> anime.awaitList { releaseMonitorQueries.getMonitoredIds() }
        ReleaseMedium.MANGA -> manga.awaitList { releaseMonitorQueries.getMonitoredIds() }
    }

    fun monitoredFlow(medium: ReleaseMedium): Flow<List<Long>> = when (medium) {
        ReleaseMedium.ANIME -> anime.subscribeToList { releaseMonitorQueries.getMonitoredIds() }
        ReleaseMedium.MANGA -> manga.subscribeToList { releaseMonitorQueries.getMonitoredIds() }
    }

    suspend fun check(medium: ReleaseMedium, id: Long): ReleaseCheckState = when (medium) {
        ReleaseMedium.ANIME -> anime.awaitOneOrNull {
            releaseMonitorQueries.getCheck(id) { _, attempt, success, next, failures, metadata ->
                ReleaseCheckState(attempt, success, next, failures.toInt(), metadata)
            }
        }
        ReleaseMedium.MANGA -> manga.awaitOneOrNull {
            releaseMonitorQueries.getCheck(id) { _, attempt, success, next, failures, metadata ->
                ReleaseCheckState(attempt, success, next, failures.toInt(), metadata)
            }
        }
    } ?: ReleaseCheckState()

    suspend fun alignSchedule(events: List<AiringEvent>, now: Long = System.currentTimeMillis()) {
        for ((id, dates) in events.groupBy { it.entryId }) {
            if (ReleaseEligibility.source(ReleaseMedium.ANIME, id) == null) continue
            val state = check(ReleaseMedium.ANIME, id)
            if (state.failures > 0 || state.lastSuccess == 0L) continue
            val at = dates.firstOrNull { it.airingAt >= now - 6 * ReleasePolicy.HOUR }?.airingAt ?: continue
            val next = now + ReleasePolicy.interval(now, at, true, false)
            anime.await { releaseMonitorQueries.bringForward(next, id) }
        }
    }

    suspend fun markAttempt(medium: ReleaseMedium, id: Long, now: Long) {
        when (medium) {
            ReleaseMedium.ANIME -> anime.await { releaseMonitorQueries.markAttempt(id, now) }
            ReleaseMedium.MANGA -> manga.await { releaseMonitorQueries.markAttempt(id, now) }
        }
    }

    suspend fun metadataChecked(medium: ReleaseMedium, id: Long, now: Long) {
        when (medium) {
            ReleaseMedium.ANIME -> anime.await { releaseMonitorQueries.markMetadata(now, id) }
            ReleaseMedium.MANGA -> manga.await { releaseMonitorQueries.markMetadata(now, id) }
        }
    }

    suspend fun markFailure(medium: ReleaseMedium, id: Long, now: Long, retryAfter: Long = 0) {
        val next = now + maxOf(ReleasePolicy.retryDelay(check(medium, id).failures + 1), retryAfter)
        when (medium) {
            ReleaseMedium.ANIME -> anime.await { releaseMonitorQueries.markFailure(id, now, next) }
            ReleaseMedium.MANGA -> manga.await { releaseMonitorQueries.markFailure(id, now, next) }
        }
    }

    /** Call inside the same database transaction as synchronization, after URL replacements are filtered. */
    suspend fun committed(
        medium: ReleaseMedium,
        id: Long,
        items: List<Long>,
        firstSnapshot: Boolean,
        completed: Boolean,
        airingAt: Long? = null,
        sourceDates: Map<Long, Long> = emptyMap(),
    ) {
        val now = System.currentTimeMillis()
        var announced = airingAt
        var missing = false
        var verifiedComplete = completed
        if (medium == ReleaseMedium.ANIME) {
            anime.await {
                val catalog = airingQueries.getCache(id).executeAsOneOrNull()
                val numbers = episodesQueries.getEpisodesByAnimeId(id).executeAsList().map { it.episode_number }.toSet()
                val absent = airingQueries.getEntryEvents(id).executeAsList().firstOrNull {
                    it.airing_at >= now - 2 * ReleasePolicy.DAY && it.episode.toDouble() !in numbers
                }
                announced = airingAt ?: absent?.airing_at
                missing = absent != null
                verifiedComplete = completed &&
                    catalog?.finished == 1L &&
                    catalog.total_episodes in 1..65535 &&
                    (1..catalog.total_episodes.toInt()).all { it.toDouble() in numbers }
            }
        }
        val next = now + ReleasePolicy.interval(now, announced, missing, verifiedComplete)
        val capture = !ReleaseRestoreGuard.active &&
            !Injekt.get<BasePreferences>().incognitoMode().get() &&
            ReleaseEligibility.source(medium, id) != null &&
            id in monitoredIds(medium)
        when (medium) {
            ReleaseMedium.ANIME -> anime.await(inTransaction = true) {
                sourceDates.filterValues { it in 1..now }.forEach { (item, date) ->
                    releaseMonitorQueries.verifyPublicationDate(date, item)
                }
                if (!firstSnapshot &&
                    capture
                ) {
                    items.forEach {
                        releaseMonitorQueries.queueNotice(
                            it,
                            id,
                            now,
                            sourceDates[it]?.takeIf { date -> date in 1..now } ?: 0,
                        )
                    }
                }
                releaseMonitorQueries.markSuccess(id, now, next)
            }
            ReleaseMedium.MANGA -> manga.await(inTransaction = true) {
                sourceDates.filterValues { it in 1..now }.forEach { (item, date) ->
                    releaseMonitorQueries.verifyPublicationDate(date, item)
                }
                if (!firstSnapshot &&
                    capture
                ) {
                    items.forEach {
                        releaseMonitorQueries.queueNotice(
                            it,
                            id,
                            now,
                            sourceDates[it]?.takeIf { date -> date in 1..now } ?: 0,
                        )
                    }
                }
                releaseMonitorQueries.markSuccess(id, now, next)
            }
        }
    }

    suspend fun pending(medium: ReleaseMedium): List<Notice> = when (medium) {
        ReleaseMedium.ANIME -> anime.awaitList {
            releaseMonitorQueries.getPending { item, entry, created, _, sourceAt ->
                Notice(item, entry, created, sourceAt)
            }
        }
        ReleaseMedium.MANGA -> manga.awaitList {
            releaseMonitorQueries.getPending { item, entry, created, _, sourceAt ->
                Notice(item, entry, created, sourceAt)
            }
        }
    }

    fun noticeFlow(medium: ReleaseMedium): Flow<List<Notice>> = when (medium) {
        ReleaseMedium.ANIME -> anime.subscribeToList {
            releaseMonitorQueries.getNotices { item, entry, created, _, sourceAt, number ->
                Notice(item, entry, created, sourceAt, number)
            }
        }
        ReleaseMedium.MANGA -> manga.subscribeToList {
            releaseMonitorQueries.getNotices { item, entry, created, _, sourceAt, number ->
                Notice(item, entry, created, sourceAt, number)
            }
        }
    }

    suspend fun delivered(medium: ReleaseMedium, items: List<Long>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        when (medium) {
            ReleaseMedium.ANIME -> anime.await { releaseMonitorQueries.markDelivered(now, items) }
            ReleaseMedium.MANGA -> manga.await { releaseMonitorQueries.markDelivered(now, items) }
        }
    }

    suspend fun backup(): List<BackupReleaseSubscription> {
        val result = ArrayList<BackupReleaseSubscription>()
        val video = anime.awaitList { releaseMonitorQueries.getSubscriptions() }
        val comics = manga.awaitList { releaseMonitorQueries.getSubscriptions() }
        for (item in video) {
            val entry = Injekt.get<AnimeRepository>().getAnimeById(item.entry_id)
            result +=
                BackupReleaseSubscription(
                    "ANIME",
                    entry.source,
                    entry.url,
                    item.mode,
                    item.availability != 0L,
                    item.reminder != 0L,
                )
        }
        for (item in comics) {
            val entry = Injekt.get<MangaRepository>().getMangaById(item.entry_id)
            result +=
                BackupReleaseSubscription(
                    "MANGA",
                    entry.source,
                    entry.url,
                    item.mode,
                    item.availability != 0L,
                    item.reminder != 0L,
                )
        }
        return result
    }

    suspend fun restore(values: List<BackupReleaseSubscription>) {
        for (value in values) {
            val medium = ReleaseMedium.entries.firstOrNull { it.name == value.medium } ?: continue
            val mode = FollowMode.entries.firstOrNull { it.name == value.mode } ?: continue
            val id = when (medium) {
                ReleaseMedium.ANIME -> Injekt.get<AnimeRepository>().getAnimeByUrlAndSourceId(
                    value.url,
                    value.source,
                )?.id
                ReleaseMedium.MANGA -> Injekt.get<MangaRepository>().getMangaByUrlAndSourceId(
                    value.url,
                    value.source,
                )?.id
            } ?: continue
            setSubscription(medium, id, ReleaseSubscription(mode, value.availability, value.reminder))
        }
    }

    private fun Boolean.toDb() = if (this) 1L else 0L

    private fun subscription(mode: String, availability: Long, reminder: Long) = ReleaseSubscription(
        FollowMode.entries.firstOrNull { it.name == mode } ?: FollowMode.AUTO,
        availability != 0L,
        reminder != 0L,
    )
}
