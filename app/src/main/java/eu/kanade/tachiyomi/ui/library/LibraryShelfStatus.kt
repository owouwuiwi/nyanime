package eu.kanade.tachiyomi.ui.library

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.releases.AiringEvent
import eu.kanade.tachiyomi.data.releases.ChapterScheduleRepository
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import tachiyomi.core.common.preference.Preference

@Immutable
data class LibraryShelfStatus(
    val newReleaseCount: Int = 0,
    val newestReleaseNumber: Double? = null,
    val nextReleaseAt: Long? = null,
    val nextReleaseNumber: Double? = null,
    val noticeKeys: Set<String> = emptySet(),
)

internal fun acknowledgeShelfNotices(preference: Preference<Set<String>>, status: LibraryShelfStatus) {
    if (status.noticeKeys.isEmpty()) return
    preference.set(preference.get() + status.noticeKeys)
}

private fun ReleaseStore.Notice.shelfKey(medium: String) = "$medium:$itemId:$createdAt"

internal fun animeShelfStatuses(
    notices: List<ReleaseStore.Notice>,
    events: List<AiringEvent>,
    viewed: Set<String> = emptySet(),
    now: Long = System.currentTimeMillis(),
): Map<Long, LibraryShelfStatus> = buildShelfStatuses(
    medium = "anime",
    notices = notices,
    viewed = viewed,
    future = events.asSequence()
        .filter { it.airingAt > now }
        .map { FutureRelease(it.entryId, it.episode.toDouble(), it.airingAt) }
        .toList(),
)

internal fun mangaShelfStatuses(
    notices: List<ReleaseStore.Notice>,
    events: List<ChapterScheduleRepository.Event>,
    viewed: Set<String> = emptySet(),
    now: Long = System.currentTimeMillis(),
): Map<Long, LibraryShelfStatus> = buildShelfStatuses(
    medium = "manga",
    notices = notices,
    viewed = viewed,
    future = events.asSequence()
        .filter { it.release.releaseAt > now }
        .map { FutureRelease(it.entryId, it.release.number, it.release.releaseAt) }
        .toList(),
)

private data class FutureRelease(val entryId: Long, val number: Double, val at: Long)

private fun buildShelfStatuses(
    medium: String,
    notices: List<ReleaseStore.Notice>,
    viewed: Set<String>,
    future: List<FutureRelease>,
): Map<Long, LibraryShelfStatus> {
    val noticesByEntry = notices.filterNot { it.shelfKey(medium) in viewed }.groupBy { it.entryId }
    val nextByEntry = future.groupBy { it.entryId }.mapValues { (_, values) -> values.minBy { it.at } }
    return (noticesByEntry.keys + nextByEntry.keys).associateWith { entryId ->
        val entryNotices = noticesByEntry[entryId].orEmpty()
        val next = nextByEntry[entryId]
        LibraryShelfStatus(
            newReleaseCount = entryNotices.size,
            newestReleaseNumber = entryNotices.asSequence().map { it.number }.filter { it > 0 }.maxOrNull(),
            nextReleaseAt = next?.at,
            nextReleaseNumber = next?.number,
            noticeKeys = entryNotices.mapTo(mutableSetOf()) { it.shelfKey(medium) },
        )
    }
}
