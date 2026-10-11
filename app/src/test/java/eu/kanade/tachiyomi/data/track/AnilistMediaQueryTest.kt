package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.track.anilist.AnilistMediaQuery
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AnilistMediaQueryTest {
    @Test
    fun `translated title cannot restrict verified id lookup`() {
        val variables = AnilistMediaQuery.variables("Un titolo che non esiste nel catalogo", 987L)
        assertEquals(setOf("id"), variables.keys)
        assertEquals(987L, variables.getValue("id").jsonPrimitive.long)
    }

    @Test
    fun `manual title search still sends the full query`() {
        val title = "Example Part II"
        val variables = AnilistMediaQuery.variables(title, null)
        assertEquals(setOf("query"), variables.keys)
        assertEquals(title, variables.getValue("query").jsonPrimitive.content)
    }

    @Test
    fun `invalid and overflowing catalogue ids cannot become another work`() {
        listOf(-1L, 0L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { AnilistMediaQuery.variables("Example", id) }
        }
    }
}
