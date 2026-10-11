package eu.kanade.tachiyomi.data.releases

import eu.kanade.presentation.components.releases.ReleaseAgendaActions
import eu.kanade.presentation.components.releases.ReleaseAgendaItem
import eu.kanade.presentation.components.releases.ReleaseAgendaMerge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.entries.anime.model.Anime

class ReleaseAgendaActionsTest {
    private fun work(id: Long, source: Long = id) = Anime.create().let {
        it.copy(
            id = id,
            source = source,
            title = "Series",
            url = "/series/$source",
            memo = SourceHomePresentation(catalogIds = mapOf("anilist" to 50)).attachTo(it.memo),
        )
    }

    private fun item(id: Long, number: Double = 2.0, available: Boolean = false) = ReleaseAgendaItem(
        key = "item-$id", entryId = id, title = "Series", cover = null, at = 1000, label = "Episode $number",
        itemId = if (available) id * 10 else null, number = number,
        agendaKeys = setOf(ReleaseAgendaActions.key(ReleaseMedium.ANIME, id, "/series/$id", number)),
    )

    @Test fun removingOneEpisodeKeepsOtherEpisodesAndTitles() {
        val hidden = ReleaseAgendaActions.dismissalKeys(item(1))
        assertTrue(ReleaseAgendaActions.dismissed(item(1), hidden))
        assertFalse(ReleaseAgendaActions.dismissed(item(1, 3.0), hidden))
        assertFalse(ReleaseAgendaActions.dismissed(item(2), hidden))
    }

    @Test fun changedTimeTitleAndDatabaseIdsDoNotResurrectTheRelease() {
        val hidden = ReleaseAgendaActions.dismissalKeys(item(1))
        val restored = item(1, available = true).copy(entryId = 500, itemId = 900, title = "Renamed", at = 2000)
        assertTrue(ReleaseAgendaActions.dismissed(restored, hidden))
    }

    @Test fun plannedAndAvailableMergedRowsShareDismissalAcrossAllEditions() {
        val works = listOf(work(1), work(2), work(3))
        val planned = ReleaseAgendaMerge.merge(listOf(item(1), item(2)), works).single()
        val available = ReleaseAgendaMerge.merge(listOf(item(2, available = true)), works).single()
        val hidden = ReleaseAgendaActions.dismissalKeys(planned)
        assertTrue(ReleaseAgendaActions.dismissed(available, hidden))
        assertTrue(ReleaseAgendaActions.dismissed(item(3, available = true), hidden))
        assertFalse(ReleaseAgendaActions.dismissed(item(3, 3.0, available = true), hidden))
        assertEquals(setOf(1L, 2L, 3L), ReleaseAgendaActions.entries(available))
    }

    @Test fun stoppingFollowAlsoIncludesEditionsWithoutACurrentSourceChoice() {
        val merged = ReleaseAgendaMerge.merge(listOf(item(2, available = true)), listOf(work(1), work(2))).single()
        assertTrue(merged.choices.isEmpty())
        assertEquals(setOf(1L, 2L), ReleaseAgendaActions.entries(merged))
    }

    @Test fun unidentifiedChaptersUseTheirExactReferencesAndKeepMediaSeparate() {
        val first = ReleaseAgendaActions.key(ReleaseMedium.MANGA, 1, "/title/1", -1.0, "/chapter/a")
        val second = ReleaseAgendaActions.key(ReleaseMedium.MANGA, 1, "/title/1", -1.0, "/chapter/b")
        assertNotEquals(first, second)
        assertNotEquals(
            ReleaseAgendaActions.key(ReleaseMedium.MANGA, 1, "/title/1", 2.0),
            ReleaseAgendaActions.key(ReleaseMedium.ANIME, 1, "/title/1", 2.0),
        )
    }

    @Test fun referenceSeparatorsAndUnicodeCannotCollide() {
        assertNotEquals(
            ReleaseAgendaActions.key(ReleaseMedium.MANGA, 1, "/作品|item-a", null, "b"),
            ReleaseAgendaActions.key(ReleaseMedium.MANGA, 1, "/作品", null, "item-a|b"),
        )
    }

    @Test fun openingContentNeverOffersAnUnreleasedEdition() {
        assertTrue(ReleaseAgendaActions.playableOptions(item(1)).isEmpty())
        val mixed = item(1).copy(choices = listOf(item(1), item(2, available = true)))
        assertEquals(listOf(20L), ReleaseAgendaActions.playableOptions(mixed).map { it.itemId })
        assertEquals(listOf(10L), ReleaseAgendaActions.playableOptions(item(1, available = true)).map { it.itemId })
    }

    @Test fun twoAvailableChapterEditionsWithTheSameNumberCanBeRemovedSeparately() {
        val plannedKey = ReleaseAgendaActions.key(ReleaseMedium.MANGA, 1, "/title/1", 2.0)
        val first = item(1, available = true).copy(
            medium = ReleaseMedium.MANGA,
            agendaKeys = setOf(ReleaseAgendaActions.key(ReleaseMedium.MANGA, 1, "/title/1", null, "/chapter/a")),
            plannedAgendaKeys = setOf(plannedKey),
        )
        val second = first.copy(
            key = "other-edition",
            itemId = 11,
            agendaKeys = setOf(ReleaseAgendaActions.key(ReleaseMedium.MANGA, 1, "/title/1", null, "/chapter/b")),
        )
        val hidden = ReleaseAgendaActions.dismissalKeys(first)
        assertTrue(ReleaseAgendaActions.dismissed(first, hidden))
        assertFalse(ReleaseAgendaActions.dismissed(second, hidden))
        assertTrue(ReleaseAgendaActions.dismissed(first, setOf(plannedKey)))
    }
}
