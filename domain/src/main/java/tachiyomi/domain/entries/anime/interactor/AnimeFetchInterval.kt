package tachiyomi.domain.entries.anime.interactor

import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.entries.interactor.ReleaseDates
import tachiyomi.domain.entries.interactor.ReleaseSchedule
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.model.Episode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.absoluteValue

class AnimeFetchInterval(private val getEpisodesByAnimeId: GetEpisodesByAnimeId) {
    suspend fun toAnimeUpdate(
        anime: Anime,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
        foundRelease: Boolean = false,
    ): AnimeUpdate {
        val today = dateTime.toLocalDate()
        val schedule = if (anime.fetchInterval < 0) {
            val anchor = if (foundRelease || anime.lastUpdate <= 0L) {
                today
            } else {
                Instant.ofEpochMilli(anime.lastUpdate).atZone(dateTime.zone).toLocalDate()
            }
            ReleaseSchedule.fixed(
                anime.fetchInterval.toLong().absoluteValue.coerceIn(1, MAX_INTERVAL.toLong()).toInt(),
                anchor,
            )
        } else {
            ReleaseSchedule.estimate(
                getEpisodesByAnimeId.await(anime.id).map { ReleaseDates(it.dateUpload, it.dateFetch) },
                dateTime.zone,
                today,
            )
        }
        val currentWindow = window.takeUnless { it.first == 0L && it.second == 0L } ?: getWindow(dateTime)
        val expectedDay = anime.nextUpdate.takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it).atZone(dateTime.zone).toLocalDate() }
        val awaitingRelease = expectedDay != null &&
            anime.nextUpdate in currentWindow.first..currentWindow.second + 1 &&
            (schedule.lastRelease == null || schedule.lastRelease < expectedDay)
        val next = when {
            foundRelease -> schedule.nextCheckAfterRelease(today).atStartOfDay(dateTime.zone).toInstant().toEpochMilli()
            awaitingRelease -> anime.nextUpdate
            else -> schedule.nextCheck(today).atStartOfDay(dateTime.zone).toInstant().toEpochMilli()
        }
        return AnimeUpdate(
            id = anime.id,
            nextUpdate = next,
            fetchInterval = anime.fetchInterval.takeIf { it < 0 } ?: schedule.interval,
        )
    }

    fun getWindow(dateTime: ZonedDateTime): Pair<Long, Long> {
        val today = dateTime.toLocalDate()
        return today.minusDays(1).atStartOfDay(dateTime.zone).toInstant().toEpochMilli() to
            today.plusDays(1).atStartOfDay(dateTime.zone).toInstant().toEpochMilli() - 1
    }

    internal fun calculateInterval(episodes: List<Episode>, zone: ZoneId): Int =
        ReleaseSchedule.estimate(
            episodes.map {
                ReleaseDates(it.dateUpload, it.dateFetch)
            },
            zone,
            LocalDate.now(zone),
        ).interval

    companion object {
        const val MAX_INTERVAL = 28
    }
}
