package eu.kanade.tachiyomi.data.tv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TvSeekWindowTest {
    @Test fun `native map joins overlapping ranges without bridging uncached gaps`() {
        val raw = """{"seekable-ranges":[{"start":20,"end":40},{"start":5,"end":25},{"start":60,"end":80}]}"""
        assertEquals(5.0..40.0, TvSeekWindow.read(raw, 30.0))
        assertEquals(60.0..80.0, TvSeekWindow.read(raw, 70.0))
        assertNull(TvSeekWindow.read(raw, 50.0))
    }

    @Test fun `unknown invalid or missing native cache never exposes a synthetic seek window`() {
        for (raw in listOf(null, "not-json", "{}", "[]", """{"seekable-ranges":[]}""")) {
            assertNull(TvSeekWindow.read(raw, 10.0))
        }
        assertNull(TvSeekWindow.read("""{"seekable-ranges":[{"start":-1,"end":30},{"start":20,"end":10}]}""", 10.0))
        assertNull(TvSeekWindow.read("""{"seekable-ranges":[{"start":0,"end":30}]}""", Double.NaN))
    }

    @Test fun `oversized or excessive cache metadata is rejected`() {
        assertNull(TvSeekWindow.read(" ".repeat(65_537), 10.0))
        val ranges = List(33) { "{\"start\":0,\"end\":20}" }.joinToString(",")
        assertNull(TvSeekWindow.read("{\"seekable-ranges\":[$ranges]}", 10.0))
    }
}
