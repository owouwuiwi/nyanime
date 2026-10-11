package eu.kanade.presentation.components.releases

import java.time.LocalDate

/** Local, chronological rows. Empty anchor days remain reachable without inventing releases. */
internal class ReleaseAgendaTimeline(
    items: List<ReleaseAgendaItem>,
    today: LocalDate,
    focusDate: LocalDate = today,
) {
    sealed interface Row {
        val key: String

        data class Day(val date: LocalDate) : Row {
            override val key = "day-$date"
        }

        data class Release(val item: ReleaseAgendaItem) : Row {
            override val key = "release-${item.key}"
        }

        data class Empty(val date: LocalDate) : Row {
            override val key = "empty-$date"
        }
    }

    val rows: List<Row> = buildList {
        val days = items.sortedWith(compareBy({ it.at }, { it.key })).groupBy { it.date }
        (days.keys + today + focusDate).sorted().forEach { date ->
            add(Row.Day(date))
            val releases = days[date].orEmpty()
            if (releases.isEmpty()) add(Row.Empty(date)) else releases.forEach { add(Row.Release(it)) }
        }
    }

    fun indexOf(date: LocalDate): Int = rows.indexOfFirst { it is Row.Day && it.date == date }.coerceAtLeast(0)

    fun terminalEmptyDay(focusDate: LocalDate): LocalDate? {
        val empty = rows.lastOrNull() as? Row.Empty ?: return null
        return empty.date.takeIf { it == focusDate }
    }
}
