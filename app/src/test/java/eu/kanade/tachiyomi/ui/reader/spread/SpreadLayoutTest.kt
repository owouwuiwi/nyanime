package eu.kanade.tachiyomi.ui.reader.spread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SpreadLayoutTest {
    private val portraitPage = 1200 to 1800

    @Test fun `cropped page geometry replaces file headers in both orientations`() {
        val original = 1600 to 2300
        val cropped = 1400 to 2200
        val visible = SpreadLayout.displaySize(original, cropped)
        assertEquals(cropped, visible)
        for ((width, height) in listOf(1080 to 2640, 2640 to 1080)) {
            val bounds = SpreadLayout.fit(width, height, listOf(visible, visible))
            val expectedHeight = minOf(width / (2.0 * cropped.first / cropped.second), height.toDouble())
            assertTrue(abs(bounds.height - expectedHeight) <= 0.5)
            assertEquals(bounds.width, bounds.pageWidths.sum())
            assertTrue(abs(bounds.pageWidths.first() - bounds.height * cropped.first.toDouble() / cropped.second) <= 1)
        }
    }

    @Test fun `different crops share a fitted height without reserving the old margins`() {
        val original = listOf(1600 to 2300, 1600 to 2300)
        val decoded = listOf(1300 to 2200, 1500 to 2000)
        val visible = original.zip(decoded) { raw, rendered -> SpreadLayout.displaySize(raw, rendered) }
        val portrait = SpreadLayout.fit(1080, 2640, visible)
        assertEquals(1080, portrait.width)
        assertTrue(portrait.pageWidths[0] < portrait.pageWidths[1])
        decoded.forEachIndexed { index, size ->
            assertTrue(abs(portrait.pageWidths[index] - portrait.height * size.first.toDouble() / size.second) <= 1)
        }
        val landscape = SpreadLayout.fit(2640, 1080, visible)
        assertEquals(1080, landscape.height)
        assertTrue(landscape.width < 2640)
    }

    @Test fun `unavailable decoder geometry falls back until a crop is ready`() {
        assertEquals(portraitPage, SpreadLayout.displaySize(portraitPage, null))
        assertEquals(portraitPage, SpreadLayout.displaySize(portraitPage, 0 to -1))
        assertEquals(null, SpreadLayout.displaySize(null, -1 to 0))
        val cropped = 1000 to 1700
        assertEquals(cropped, SpreadLayout.displaySize(portraitPage, cropped))
        assertEquals(portraitPage, SpreadLayout.displaySize(portraitPage, portraitPage))
    }

    @Test fun `decoder rotation is applied once before the spread is fitted`() {
        assertEquals(1800 to 1200, SpreadLayout.displaySize(portraitPage, portraitPage, quarterTurn = true))
        assertEquals(portraitPage, SpreadLayout.displaySize(portraitPage, null, quarterTurn = true))
        assertEquals(portraitPage, SpreadLayout.displaySize(portraitPage, portraitPage))
    }

    @Test fun `zoom cannot drag a narrow landscape pair beyond its actual edges`() {
        assertEquals(0f, SpreadLayout.panLimit(2640, 1440, 1f))
        assertEquals(0f, SpreadLayout.panLimit(2640, 1440, 1.5f))
        assertEquals(120f, SpreadLayout.panLimit(2640, 1440, 2f))
        assertEquals(540f, SpreadLayout.panLimit(1080, 1080, 2f))
    }

    @Test fun `portrait zoom keeps short pairs vertically centered while they fit`() {
        assertEquals(0f, SpreadLayout.panLimit(2640, 810, 2f))
        assertEquals(300f, SpreadLayout.panLimit(2640, 810, 4f))
    }

    @Test fun `landscape keeps pages adjacent in a centered full-height pair`() {
        val bounds = SpreadLayout.fit(2640, 1080, listOf(portraitPage, portraitPage))
        assertEquals(1080, bounds.height)
        assertEquals(listOf(720, 720), bounds.pageWidths)
        assertEquals(600, (2640 - bounds.width) / 2)
    }

    @Test fun `portrait uses all available width without separating the two pages`() {
        val bounds = SpreadLayout.fit(1080, 2640, listOf(portraitPage, portraitPage))
        assertEquals(listOf(540, 540), bounds.pageWidths)
        assertEquals(810, bounds.height)
    }

    @Test fun `different page shapes retain their proportions at a common height`() {
        val pages = listOf(1000 to 1800, 1400 to 1800)
        for (width in listOf(320, 1080, 2640)) {
            for (height in listOf(240, 1080, 2640)) {
                val bounds = SpreadLayout.fit(width, height, pages)
                assertTrue(bounds.width <= width)
                assertTrue(bounds.height <= height)
                pages.forEachIndexed { index, page ->
                    val expected = bounds.height * page.first.toDouble() / page.second
                    assertTrue(abs(bounds.pageWidths[index] - expected) <= 1.5)
                }
            }
        }
    }

    @Test fun `unmeasured pages have stable portrait placeholders and continuous rows fit width`() {
        assertEquals(
            SpreadLayout.fit(1080, 2640, listOf(portraitPage, portraitPage)),
            SpreadLayout.fit(1080, 2640, listOf(null, 0 to -1)),
        )
        val continuous = SpreadLayout.fit(1080, null, listOf(800 to 3200))
        assertEquals(1080, continuous.width)
        assertEquals(4320, continuous.height)
    }
}
