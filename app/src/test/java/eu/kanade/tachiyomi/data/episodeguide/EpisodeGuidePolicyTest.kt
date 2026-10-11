package eu.kanade.tachiyomi.data.episodeguide

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import eu.kanade.tachiyomi.data.rin.RinGuideDeclaration
import eu.kanade.tachiyomi.data.rin.RinManifest
import eu.kanade.tachiyomi.data.rin.RinSource
import eu.kanade.tachiyomi.data.workinfo.WorkIdentity
import eu.kanade.tachiyomi.data.workinfo.WorkKind
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpisodeGuidePolicyTest {
    @Test fun `provider payload bounds include aliases and pending candidates`() {
        val work = GuideCandidate("fictional", "Fictional series", aliases = listOf("Alternate title"))
        val guide =
            EpisodeGuide(work, listOf(GuideEpisode(1, "FUTURE_TYPE")), "Test guide", "https://example.invalid/guide")
        assertTrue(guide.valid())
        assertFalse(guide.copy(work = work.copy(aliases = List(65) { "Alias" })).valid())
        assertFalse(guide.copy(episodes = listOf(GuideEpisode(1, "CANON", "x".repeat(513)))).valid())
        assertFalse(work.copy(reference = "x".repeat(513)).valid())
        assertTrue(
            GuideSnapshot(guide = guide, candidates = listOf("provider" to work)).estimatedBytes > guide.estimatedBytes,
        )
        assertEquals(EpisodeClassification.UNKNOWN, guide.episodes.single().classification)
    }

    private val candidate = GuideCandidate("/guide/fictional", "A fictional work", year = 2000, format = "TV")
    private val identity = WorkIdentity("local", "A fictional work", WorkKind.VIDEO, year = 2000, format = "TV")
    private val guide = EpisodeGuide(
        candidate,
        listOf(GuideEpisode(26, "FILLER"), GuideEpisode(27, "MIXED"), GuideEpisode(28, "ANIME_CANON")),
        "Example guide provider",
        "https://example.invalid/guide/fictional",
    )

    @Test fun `only pure confirmed fillers are skipped and their positions are retained`() {
        val types =
            listOf(
                EpisodeClassification.CANON,
                EpisodeClassification.FILLER,
                EpisodeClassification.FILLER,
                EpisodeClassification.MIXED,
                EpisodeClassification.ANIME_CANON,
                EpisodeClassification.UNKNOWN,
            )
        assertEquals(3 to 2, EpisodeGuidePolicy.next(types.indices.toList(), 0, true) { types[it] })
        assertEquals(1 to 0, EpisodeGuidePolicy.next(types.indices.toList(), 0, false) { types[it] })
        assertEquals(4 to 0, EpisodeGuidePolicy.next(types.indices.toList(), 3, true) { types[it] })
        assertEquals(5 to 0, EpisodeGuidePolicy.next(types.indices.toList(), 4, true) { types[it] })
    }

    @Test fun `all remaining filler terminates normally without choosing the first episode`() {
        assertEquals(null to 2, EpisodeGuidePolicy.next(listOf(1, 2, 3), 0, true) { EpisodeClassification.FILLER })
        assertEquals(null to 0, EpisodeGuidePolicy.next(emptyList<Int>(), -1, true) { EpisodeClassification.UNKNOWN })
    }

    @Test fun `numbering corrections map absolute seasons and leave specials unknown`() {
        assertEquals(EpisodeClassification.FILLER, EpisodeGuidePolicy.classification(guide, 1.0, 25))
        assertEquals(EpisodeClassification.MIXED, EpisodeGuidePolicy.classification(guide, 2.0, 25))
        for (number in listOf(0.0, -1.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY, 100_001.0)) {
            assertEquals(EpisodeClassification.UNKNOWN, EpisodeGuidePolicy.classification(guide, number, 25))
        }
        assertEquals(EpisodeClassification.UNKNOWN, EpisodeGuidePolicy.classification(guide, 4.0, 25))
    }

    @Test fun `manual choices beat downloaded classifications and the existing flag`() {
        assertEquals(
            EpisodeClassification.CANON,
            EpisodeGuidePolicy.effective(false, true, EpisodeClassification.FILLER),
        )
        assertEquals(
            EpisodeClassification.FILLER,
            EpisodeGuidePolicy.effective(true, false, EpisodeClassification.MIXED),
        )
        assertEquals(
            EpisodeClassification.FILLER,
            EpisodeGuidePolicy.effective(null, true, EpisodeClassification.UNKNOWN),
        )
        assertEquals(
            EpisodeClassification.MIXED,
            EpisodeGuidePolicy.effective(null, false, EpisodeClassification.MIXED),
        )
    }

    @Test fun `titles alone and episode counts do not establish an association`() {
        assertNull(EpisodeGuidePolicy.automatic(identity.copy(year = null), listOf(candidate)))
        assertNull(EpisodeGuidePolicy.automatic(identity.copy(format = null), listOf(candidate)))
        assertNull(EpisodeGuidePolicy.automatic(identity, listOf(candidate.copy(year = 2010))))
        assertNull(EpisodeGuidePolicy.automatic(identity, listOf(candidate.copy(format = "MOVIE"))))
        assertEquals(
            candidate,
            EpisodeGuidePolicy.automatic(
                identity.copy(title = "Localized title", aliases = listOf(candidate.title)),
                listOf(candidate),
            ),
        )
    }

    @Test fun `later seasons parts and ambiguous editions require confirmed numbering`() {
        assertNull(EpisodeGuidePolicy.automatic(identity.copy(season = 2), listOf(candidate)))
        assertNull(EpisodeGuidePolicy.automatic(identity.copy(part = 2), listOf(candidate)))
        assertNull(
            EpisodeGuidePolicy.automatic(
                identity,
                listOf(candidate, candidate.copy(reference = "/guide/other-edition")),
            ),
        )
    }

    @Test fun `unknown categories remain unclassified across serialization`() {
        assertEquals(
            EpisodeClassification.UNKNOWN,
            Json.decodeFromString<GuideEpisode>("""{"number":9,"type":"FUTURE_TYPE"}""").classification,
        )
        assertFalse(guide.copy(episodes = listOf(GuideEpisode(1, "CANON"), GuideEpisode(1, "FILLER"))).valid())
        assertTrue(guide.valid())
    }

    @Test fun `public queries contain no local identities source URLs or viewing progress`() {
        val value = Json.encodeToString(GuideQuery("A fictional work", listOf("An alias"), 2000, "TV", 1, null, 100))
        assertFalse(value.contains("local"))
        assertFalse(value.contains("source"))
        assertFalse(value.contains("url"))
        assertFalse(value.contains("progress"))
        assertFalse(value.contains("cookie"))
    }

    @Test fun `binding correction survives backup JSON and explicit removal is distinct from no choice`() {
        val binding = GuideBinding("example.provider:signer", "/guide/fictional", 25)
        assertEquals(binding, Json.decodeFromString<GuideBinding>(Json.encodeToString(binding)))
        assertEquals(GuideBinding(), Json.decodeFromString<GuideBinding>("{}"))
        for (key in listOf(
            "episode_guides_skip",
            "episode_guides_v1_example_binding",
            "episode_guides_v1_example_override:abc",
            "episode_guides_v1_example_hide",
        )) {
            assertTrue(BackupPreferencePolicy.isPortable(key))
        }
    }

    @Test fun `guide contracts register no playable sources and reject malformed capability declarations`() {
        val declaration = RinGuideDeclaration(1, listOf("episode-guides.v1"), mapOf("en" to "Example guide"))
        val manifest =
            RinManifest(
                2, "example.guide.provider", "Example guide", 1, "1.0.0", "guide", 1.0,
                listOf(
                    "example.guide.Provider",
                ),
                emptyList(), hashes = emptyMap(), guide = declaration,
            )
        assertTrue(manifest.validSourceIdentities())
        assertTrue(manifest.preservesSourceIds(emptyList()))
        assertFalse(manifest.copy(sources = listOf(RinSource(1, "Example", "en"))).validSourceIdentities())
        assertFalse(declaration.copy(capabilities = listOf("invalid capability")).valid())
        assertFalse(declaration.copy(api = 99).valid())
    }
}
