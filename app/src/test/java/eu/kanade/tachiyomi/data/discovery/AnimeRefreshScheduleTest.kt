package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.library.anime.AnimeRefreshSchedule
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.model.Anime

class AnimeRefreshScheduleTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `a forecast never permanently suppresses checks and explicit fetch-once is skipped`() {
        val schedule = AnimeRefreshSchedule(InMemoryPreferenceStore())
        val anime = Anime.create().copy(id = 91L)

        assertTrue(schedule.isDue(anime.copy(nextUpdate = now + 3 * DAY), now))
        assertFalse(
            schedule.isDue(anime.copy(updateStrategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE), now),
        )
        assertTrue(schedule.isDue(anime.copy(nextUpdate = now + DAY), now))
    }

    @Test
    fun `ongoing titles are checked sooner than completed ones and failures retry later`() {
        val anime = Anime.create().copy(id = 92L, status = SAnime.ONGOING.toLong())
        val success = scheduleAt(anime.id, now - 13 * HOUR)
        assertTrue(success.isDue(anime, now))
        assertFalse(success.isDue(anime.copy(status = SAnime.COMPLETED.toLong()), now))

        val failure = scheduleAt(anime.id, -(now - 30 * MINUTE))
        assertFalse(failure.isDue(anime, now))
        assertTrue(failure.isDue(anime, now + 31 * MINUTE))
    }

    @Test
    fun `source budget survives new schedule objects and resumes in the next hour`() {
        val longs = mutableMapOf<String, InMemoryPreference<Long>>()
        val ints = mutableMapOf<String, InMemoryPreference<Int>>()
        val store = mockk<PreferenceStore> {
            every { getLong(any(), any()) } answers {
                val key = firstArg<String>()
                longs.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
            every { getInt(any(), any()) } answers {
                val key = firstArg<String>()
                ints.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
        }

        repeat(12) { assertTrue(AnimeRefreshSchedule(store).reserve(43L, now)) }
        assertFalse(AnimeRefreshSchedule(store).reserve(43L, now))
        assertTrue(AnimeRefreshSchedule(store).reserve(43L, now + HOUR))
        assertEquals(1, ints.values.single().get())
    }

    private fun scheduleAt(id: Long, lastResult: Long): AnimeRefreshSchedule {
        val key = Preference.appStateKey("recent_anime_result_$id")
        return AnimeRefreshSchedule(
            InMemoryPreferenceStore(sequenceOf(InMemoryPreferenceStore.InMemoryPreference(key, lastResult, 0L))),
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
