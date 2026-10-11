package eu.kanade.tachiyomi.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AniChartApiTest {
    private val api = AniChartApi()

    @Test
    fun `valid upcoming episode is returned`() {
        assertEquals(
            19 to 1_790_770_800L,
            api.parseAniListAiringResponse(
                """{"data":{"Media":{"nextAiringEpisode":{"episode":19,"airingAt":1790770800}}}}""",
            ),
        )
    }

    @Test
    fun `confirmed absence of next episode clears the date`() {
        assertEquals(
            1 to 0L,
            api.parseAniListAiringResponse("""{"data":{"Media":{"nextAiringEpisode":null}}}"""),
        )
    }

    @Test
    fun `incomplete or failed responses are unavailable`() {
        assertNull(api.parseAniListAiringResponse("""{"errors":[{"message":"Unavailable"}]}"""))
        assertNull(api.parseAniListAiringResponse("""{"data":{"Media":{}}}"""))
        assertNull(api.parseAniListAiringResponse("""{"data":{"Media":{"nextAiringEpisode":{"episode":19}}}}"""))
        assertNull(api.parseAniListAiringResponse("not json"))
    }
}
