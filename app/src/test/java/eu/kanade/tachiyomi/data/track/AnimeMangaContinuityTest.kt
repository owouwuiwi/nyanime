package eu.kanade.tachiyomi.data.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimeMangaContinuityTest {
    @Test
    fun `uses only explicit chapter and episode checkpoints`() {
        val start = AnimeMangaContinuity.checkpoint("Vol 1, Chap 1 (Chap 1 adapted in EP 4)")
        val end = AnimeMangaContinuity.checkpoint("Vol 113, Chap 1150 (As of EP 1180)")
        assertEquals(1.0, start?.chapter)
        assertEquals(4, start?.episode)
        assertTrue(start?.exactEpisode == true)
        assertEquals(1150.0, end?.chapter)
        assertEquals(1180, end?.episode)
        assertFalse(end?.exactEpisode == true)
        assertNull(AnimeMangaContinuity.checkpoint("Anime covers the early arc"))

        val arc = AnimeMangaContinuity.checkpoint("Vol 5, Chap 31 (S1E11)")
        assertEquals(1, arc?.season)
        assertEquals(11, arc?.episode)
        assertFalse(arc?.exactEpisode == true)
    }

    @Test
    fun `does not guess a chapter outside the documented episode`() {
        val choice = AnimeMangaContinuity.Choice(
            catalogId = 42,
            catalogMalId = null,
            title = "Example",
            coverUrl = null,
            format = "MANGA",
            beginning = AnimeMangaContinuity.Checkpoint(1.0, "Chap 1", null),
            latestAdapted = AnimeMangaContinuity.Checkpoint(30.0, "Chap 30 adapted in EP 12", 12, exactEpisode = true),
            matches = emptyList(),
        )
        assertNull(choice.continuationAfter(11.0))
        assertNull(choice.continuationAfter(13.0))
        assertNull(choice.continuationAfter(null))
        assertEquals(30.0, choice.continuationAfter(12.0))
        assertNull(choice.copy(latestAdapted = choice.latestAdapted?.copy(season = 2)).continuationAfter(12.0))
    }

    @Test
    fun `standalone season tags and separate season references are retained`() {
        val points = AnimeMangaContinuity.checkpoints("Vol 1, Chap 1 (S1); Vol 5, Chap 30 (S2)")
        assertEquals(listOf(1, 2), points.map { it.season })
        assertEquals(1.0, AnimeMangaContinuity.selectCheckpoint(points, 1, false)?.chapter)
        assertEquals(30.0, AnimeMangaContinuity.selectCheckpoint(points, 2, false)?.chapter)
        assertNull(AnimeMangaContinuity.selectCheckpoint(points, 3, false))
        assertNull(AnimeMangaContinuity.selectCheckpoint(points, null, false))
        val inline = AnimeMangaContinuity.checkpoints("Vol 1, Chap 1 (S1), Vol 5, Chap 30 (S2E1)")
        assertEquals(listOf(1, 2), inline.map { it.season })
        assertEquals(1, inline.last().episode)
        val slashSeparated = AnimeMangaContinuity.checkpoints(
            "Vol 1, Chap 1 (S1) / Vol 5, Chap 30 (S2) / Vol 9, Chap 55 (S3) / Vol 13, Chap 90 (S4)",
        )
        assertEquals(listOf(1, 2, 3, 4), slashSeparated.map { it.season })
        assertEquals(55.0, AnimeMangaContinuity.selectCheckpoint(slashSeparated, 3, false)?.chapter)
        val html = AnimeMangaContinuity.checkpoints("Chap 23 (Season 1)<br />Chap 55 (Season 2)")
        assertEquals(55.0, AnimeMangaContinuity.selectCheckpoint(html, 2, false)?.chapter)
    }

    @Test
    fun `a series wide or ambiguous checkpoint is not a sequel endpoint`() {
        val points = AnimeMangaContinuity.checkpoints("Vol 10, Chap 90")
        assertEquals(90.0, AnimeMangaContinuity.selectCheckpoint(points, 1, true)?.chapter)
        assertNull(AnimeMangaContinuity.selectCheckpoint(points, 1, false))
        assertNull(AnimeMangaContinuity.selectCheckpoint(points, 2, false))
        val ambiguous = AnimeMangaContinuity.checkpoints("Chap 30 (S2); Chap 35 (S2)")
        assertNull(AnimeMangaContinuity.selectCheckpoint(ambiguous, 2, false))
        assertNull(AnimeMangaContinuity.checkpoint("Chap 30 (S1 or S2)"))
        assertNull(AnimeMangaContinuity.checkpoint("Chap 1 to Chap 30"))
    }

    @Test
    fun `season numbering uses catalog entry titles and serial relations`() {
        assertEquals(2, AniListMediaLookup.seasonOrdinal(listOf("Example 2nd Season", "Example Season 2 Part 2")))
        assertEquals(2, AniListMediaLookup.seasonOrdinal(listOf("Example: Another Arc", "Example ภาค 2")))
        assertEquals(3, AniListMediaLookup.seasonOrdinal(listOf("Example 第3期", "Example Staffel 3")))
        assertNull(AniListMediaLookup.seasonOrdinal(listOf("Example 2026", "Example Part 2")))
        assertNull(AniListMediaLookup.seasonOrdinal(listOf("Example Season 2", "Example Season 3")))
        val first = AniListMediaLookup.parseAdaptationContext(
            """{"data":{"Media":{"id":10,"format":"TV","title":{"english":"Example"},
                "relations":{"edges":[{"relationType":"SEQUEL","node":{"id":20,"type":"ANIME","format":"TV"}}]}}}}""",
        )
        assertEquals(1, first.season)
        assertFalse(first.standaloneSeason)
        val sequel = AniListMediaLookup.parseAdaptationContext(
            """{"data":{"Media":{"id":20,"format":"TV","title":{"english":"Example: Another Arc"},
                "relations":{"edges":[{"relationType":"PREQUEL","node":{"id":10,"type":"ANIME","format":"TV"}}]}}}}""",
        )
        assertNull(sequel.season)
        assertFalse(sequel.standaloneSeason)
        val namedArc = AniListMediaLookup.parseAdaptationContext(
            """{"data":{"Media":{"id":20,"format":"TV","title":{"english":"Example: Another Arc"},
                "synonyms":["Example ภาค 2"],"relations":{"edges":[]}}}}""",
        )
        assertEquals(2, namedArc.season)
        val standalone = AniListMediaLookup.parseAdaptationContext(
            """{"data":{"Media":{"id":30,"format":"TV","title":{"english":"Example"},
                "relations":{"edges":[{"relationType":"PREQUEL","node":{"id":40,"type":"ANIME","format":"MOVIE"}}]}}}}""",
        )
        assertEquals(1, standalone.season)
        assertTrue(standalone.standaloneSeason)
    }

    @Test
    fun `a conflicting catalog ID prevents automatic association`() {
        assertTrue(AnimeMangaContinuity.hasSameIdentity(10, 20, SourceTrackingHints(anilistId = 10)))
        assertFalse(AnimeMangaContinuity.hasSameIdentity(10, 20, SourceTrackingHints(malId = 20, anilistId = 11)))
        assertFalse(AnimeMangaContinuity.hasSameIdentity(10, 20, SourceTrackingHints(titles = listOf("Same title"))))
    }
}
