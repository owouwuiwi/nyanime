package eu.kanade.tachiyomi.data.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaAnimeContinuityTest {
    @Test
    fun `only anime adaptations establish the reverse relationship`() {
        val relations = AniListMediaLookup.parseAnimeRelations(
            """{"data":{"Media":{"relations":{"edges":[
              {"relationType":"ADAPTATION","node":{"id":101,"type":"ANIME","format":"TV",
                "title":{"english":"Example adaptation"}}},
              {"relationType":"PREQUEL","node":{"id":102,"type":"ANIME","format":"TV",
                "title":{"english":"Example prequel"}}},
              {"relationType":"ADAPTATION","node":{"id":103,"type":"MANGA","format":"NOVEL",
                "title":{"english":"Example novel"}}},
              {"relationType":"ADAPTATION","node":{"id":104,"type":"ANIME","format":"MUSIC",
                "title":{"english":"Example music"}}}
            ]}}}}""",
        )
        assertEquals(listOf(101L), relations.map { it.id })
        assertFalse(relations.single().viaOriginalNovel)
    }

    @Test
    fun `keeps seasons separate and deduplicates catalog identities`() {
        val relations = AniListMediaLookup.parseAnimeRelations(
            """{"data":{"Media":{"relations":{"edges":[
              {"relationType":"ADAPTATION","node":{"id":101,"idMal":201,"type":"ANIME","format":"TV",
                "episodes":12,"seasonYear":2024,"coverImage":{"large":"https://images.example/cover.jpg"},
                "title":{"english":"Example first season"}}},
              {"relationType":"ADAPTATION","node":{"id":102,"type":"ANIME","format":"TV",
                "episodes":0,"seasonYear":2025,"title":{"romaji":"Example second season"}}},
              {"relationType":"ADAPTATION","node":{"id":101,"type":"ANIME","format":"TV",
                "title":{"english":"Duplicate edition title"}}}
            ]}}}}""",
            viaOriginalNovel = true,
        )
        assertEquals(listOf(101L, 102L), relations.map { it.id })
        assertEquals(12, relations.first().episodes)
        assertEquals(2024, relations.first().year)
        assertEquals(201L, relations.first().malId)
        assertEquals("Example second season", relations.last().title)
        assertEquals(null, relations.last().episodes)
        assertTrue(relations.all { it.viaOriginalNovel })
    }

    @Test
    fun `a tracker cannot override conflicting source identity`() {
        assertFalse(
            MangaAnimeContinuity.knownIdentityMatches(
                101,
                201,
                SourceTrackingHints(anilistId = 102),
                SourceTrackingHints(malId = 201),
            ),
        )
        assertFalse(
            MangaAnimeContinuity.knownIdentityMatches(
                101,
                201,
                SourceTrackingHints(anilistId = 101),
                SourceTrackingHints(malId = 202),
            ),
        )
        assertTrue(MangaAnimeContinuity.knownIdentityMatches(101, 201, SourceTrackingHints(anilistId = 101), null))
        assertFalse(MangaAnimeContinuity.knownIdentityMatches(101, 201, null, SourceTrackingHints(malId = 201)))
        assertFalse(
            MangaAnimeContinuity.knownIdentityMatches(101, 201, SourceTrackingHints(titles = listOf("Same")), null),
        )
    }
}
