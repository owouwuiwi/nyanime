package eu.kanade.tachiyomi.data.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdaptationChapterCatalogTest {
    @Test
    fun `reverse catalog relations bind chapter ranges to their own season`() {
        val relations = AniListMediaLookup.parseAnimeRelations(
            """{"data":{"Media":{"relations":{"edges":[
              {"relationType":"ADAPTATION","node":{"id":101,"type":"ANIME","format":"TV",
                "title":{"english":"Example"},"relations":{"edges":[
                  {"relationType":"SEQUEL","node":{"id":102,"type":"ANIME","format":"TV"}}]}}},
              {"relationType":"ADAPTATION","node":{"id":102,"type":"ANIME","format":"TV",
                "title":{"english":"Example Season 2"},"relations":{"edges":[
                  {"relationType":"PREQUEL","node":{"id":101,"type":"ANIME","format":"TV"}}]}}},
              {"relationType":"ADAPTATION","node":{"id":103,"type":"ANIME","format":"TV",
                "title":{"english":"Example Season 3"},"relations":{"edges":[
                  {"relationType":"PREQUEL","node":{"id":102,"type":"ANIME","format":"TV"}}]}}}
            ]}}}}""",
        )
        val metadata = AdaptationChapterCatalog.Metadata(
            AnimeMangaContinuity.checkpoints("Chap 1 (S1) / Chap 21 (S2) / Chap 51 (S3)"),
            AnimeMangaContinuity.checkpoints("Chap 20 (S1) / Chap 50 (S2) / Chap 80 Page 12 (S3)"),
        )
        assertEquals(listOf(1, 2, 3), relations.map { it.context.season })
        val ranges = relations.map { metadata.forAdaptation(it.format, it.context) }
        assertEquals(listOf(1.0, 21.0, 51.0), ranges.map { it.beginning?.chapter })
        assertEquals(listOf(20.0, 50.0, 80.0), ranges.map { it.ending?.chapter })
        assertEquals(12, ranges.last().ending?.page)
    }

    @Test
    fun `films specials and unnumbered sequels do not borrow TV chapter ranges`() {
        val metadata = AdaptationChapterCatalog.Metadata(
            AnimeMangaContinuity.checkpoints("Chap 1 (S1)"),
            AnimeMangaContinuity.checkpoints("Chap 20 (S1)"),
        )
        for (format in listOf("MOVIE", "SPECIAL", "OVA")) {
            val range = metadata.forAdaptation(format, AniListMediaLookup.AdaptationContext(1, true))
            assertNull(range.beginning)
            assertNull(range.ending)
        }
        val unknown = metadata.forAdaptation("TV", AniListMediaLookup.AdaptationContext(null, false))
        assertNull(unknown.beginning)
        assertNull(unknown.ending)
        val sequel = metadata.forAdaptation("TV", AniListMediaLookup.AdaptationContext(2, false))
        assertNull(sequel.beginning)
        assertNull(sequel.ending)
    }

    @Test
    fun `unscoped endpoints apply only to a standalone first season`() {
        val metadata = AdaptationChapterCatalog.Metadata(
            AnimeMangaContinuity.checkpoints("Chap 1"),
            AnimeMangaContinuity.checkpoints("Chap 30"),
        )
        assertTrue(metadata.forAdaptation("TV", AniListMediaLookup.AdaptationContext(1, true)).complete)
        for (context in listOf(
            AniListMediaLookup.AdaptationContext(1, false),
            AniListMediaLookup.AdaptationContext(2, false),
            AniListMediaLookup.AdaptationContext(null, false),
        )) {
            assertEquals(AdaptationChapterCatalog.Range(), metadata.forAdaptation("TV", context))
        }
    }

    @Test
    fun `incomplete chapter references stay incomplete`() {
        val metadata = AdaptationChapterCatalog.Metadata(
            beginning = AnimeMangaContinuity.checkpoints("Chap 7.5 (S2E2)"),
        )
        val range = metadata.forAdaptation("TV", AniListMediaLookup.AdaptationContext(2, false))
        assertEquals(7.5, range.beginning?.chapter)
        assertNull(range.ending)
    }

    @Test
    fun `catalog record must have a unique exact identity`() {
        val record = """{"source":{"anilist":{"id":42}},"anime":{"start":"Chap 1 (S1)",
          "end":"Chap 20 (S1)"},"cover":{"raw":{"url":"https://images.example/cover.jpg"}}}"""
        val raw = """{"data":{"series":[$record]}}"""
        assertEquals(1.0, AdaptationChapterCatalog.parseMangaBaka(raw, 42).beginning.single().chapter)
        assertEquals("https://images.example/cover.jpg", AdaptationChapterCatalog.parseMangaBaka(raw, 42).coverUrl)
        assertTrue(AdaptationChapterCatalog.parseMangaBaka(raw, 43).beginning.isEmpty())
        assertTrue(
            AdaptationChapterCatalog.parseMangaBaka("""{"data":{"series":[$record,$record]}}""", 42)
                .beginning.isEmpty(),
        )
    }

    @Test
    fun `a fallback cannot turn contradictory endpoints into a valid range`() {
        val start = AnimeMangaContinuity.checkpoint("Chap 21 (S2)")
        val end = AnimeMangaContinuity.checkpoint("Chap 20 (S2)")
        val invalid = AdaptationChapterCatalog.Range(beginning = start)
            .withFallback(AdaptationChapterCatalog.Range(ending = end))
        assertEquals(AdaptationChapterCatalog.Range(), invalid)
        val valid = AdaptationChapterCatalog.Range(beginning = start).withFallback(
            AdaptationChapterCatalog.Range(ending = AnimeMangaContinuity.checkpoint("Chap 50 (S2)")),
        )
        assertTrue(valid.complete)
    }
}
