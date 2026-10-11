package nyanime.tv.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvRulesTest {
    private val ref = TvChannelRef(12, "opaque-feed")

    @Test
    fun `programme rollover has no gap or overlap at the boundary`() {
        val first = TvProgramme("a", ref, "First", 1_000, 2_000)
        val second = TvProgramme("b", ref, "Second", 2_000, 3_000)
        assertEquals(first to second, TvRules.nowNext(listOf(second, first), 1_999))
        assertEquals(second to null, TvRules.nowNext(listOf(first, second), 2_000))
    }

    @Test
    fun `invalid dates never create a currently airing programme`() {
        val broken = TvProgramme("broken", ref, "Programme", 4_000, 3_000)
        assertFalse(TvRules.valid(broken))
        assertEquals(null to null, TvRules.nowNext(listOf(broken), 3_500))
    }

    @Test
    fun `channel identities do not depend on titles or playable URLs`() {
        assertTrue(TvRules.valid(TvChannel(ref, "A channel")))
        assertFalse(TvRules.valid(TvChannel(ref.copy(sourceId = 0), "A channel")))
        assertFalse(TvRules.valid(TvChannel(ref.copy(id = "\n"), "A channel")))
        assertFalse(TvRules.valid(ref.copy(id = "https://media.example/live?secret=private")))
    }

    @Test
    fun `stream transport excludes unsafe schemes and header injection`() {
        assertTrue(TvRules.valid(TvStream("a", "https://media.example/live.m3u8", "Auto")))
        assertFalse(TvRules.valid(TvStream("a", "file:///local/video", "Auto")))
        assertFalse(TvRules.valid(TvStream("a", "https://media.example/live", "Auto", mapOf("Referer" to "a\nb"))))
    }
}
