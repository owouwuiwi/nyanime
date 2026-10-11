package eu.kanade.tachiyomi.data.releases

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The same agenda data backs the app calendar and the Android widget. */
data class ReleaseAgendaItem(
    val key: String,
    val entryId: Long,
    val title: String,
    val cover: Any?,
    val at: Long,
    val label: ReleaseAgendaLabel,
    val itemId: Long? = null,
    val medium: ReleaseMedium = ReleaseMedium.ANIME,
    val dismissalKey: String? = null,
    val number: Double? = null,
    val sourceLabel: String = "",
    val choices: List<ReleaseAgendaItem> = emptyList(),
    val broadcasts: List<ScheduleBroadcast> = emptyList(),
    val relatedEntryIds: Set<Long> = emptySet(),
    val agendaKeys: Set<String> = emptySet(),
    val plannedAgendaKeys: Set<String> = emptySet(),
) {
    val date: LocalDate get() = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
}

sealed interface ReleaseAgendaLabel {
    data class OriginalBroadcast(val event: AiringEvent) : ReleaseAgendaLabel
    data class PlannedChapter(val number: Double) : ReleaseAgendaLabel
    data class Available(val itemName: String) : ReleaseAgendaLabel
}
