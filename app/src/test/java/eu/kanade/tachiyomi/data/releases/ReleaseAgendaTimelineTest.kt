package eu.kanade.tachiyomi.data.releases

import eu.kanade.presentation.components.releases.ReleaseAgendaItem
import eu.kanade.presentation.components.releases.ReleaseAgendaTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class ReleaseAgendaTimelineTest {
    private val today = LocalDate.of(2026, 9, 28)

    private fun item(key: String, offset: Long, hour: Int = 12, medium: ReleaseMedium = ReleaseMedium.ANIME) =
        ReleaseAgendaItem(
            key,
            1,
            "Title",
            null,
            today.plusDays(offset).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            "Available",
            medium = medium,
        )

    private fun ReleaseAgendaTimeline.releases() = rows.filterIsInstance<ReleaseAgendaTimeline.Row.Release>()

    @Test fun opensOnTodayWithOlderReleasesAboveAndDistantFutureBelow() {
        val timeline = ReleaseAgendaTimeline(listOf(item("future", 90), item("old", -60), item("today", 0)), today)
        val anchor = timeline.indexOf(today)
        assertTrue(
            timeline.rows.take(anchor).filterIsInstance<ReleaseAgendaTimeline.Row.Release>().any {
                it.item.key ==
                    "old"
            },
        )
        assertEquals(
            listOf("today", "future"),
            timeline.rows.drop(anchor).filterIsInstance<ReleaseAgendaTimeline.Row.Release>().map {
                it.item.key
            },
        )
    }

    @Test fun emptyTodayIsARealAnchorBetweenPastAndFutureWithoutAFabricatedRelease() {
        val timeline = ReleaseAgendaTimeline(listOf(item("past", -2), item("future", 20)), today)
        val index = timeline.indexOf(today)
        assertEquals(ReleaseAgendaTimeline.Row.Day(today), timeline.rows[index])
        assertEquals(ReleaseAgendaTimeline.Row.Empty(today), timeline.rows[index + 1])
        assertEquals(listOf("past", "future"), timeline.releases().map { it.item.key })
    }

    @Test fun onlyPastAndOnlyFutureStillOpenOnToday() {
        val past = ReleaseAgendaTimeline(listOf(item("past", -100)), today)
        assertEquals(ReleaseAgendaTimeline.Row.Empty(today), past.rows[past.indexOf(today) + 1])
        assertTrue(past.indexOf(today) > 0)
        val future = ReleaseAgendaTimeline(listOf(item("future", 100)), today)
        assertEquals(0, future.indexOf(today))
        assertEquals(ReleaseAgendaTimeline.Row.Empty(today), future.rows[1])
    }

    @Test fun emptyLibraryContainsOneEmptyDay() {
        assertEquals(
            listOf(ReleaseAgendaTimeline.Row.Day(today), ReleaseAgendaTimeline.Row.Empty(today)),
            ReleaseAgendaTimeline(emptyList(), today).rows,
        )
    }

    @Test fun aTerminalEmptyAnchorCanFillOneScreenWithoutAddingAnEmptyScrollableTail() {
        val timeline = ReleaseAgendaTimeline(listOf(item("past", -10)), today)
        assertEquals(today, timeline.terminalEmptyDay(today))
        assertEquals(timeline.rows.size - 2, timeline.indexOf(today))
        assertNull(ReleaseAgendaTimeline(listOf(item("future", 96)), today).terminalEmptyDay(today))
        assertNull(ReleaseAgendaTimeline(listOf(item("today", 0)), today).terminalEmptyDay(today))
    }

    @Test fun calendarSelectionAnchorsTheWholeAgendaIncludingAnEmptyPastDay() {
        val selected = today.minusDays(30)
        val timeline = ReleaseAgendaTimeline(listOf(item("earlier", -60), item("later", 60)), today, selected)
        assertEquals(ReleaseAgendaTimeline.Row.Day(selected), timeline.rows[timeline.indexOf(selected)])
        assertEquals(ReleaseAgendaTimeline.Row.Empty(selected), timeline.rows[timeline.indexOf(selected) + 1])
        assertEquals(2, timeline.releases().size)
    }

    @Test fun withinDayOrderingIsStableRegardlessOfSnapshotOrder() {
        val items = listOf(item("late", 0, 20), item("b", 0, 10), item("a", 0, 10))
        assertEquals(listOf("a", "b", "late"), ReleaseAgendaTimeline(items, today).releases().map { it.item.key })
        assertEquals(ReleaseAgendaTimeline(items, today).rows, ReleaseAgendaTimeline(items.reversed(), today).rows)
    }

    @Test fun liveUpdatesKeepStableKeysForSavedVisibleRows() {
        val first = ReleaseAgendaTimeline(listOf(item("old", -3), item("today", 0)), today)
        val updated =
            ReleaseAgendaTimeline(listOf(item("older", -8), item("old", -3), item("today", 0), item("new", 20)), today)
        assertTrue(updated.indexOf(today) > first.indexOf(today))
        assertEquals(first.rows[first.indexOf(today)].key, updated.rows[updated.indexOf(today)].key)
        assertTrue(updated.rows.map { it.key }.containsAll(first.rows.map { it.key }))
    }

    @Test fun unifiedAndFilteredListsHaveIndependentTodayAnchors() {
        val items = listOf(item("anime", -5), item("manga", 0, medium = ReleaseMedium.MANGA))
        val unified = ReleaseAgendaTimeline(items, today)
        val anime = ReleaseAgendaTimeline(items.filter { it.medium == ReleaseMedium.ANIME }, today)
        val manga = ReleaseAgendaTimeline(items.filter { it.medium == ReleaseMedium.MANGA }, today)
        assertEquals(2, unified.releases().size)
        assertEquals(listOf("anime"), anime.releases().map { it.item.key })
        assertEquals(0, manga.indexOf(today))
        assertFalse(manga.rows.any { it is ReleaseAgendaTimeline.Row.Empty })
    }
}
