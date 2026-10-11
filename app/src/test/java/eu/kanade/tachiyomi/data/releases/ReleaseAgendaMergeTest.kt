package eu.kanade.tachiyomi.data.releases

import eu.kanade.presentation.components.releases.ReleaseAgendaItem
import eu.kanade.presentation.components.releases.ReleaseAgendaMerge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.entries.anime.model.Anime

class ReleaseAgendaMergeTest {
    private fun work(id: Long, source: Long = id, catalog: Long? = 50, year: Int? = null) = Anime.create().let {
        it.copy(
            id = id,
            source = source,
            title = "Series",
            url = "/series/$id",
            memo = SourceHomePresentation(
                catalogIds = catalog?.let { mapOf("anilist" to it) }.orEmpty(),
                releaseYear = year,
            ).attachTo(it.memo),
        )
    }

    private fun item(id: Long, number: Double = 2.0, available: Boolean = false) = ReleaseAgendaItem(
        key = "item-$id", entryId = id, title = "Series", cover = null,
        at = 1000 + id, label = "Episode $number", itemId = if (available) id * 10 else null,
        number = number, sourceLabel = "Source $id",
    )

    @Test fun oneBroadcastAcrossTwoSourcesKeepsConcreteChoicesAndStableOrdering() {
        val result = ReleaseAgendaMerge.merge(listOf(item(2), item(1)), listOf(work(2), work(1)))
        assertEquals(1, result.size)
        assertEquals(listOf(1L, 2L), result.single().choices.map { it.entryId })
        assertEquals(result, ReleaseAgendaMerge.merge(listOf(item(1), item(2)), listOf(work(1), work(2))))
    }

    @Test fun differentSeasonsAndConflictingEvidenceNeverMerge() {
        assertEquals(2, ReleaseAgendaMerge.merge(listOf(item(1), item(2)), listOf(work(1), work(2, catalog = 51))).size)
        assertNull(ReleaseAgendaMerge.verifiedIds(mapOf("anilist" to 50), mapOf("anilist" to 51)))
        assertEquals(
            mapOf("anilist" to 50L, "myanimelist" to 60L),
            ReleaseAgendaMerge.verifiedIds(
                mapOf("anilist" to 50),
                mapOf("anilist" to 50, "myanimelist" to 60),
            ),
        )
    }

    @Test fun titleAloneAndUnknownEpisodeNumbersAreInsufficient() {
        assertEquals(
            2,
            ReleaseAgendaMerge.merge(
                listOf(item(1), item(2)),
                listOf(work(1, catalog = null), work(2, catalog = null)),
            ).size,
        )
        assertEquals(2, ReleaseAgendaMerge.merge(listOf(item(1, -1.0), item(2, -1.0)), listOf(work(1), work(2))).size)
    }

    @Test fun independentlyKnownYearReusesTheHomeFallback() {
        assertEquals(
            1,
            ReleaseAgendaMerge.merge(
                listOf(item(1), item(2)),
                listOf(
                    work(1, catalog = null, year = 2025),
                    work(2, catalog = null, year = 2025),
                ),
            ).size,
        )
        assertEquals(
            2,
            ReleaseAgendaMerge.merge(
                listOf(item(1), item(2)),
                listOf(
                    work(1, catalog = null, year = 2025),
                    work(2, catalog = null, year = 2026),
                ),
            ).size,
        )
    }

    @Test fun distinctEpisodesAndUnverifiedSameSourceEditionsRemainSeparate() {
        assertEquals(2, ReleaseAgendaMerge.merge(listOf(item(1), item(2, 3.0)), listOf(work(1), work(2))).size)
        assertEquals(
            2,
            ReleaseAgendaMerge.merge(
                listOf(item(1), item(2)),
                listOf(
                    work(1, source = 3, catalog = null, year = 2025),
                    work(2, source = 3, catalog = null, year = 2025),
                ),
            ).size,
        )
    }

    @Test fun verifiedEditionsShareOneBroadcastAndKeepAllThreeSourceChoices() {
        val works = listOf(work(1, source = 3), work(2, source = 3).copy(title = "Series (Dub)"), work(4))
        val result = ReleaseAgendaMerge.merge(listOf(item(1), item(2), item(4)), works)
        assertEquals(1, result.size)
        assertEquals(listOf(1L, 2L, 4L), result.single().choices.map { it.entryId })
        assertEquals(2, tachiyomi.data.discovery.mergeHomeCards(works).size)
    }

    @Test fun availableEpisodeReplacesTheBroadcastWithoutOpeningAnUnavailableSource() {
        val result = ReleaseAgendaMerge.merge(listOf(item(1), item(2, available = true)), listOf(work(1), work(2)))
        assertEquals(20L, result.single().itemId)
        assertTrue(result.single().choices.isEmpty())
    }

    @Test fun watchedInOneSourceDoesNotRemainNewInAnother() {
        assertTrue(
            ReleaseAgendaMerge.merge(
                listOf(item(1), item(2, available = true)),
                listOf(work(1), work(2)),
                watched = mapOf(1L to setOf(2.0)),
            ).isEmpty(),
        )
    }

    @Test fun existingEpisodeWithoutANoticeDoesNotRemainAnnouncedInAnotherSource() {
        assertTrue(
            ReleaseAgendaMerge.merge(
                listOf(item(2)),
                listOf(work(1), work(2)),
                present = mapOf(1L to setOf(2.0)),
            ).isEmpty(),
        )
    }

    @Test fun distantFutureBroadcastSurvivesLocalEpisodePlaceholdersAndWatchHistory() {
        val now = 100 * ReleasePolicy.DAY
        val future = item(1, number = 800.0).copy(at = now + 96 * ReleasePolicy.DAY)
        val result = ReleaseAgendaMerge.merge(
            listOf(future, future.copy(key = "other", entryId = 2)),
            listOf(work(1), work(2)),
            present = mapOf(1L to setOf(800.0)),
            watched = mapOf(2L to setOf(800.0)),
            now = now,
        )
        assertEquals(1, result.size)
        assertEquals(future.at, result.single().at)
        assertEquals(2, result.single().choices.size)
    }
}
