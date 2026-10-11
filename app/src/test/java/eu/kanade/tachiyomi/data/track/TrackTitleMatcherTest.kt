package eu.kanade.tachiyomi.data.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TrackTitleMatcherTest {
    private data class Candidate(val id: Long, val titles: List<String>)

    private fun select(title: String, vararg candidates: Candidate) =
        TrackTitleMatcher.choose(title, candidates.toList(), { it.titles }, { it.id })

    @Test
    fun matchesAnOfficialAlternateTitle() {
        val title = Candidate(1, listOf("Official title", "Nihongo title", "Titolo italiano"))
        assertEquals(title, select("Titolo italiano", title))
    }

    @Test
    fun toleratesPunctuationAccentsAndLanguageTags() {
        val title = Candidate(2, listOf("Café & Dreams: Season III"))
        assertEquals(title, select("Cafe and Dreams - Season 3 [ITA]", title))
    }

    @Test
    fun doesNotConfuseSeasons() {
        assertNull(select("A Story Season 2", Candidate(1, listOf("A Story Season 3"))))
        assertEquals(
            Candidate(2, listOf("A Story Season 2")),
            select(
                "A Story 2nd Season",
                Candidate(1, listOf("A Story Season 3")),
                Candidate(2, listOf("A Story Season 2")),
            ),
        )
    }

    @Test
    fun doesNotChooseBetweenDuplicateTitles() {
        assertNull(select("A Story", Candidate(1, listOf("A Story")), Candidate(2, listOf("A Story"))))
    }

    @Test
    fun acceptsClearLongTypoButNotCompetingCandidate() {
        val intended = Candidate(1, listOf("The Enchanted Kingdom Chronicles"))
        assertEquals(intended, select("The Enchanted Kingdom Chronicle", intended))
        assertNull(
            select(
                "The Enchanted Kingdom Chronicle",
                intended,
                Candidate(2, listOf("The Enchanted Kingdom Chronicles")),
            ),
        )
    }
}
