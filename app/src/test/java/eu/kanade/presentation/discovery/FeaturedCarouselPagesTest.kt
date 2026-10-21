package eu.kanade.presentation.discovery

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeaturedCarouselPagesTest {
    @Test
    fun `swiping across either end wraps without repeating the edge title`() {
        for (count in listOf(2, 3, 7, 28, 100)) {
            val start = FeaturedCarouselPages.initial(count)
            assertEquals(0, FeaturedCarouselPages.index(start, count))
            assertEquals(count - 1, FeaturedCarouselPages.index(start - 1, count))
            assertEquals(0, FeaturedCarouselPages.index(start + count, count))
            assertEquals(1, FeaturedCarouselPages.index(start + count + 1, count))
            assertTrue(start > count && start + count < FeaturedCarouselPages.WINDOW)
        }
    }

    @Test
    fun `restored selection keeps its position in a refreshed collection`() {
        for (count in listOf(1, 2, 12, 99)) {
            for (selected in 0 until count) {
                assertEquals(
                    selected,
                    FeaturedCarouselPages.index(
                        FeaturedCarouselPages.initial(count, selected),
                        count,
                    ),
                )
            }
        }
    }

    @Test
    fun `single cover is not duplicated and invalid saved indexes are bounded`() {
        assertEquals(0, FeaturedCarouselPages.initial(1, 8))
        assertEquals(0, FeaturedCarouselPages.index(FeaturedCarouselPages.initial(4, -1), 4))
        assertEquals(3, FeaturedCarouselPages.index(FeaturedCarouselPages.initial(4, 15), 4))
    }

    @Test
    fun `placeholder and pager reserve the same bounded portrait geometry`() {
        assertEquals(243.2f, FeaturedCarouselPages.coverWidth(320.dp).value, .01f)
        assertEquals(340.dp, FeaturedCarouselPages.coverWidth(840.dp))
        assertEquals(
            FeaturedCarouselPages.coverWidth(390.dp).value / .72f + 32f,
            FeaturedCarouselPages.stageHeight(390.dp).value,
            .01f,
        )
    }
}
