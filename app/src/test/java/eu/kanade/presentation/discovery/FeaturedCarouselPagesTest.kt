package eu.kanade.presentation.discovery

import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeaturedCarouselPagesTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a swipe resets the delay and interrupting an advance preserves the next rotation`() = runTest {
        val scrolling = MutableStateFlow(false)
        val interval = FeaturedCarouselPages.INTERVAL_MILLIS
        var advances = 0
        val autoplay = backgroundScope.launch {
            runFeaturedAutoplay(scrolling) {
                advances++
                if (advances == 1) currentCoroutineContext().cancel()
            }
        }
        runCurrent()
        advanceTimeBy(interval - 100)
        scrolling.value = true
        runCurrent()
        advanceTimeBy(interval)
        scrolling.value = false
        runCurrent()
        advanceTimeBy(interval - 1)
        assertEquals(0, advances)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, advances)
        advanceTimeBy(interval)
        runCurrent()
        assertEquals(2, advances)
        autoplay.cancelAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `leaving the foreground cancels an in-flight advance`() = runTest {
        val scrolling = MutableStateFlow(false)
        var advances = 0
        var stopped = false
        val autoplay = backgroundScope.launch {
            runFeaturedAutoplay(scrolling) {
                advances++
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        runCurrent()
        advanceTimeBy(FeaturedCarouselPages.INTERVAL_MILLIS)
        runCurrent()
        assertEquals(1, advances)
        autoplay.cancelAndJoin()
        advanceTimeBy(FeaturedCarouselPages.INTERVAL_MILLIS * 2)
        runCurrent()
        assertTrue(stopped)
        assertEquals(1, advances)
    }

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
        assertEquals(211.2f, FeaturedCarouselPages.coverWidth(320.dp).value, .01f)
        assertEquals(280.dp, FeaturedCarouselPages.coverWidth(840.dp))
        assertEquals(
            FeaturedCarouselPages.coverWidth(390.dp).value / .72f + 24f,
            FeaturedCarouselPages.stageHeight(390.dp).value,
            .01f,
        )
    }
}
