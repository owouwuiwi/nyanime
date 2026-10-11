package tachiyomi.domain.entries.manga.interactor

import tachiyomi.domain.entries.interactor.ReleaseDates
import tachiyomi.domain.entries.interactor.ReleaseSchedule
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaUpdate
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.model.Chapter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.absoluteValue

class MangaFetchInterval(private val getChaptersByMangaId: GetChaptersByMangaId) {
    suspend fun toMangaUpdate(
        manga: Manga,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
        foundRelease: Boolean = false,
    ): MangaUpdate {
        val today = dateTime.toLocalDate()
        val schedule = if (manga.fetchInterval < 0) {
            val anchor = if (foundRelease || manga.lastUpdate <= 0L) {
                today
            } else {
                Instant.ofEpochMilli(manga.lastUpdate).atZone(dateTime.zone).toLocalDate()
            }
            ReleaseSchedule.fixed(
                manga.fetchInterval.toLong().absoluteValue.coerceIn(1, MAX_INTERVAL.toLong()).toInt(),
                anchor,
            )
        } else {
            ReleaseSchedule.estimate(
                getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true).map {
                    ReleaseDates(it.dateUpload, it.dateFetch)
                },
                dateTime.zone,
                today,
            )
        }
        val currentWindow = window.takeUnless { it.first == 0L && it.second == 0L } ?: getWindow(dateTime)
        val expectedDay = manga.nextUpdate.takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it).atZone(dateTime.zone).toLocalDate() }
        val awaitingRelease = expectedDay != null &&
            manga.nextUpdate in currentWindow.first..currentWindow.second + 1 &&
            (schedule.lastRelease == null || schedule.lastRelease < expectedDay)
        val next = when {
            foundRelease -> schedule.nextCheckAfterRelease(today).atStartOfDay(dateTime.zone).toInstant().toEpochMilli()
            awaitingRelease -> manga.nextUpdate
            else -> schedule.nextCheck(today).atStartOfDay(dateTime.zone).toInstant().toEpochMilli()
        }
        return MangaUpdate(
            id = manga.id,
            nextUpdate = next,
            fetchInterval = manga.fetchInterval.takeIf { it < 0 } ?: schedule.interval,
        )
    }

    fun getWindow(dateTime: ZonedDateTime): Pair<Long, Long> {
        val today = dateTime.toLocalDate()
        return today.minusDays(1).atStartOfDay(dateTime.zone).toInstant().toEpochMilli() to
            today.plusDays(1).atStartOfDay(dateTime.zone).toInstant().toEpochMilli() - 1
    }

    internal fun calculateInterval(chapters: List<Chapter>, zone: ZoneId): Int =
        ReleaseSchedule.estimate(
            chapters.map {
                ReleaseDates(it.dateUpload, it.dateFetch)
            },
            zone,
            LocalDate.now(zone),
        ).interval

    companion object {
        const val MAX_INTERVAL = 28
    }
}
