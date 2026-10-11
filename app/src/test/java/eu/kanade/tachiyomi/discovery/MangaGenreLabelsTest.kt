package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.MangaGenreLabels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MangaGenreLabelsTest {
    @Test
    fun equivalentLabelsShareOneChipWithoutChangingSourceValues() {
        assertEquals("Azione", MangaGenreLabels.display("Action"))
        assertEquals(MangaGenreLabels.key("Action"), MangaGenreLabels.key("Azione"))
        assertEquals(
            listOf("Azione", "Fantascienza", "Mistero"),
            MangaGenreLabels.distinct(listOf("Action", "Azione", "Sci-Fi", "Fantascienza", "Mystery")),
        )
    }

    @Test
    fun unknownGenresRemainAvailable() {
        assertEquals("Nuovo genere", MangaGenreLabels.display(" Nuovo genere "))
        assertEquals(listOf("Nuovo genere"), MangaGenreLabels.distinct(listOf("Nuovo genere")))
    }
}
