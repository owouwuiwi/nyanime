package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.data.releases.AiringEvent
import eu.kanade.tachiyomi.data.releases.ChapterScheduleRepository
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import eu.kanade.tachiyomi.source.ScheduledRelease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LibraryShelfStatusTest {
    private val now = 1_000_000L

    @Test
    fun animeUsesOnlyRealNoticesAndTheNearestFutureEpisode() {
        val statuses = animeShelfStatuses(
            notices = listOf(
                notice(itemId = 10, entryId = 1, number = 7.0),
                notice(itemId = 11, entryId = 1, number = 8.0),
            ),
            events = listOf(
                AiringEvent(entryId = 1, episode = 9, airingAt = now + 90_000, catalogId = 90),
                AiringEvent(entryId = 1, episode = 10, airingAt = now + 180_000, catalogId = 90),
                AiringEvent(entryId = 2, episode = 4, airingAt = now - 1, catalogId = 91),
            ),
            now = now,
        )

        assertEquals(2, statuses.getValue(1).newReleaseCount)
        assertEquals(8.0, statuses.getValue(1).newestReleaseNumber)
        assertEquals(9.0, statuses.getValue(1).nextReleaseNumber)
        assertEquals(now + 90_000, statuses.getValue(1).nextReleaseAt)
        assertFalse(statuses.containsKey(2))
    }

    @Test
    fun backlogWithoutANoticeNeverBecomesANewRelease() {
        val status = animeShelfStatuses(
            notices = emptyList(),
            events = listOf(AiringEvent(3, 12, now + 60_000, 92)),
            now = now,
        ).getValue(3)

        assertEquals(0, status.newReleaseCount)
        assertNull(status.newestReleaseNumber)
        assertEquals(12.0, status.nextReleaseNumber)
    }

    @Test
    fun mangaUsesTheNearestAnnouncedChapter() {
        val statuses = mangaShelfStatuses(
            notices = listOf(notice(itemId = 20, entryId = 4, number = 31.5)),
            events = listOf(
                ChapterScheduleRepository.Event(4, ScheduledRelease(33.0, now + 120_000)),
                ChapterScheduleRepository.Event(4, ScheduledRelease(32.0, now + 30_000)),
            ),
            now = now,
        )

        assertEquals(1, statuses.getValue(4).newReleaseCount)
        assertEquals(31.5, statuses.getValue(4).newestReleaseNumber)
        assertEquals(32.0, statuses.getValue(4).nextReleaseNumber)
        assertEquals(now + 30_000, statuses.getValue(4).nextReleaseAt)
    }

    @Test
    fun openingATitleClearsOnlyItsCurrentShelfNotices() {
        val notices = listOf(
            notice(itemId = 10, entryId = 1, number = 7.0),
            notice(itemId = 11, entryId = 1, number = 8.0),
            notice(itemId = 12, entryId = 2, number = 3.0),
        )
        val initial = animeShelfStatuses(notices, emptyList(), now = now)
        val viewed = initial.getValue(1).noticeKeys
        val after = animeShelfStatuses(notices, emptyList(), viewed = viewed, now = now)

        assertFalse(after.containsKey(1))
        assertEquals(1, after.getValue(2).newReleaseCount)
        assertEquals(
            2,
            mangaShelfStatuses(notices.take(2), emptyList(), viewed = viewed, now = now)
                .getValue(1).newReleaseCount,
        )
    }

    private fun notice(itemId: Long, entryId: Long, number: Double) = ReleaseStore.Notice(
        itemId = itemId,
        entryId = entryId,
        createdAt = now,
        sourceAt = now,
        number = number,
    )
}
