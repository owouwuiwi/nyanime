package tachiyomi.domain.entries.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ReleaseScheduleTest {
    private val today = LocalDate.of(2024, 7, 1)
    private fun schedule(vararg days: String, zone: ZoneId = ZoneOffset.UTC, now: LocalDate = today) =
        ReleaseSchedule.estimate(
            days.map {
                val epoch = LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli()
                ReleaseDates(epoch, epoch)
            },
            zone,
            now,
        )

    @Test
    fun `monthly releases retain the calendar day despite unequal months`() {
        val value = schedule("2024-03-15", "2024-04-15", "2024-05-15", "2024-06-15", "2024-02-15")
        assertEquals(LocalDate.of(2024, 7, 15), value.nextCheck(today))
    }

    @Test
    fun `month end and last weekday handle leap years`() {
        val end = ReleasePattern.DaysOfMonth(setOf(31))
        assertEquals(LocalDate.of(2024, 2, 29), end.nextAfter(LocalDate.of(2024, 1, 31)))
        val weekday = ReleasePattern.WeekdayOfMonth(ReleasePattern.LAST_WEEK, DayOfWeek.FRIDAY)
        assertEquals(LocalDate.of(2024, 2, 23), weekday.nextAfter(LocalDate.of(2024, 1, 26)))
    }

    @Test
    fun `two monthly release dates remain distinct`() {
        val pattern = ReleasePattern.DaysOfMonth(setOf(1, 15))
        assertEquals(LocalDate.of(2024, 7, 15), pattern.nextAfter(today))
        assertEquals(today, pattern.previousBefore(LocalDate.of(2024, 7, 15)))
    }

    @Test
    fun `weekly phase survives a late release`() {
        val days = listOf("2024-05-27", "2024-06-03", "2024-06-10", "2024-06-18", "2024-06-24")
        val value = schedule(*days.toTypedArray())
        assertEquals(7, value.interval)
        assertEquals(today, value.nextCheck(today))
        assertEquals(LocalDate.of(2024, 7, 8), value.nextCheckAfterRelease(today))
    }

    @Test
    fun `batch releases do not become a daily schedule`() {
        val value = schedule(
            "2024-06-01", "2024-06-02", "2024-06-15", "2024-06-16", "2024-06-29",
            "2024-06-30", "2024-05-18", "2024-05-19", "2024-05-04",
        )
        assertEquals(14, value.interval)
        assertEquals(LocalDate.of(2024, 7, 13), value.nextCheckAfterRelease(today))
    }

    @Test
    fun `older releases and same-day batches preserve the recent pattern`() {
        val a = schedule("2024-06-03", "2024-06-10", "2024-06-17", "2024-06-24")
        val b = schedule("2024-06-03", "2024-06-10", "2024-06-17", "2024-06-24", "2024-06-24", "2023-01-01")
        assertEquals(a.nextCheck(today), b.nextCheck(today))
    }

    @Test
    fun `future upload dates are ignored while observed fetch dates remain usable`() {
        val recent = listOf(today.minusDays(7), today.minusDays(14), today.minusDays(21))
        val values = recent.map {
            ReleaseDates(
                today.plusYears(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
        }
        val value = ReleaseSchedule.estimate(values, ZoneOffset.UTC, today)
        assertEquals(today.minusDays(7), value.lastRelease)
        assertEquals(7, value.interval)
    }

    @Test
    fun `invalid zero timestamps do not become releases in 1970`() {
        val value = ReleaseSchedule.estimate(List(20) { ReleaseDates(0, -1) }, ZoneOffset.UTC, today)
        assertEquals(null, value.lastRelease)
        assertEquals(7, value.interval)
        assertEquals(today.plusDays(7), value.nextCheck(today))
    }

    @Test
    fun `hiatus spreads checks but never leaves a title dormant indefinitely`() {
        val value = schedule("2023-06-05", "2023-06-12", "2023-06-19", "2023-06-26")
        assertTrue(value.nextCheck(today) in today..today.plusDays(28))
    }

    @Test
    fun `fixed user interval does not adopt automatic hiatus backoff`() {
        val value = ReleaseSchedule.fixed(7, today.minusDays(365))
        assertTrue(value.nextCheck(today) in today..today.plusDays(7))
    }

    @Test
    fun `calendar days survive daylight saving changes`() {
        val zone = ZoneId.of("Europe/Rome")
        val now = LocalDate.of(2024, 4, 7)
        val value = schedule("2024-03-10", "2024-03-17", "2024-03-24", "2024-03-31", zone = zone, now = now)
        assertEquals(now, value.nextCheck(now))
        assertEquals(now.plusDays(7), value.nextCheckAfterRelease(now))
    }
}
