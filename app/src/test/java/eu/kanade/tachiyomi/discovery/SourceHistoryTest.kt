package eu.kanade.tachiyomi.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.history.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.history.anime.model.forSources

class SourceHistoryTest {
    @Test fun historyUsesSourceIdentityAndPreservesOrderAcrossMultipleHomeProviders() {
        val entries = listOf(1L, 2L, 3L, 1L).mapIndexed { index, source ->
            AnimeHistoryWithRelations(
                index.toLong(),
                index.toLong(),
                index.toLong(),
                "Same title",
                1.0,
                null,
                AnimeCover(index.toLong(), source, false, null, 0),
            )
        }
        assertEquals(listOf(0L, 2L, 3L), entries.forSources(setOf(1, 3)).map { it.id })
        assertEquals(listOf(1L), entries.forSources(setOf(2)).map { it.id })
        assertEquals(emptyList<AnimeHistoryWithRelations>(), entries.forSources(emptySet()))
        assertEquals(entries, entries.forSources(null))
    }
}
