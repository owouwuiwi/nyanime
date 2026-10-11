package tachiyomi.domain.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SymSpellTitleIndexMutationTest {
    @Test
    fun `renaming a record removes its old searchable terms`() {
        val index = SymSpellTitleIndex()
        val original = title("one", "Winter Harbor")
        index.add(listOf(original))
        assertEquals(listOf(original), index.candidates("Winter Harbor", SearchMedium.VIDEO))

        val renamed = original.copy(title = "Copper Meadow")
        index.add(listOf(renamed))

        assertTrue(index.candidates("Winter Harbor", SearchMedium.VIDEO).isEmpty())
        assertEquals(listOf(renamed), index.candidates("Copper Meadow", SearchMedium.VIDEO))
    }

    @Test
    fun `a removed alias cannot keep returning a record with unrelated names`() {
        val index = SymSpellTitleIndex()
        val original = title("one", "Copper Meadow").copy(aliases = listOf("Winter Harbor"))
        index.add(listOf(original))

        val updated = original.copy(aliases = listOf("Amber Forest"))
        index.add(listOf(updated))

        assertTrue(index.candidates("Winter Harbor", SearchMedium.VIDEO).isEmpty())
        assertEquals(listOf(updated), index.candidates("Amber Forest", SearchMedium.VIDEO))
    }

    @Test
    fun `removing an alias preserves another record that still declares it`() {
        val index = SymSpellTitleIndex()
        val first = title("one", "Copper Meadow").copy(aliases = listOf("Winter Harbor"))
        val second = title("two", "Silver Valley").copy(aliases = listOf("Winter Harbor"))
        index.add(listOf(first, second))

        index.add(listOf(first.copy(aliases = emptyList())))

        assertEquals(listOf(second), index.candidates("Winter Harbor", SearchMedium.VIDEO))
    }

    @Test
    fun `the last version in a batch is the only indexed version`() {
        val index = SymSpellTitleIndex()
        val first = title("one", "Winter Harbor")
        val last = first.copy(title = "Copper Meadow")

        index.add(listOf(first, first.copy(title = "Silver Valley"), last))

        assertTrue(index.candidates("Winter Harbor", SearchMedium.VIDEO).isEmpty())
        assertTrue(index.candidates("Silver Valley", SearchMedium.VIDEO).isEmpty())
        assertEquals(listOf(last), index.candidates("Copper Meadow", SearchMedium.VIDEO))
    }

    @Test
    fun `metadata changes preserve names and return only the current record in its medium`() {
        val index = SymSpellTitleIndex()
        val original = title("one", "Winter Harbor").copy(aliases = listOf("Copper Meadow"))
        index.add(listOf(original))
        val updated = original.copy(medium = SearchMedium.MANGA, origin = "library", sourceId = 17)

        index.add(listOf(updated))

        assertTrue(index.candidates("Winter Harbor", SearchMedium.VIDEO).isEmpty())
        assertEquals(listOf(updated), index.candidates("Winter Harbor", SearchMedium.MANGA))
        assertEquals(listOf(updated), index.candidates("Copper Meadow", SearchMedium.MANGA))
    }

    @Test
    fun `updated names and evictions remain consistent across media and numbered editions`() {
        val index = SymSpellTitleIndex(capacity = 2)
        val first = title("one", "Winter Harbor 1")
        val manga = title("two", "Winter Harbor 2").copy(medium = SearchMedium.MANGA)
        index.add(listOf(first, manga))
        val updated = first.copy(title = "Copper Meadow 3", aliases = listOf("Amber Forest 3"))
        val last = title("three", "Silver Valley 4")

        index.add(listOf(updated, last))

        assertTrue(index.candidates("Winter Harbor 1", SearchMedium.VIDEO).isEmpty())
        assertTrue(index.candidates("Winter Harbor 2", SearchMedium.MANGA).isEmpty())
        assertEquals(listOf(updated), index.candidates("Copper Meadow 3", SearchMedium.VIDEO))
        assertEquals(listOf(updated), index.candidates("Amber Forest 3", SearchMedium.VIDEO))
        assertEquals(listOf(last), index.candidates("Silver Valley 4", SearchMedium.VIDEO))
    }

    @Test
    fun `adding an unchanged record preserves candidates and replacing clears old records`() {
        val index = SymSpellTitleIndex()
        val first = title("one", "Winter Harbor")
        index.add(listOf(first))
        index.add(listOf(first))
        assertEquals(listOf(first), index.candidates("Winter Harbor", SearchMedium.VIDEO))

        val replacement = title("two", "Copper Meadow")
        index.replace(listOf(replacement))

        assertTrue(index.candidates("Winter Harbor", SearchMedium.VIDEO).isEmpty())
        assertEquals(listOf(replacement), index.candidates("Copper Meadow", SearchMedium.VIDEO))
        index.clear()
        assertTrue(index.candidates("Copper Meadow", SearchMedium.VIDEO).isEmpty())
    }

    private fun title(key: String, value: String) = SearchTitle(key, value, SearchMedium.VIDEO)
}
