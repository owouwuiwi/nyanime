package eu.kanade.tachiyomi.discovery

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.AnimeCatalogRepository
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogRelation
import tachiyomi.domain.discovery.CatalogSeriesEvidence
import tachiyomi.domain.discovery.SectionState

class CatalogSeriesEvidenceTest {
    private val catalog = mockk<AnimeCatalogRepository>()
    private val policy = CatalogSeriesEvidence(catalog)
    private val first =
        CatalogAnime(CatalogId(value = 1), "Example Season 1", format = "TV", status = "FINISHED", episodes = 13)
    private val second = first.copy(
        id = CatalogId(value = 2),
        title = "Example Season 2",
        episodes = 12,
        relations = listOf(CatalogRelation(first.id, first.title, null, "PREQUEL")),
    )

    private fun reply(anime: CatalogAnime = first, stale: Boolean = false) {
        every { catalog.observeDetails(first.id, any(), any()) } returns
            flowOf(SectionState(anime, loading = false, stale = stale))
    }

    @Test
    fun `verified serial chain requires the cumulative count not just more than one season`() = runBlocking {
        reply()
        assertEquals(25, policy.minimumCombinedEpisodes(second))
    }

    @Test
    fun `unknown counts and standalone works do not invent season offsets`() = runBlocking {
        reply(first.copy(episodes = null))
        assertNull(policy.minimumCombinedEpisodes(second))
        assertNull(policy.minimumCombinedEpisodes(first))
    }

    @Test
    fun `movie prequels and stale metadata are insufficient collection evidence`() = runBlocking {
        reply(first.copy(format = "MOVIE"))
        assertNull(policy.minimumCombinedEpisodes(second))
        reply(stale = true)
        assertNull(policy.minimumCombinedEpisodes(second))
    }

    @Test
    fun `ambiguous and cyclic prequels are rejected`() = runBlocking {
        assertNull(policy.minimumCombinedEpisodes(second.copy(relations = second.relations + second.relations)))
        reply(first.copy(relations = listOf(CatalogRelation(second.id, second.title, null, "PREQUEL"))))
        assertNull(policy.minimumCombinedEpisodes(second))
    }
}
