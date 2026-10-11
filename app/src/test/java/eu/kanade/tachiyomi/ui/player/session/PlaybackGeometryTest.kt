package eu.kanade.tachiyomi.ui.player.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackGeometryTest {
    @Test fun `dragged mini crosses the lower safe area without losing its anchor`() {
        val moving = PlaybackRect(456f, 2440f, 588f, 331f)
        val dragged = PlaybackGeometry.dragMini(moving, 1080f, 2640f, 94f)
        assertEquals(moving, dragged)
        assertTrue(dragged.y + dragged.height > 2640f)
        assertEquals(492f, PlaybackGeometry.dragMini(moving.copy(x = 2000f), 1080f, 2640f, 94f).x)
    }

    @Test fun `only a deliberate downward drag beyond the lower safe area closes the mini`() {
        val lower = PlaybackRect(456f, 2300f, 588f, 331f)
        assertTrue(PlaybackGeometry.dismissMini(lower, 2640f, 300f, 3f, 300f))
        assertTrue(!PlaybackGeometry.dismissMini(lower, 2640f, 300f, 3f, 120f))
        assertTrue(!PlaybackGeometry.dismissMini(lower, 2640f, 300f, 3f, -400f))
        assertTrue(!PlaybackGeometry.dismissMini(lower.copy(y = 500f), 2640f, 300f, 3f, 400f))
        assertTrue(!PlaybackGeometry.dismissMini(lower, 2640f, 300f, 3f, Float.NaN))
    }

    @Test fun `resting mini still avoids the keyboard and navigation after a cancelled dismissal`() {
        val moving = PlaybackRect(500f, 750f, 196f, 110f)
        val dragged = PlaybackGeometry.dragMini(moving, 360f, 800f, 24f)
        val resting = PlaybackGeometry.constrain(dragged, 360f, 800f, 24f, 340f)
        assertEquals(460f, resting.y + resting.height)
        assertTrue(resting.x + resting.width <= 360f)
        assertTrue(PlaybackGeometry.dismissMini(dragged, 800f, 340f, 1f, 200f))
    }

    @Test fun `rotation and presentation retain renderer dimensions without losing fullscreen resolution`() {
        for (aspect in listOf(0.5625f, 1f, 1.77778f, 2.4f)) {
            val portrait = PlaybackGeometry.renderSize(1080f, 2640f, aspect)
            val landscape = PlaybackGeometry.renderSize(2640f, 1080f, aspect)
            assertEquals(portrait, landscape)
            assertEquals(aspect, portrait.first.toFloat() / portrait.second, 0.002f)
        }
        assertEquals(1920 to 1080, PlaybackGeometry.renderSize(1080f, 2640f, 16f / 9f))
        assertEquals(1080 to 1920, PlaybackGeometry.renderSize(1080f, 2640f, 9f / 16f))
    }

    @Test fun `shrinking video keeps the grabbed vertical point under the finger`() {
        val page = PlaybackRect(0f, 94f, 1080f, 608f)
        val mini = PlaybackRect(456f, 1976f, 588f, 331f)
        for (anchor in listOf(0.1f, 0.5f, 0.9f)) {
            for (distance in listOf(40f, 180f, 420f, 900f)) {
                val moved = PlaybackGeometry.pageDrag(page, mini, anchor, distance)
                val originalGrab = page.y + page.height * anchor
                assertEquals(originalGrab + distance, moved.y + moved.height * anchor, 0.001f)
            }
        }
    }

    @Test fun `dismissal keeps following the finger when keyboard forces the mini above the page`() {
        val page = PlaybackRect(0f, 30f, 360f, 202.5f)
        val mini = PlaybackRect(12f, 42f, 196f, 110.25f)
        val moved = PlaybackGeometry.pageDrag(page, mini, 0.8f, 160f)
        assertEquals(page.y + page.height * 0.8f + 160f, moved.y + moved.height * 0.8f, 0.001f)
        assertTrue(PlaybackGeometry.pageDragTravel(page, mini, 0.8f) >= page.height)
    }

    @Test fun `mini player preserves aspect and fits narrow windows keyboard and reader tools`() {
        for (width in listOf(220f, 360f, 1200f)) {
            for (aspect in listOf(0.5625f, 1f, 1.77778f, 2.4f)) {
                for (corner in MiniCorner.entries) {
                    val rect = PlaybackGeometry.mini(width, 720f, 40f, 310f, 1f, aspect, 240f, corner)
                    assertEquals(aspect, rect.width / rect.height, 0.001f)
                    assertTrue(rect.x >= 0 && rect.y >= 40)
                    assertTrue(rect.x + rect.width <= width)
                    assertTrue(rect.y + rect.height <= 720f - 310f)
                }
            }
        }
    }

    @Test fun `page leaves room for details with portrait video and uses the available wide column`() {
        val narrow = PlaybackGeometry.page(360f, 800f, 24f, 30f, 0.5625f, false)
        assertTrue(narrow.height < 420f)
        val wide = PlaybackGeometry.page(1200f, 800f, 24f, 30f, 1.77778f, true)
        assertEquals(744f, wide.width)
        assertTrue(wide.height < 600f)
    }

    @Test fun `short interrupted gesture returns to its source presentation`() {
        assertEquals(
            PlaybackPresentation.Page,
            PlaybackGeometry.settlePresentation(PlaybackPresentation.Page, 0.02f, 0.1f),
        )
        assertEquals(
            PlaybackPresentation.Fullscreen,
            PlaybackGeometry.settlePresentation(PlaybackPresentation.Fullscreen, 0.02f, 0.1f),
        )
        assertEquals(
            PlaybackPresentation.Mini,
            PlaybackGeometry.settlePresentation(PlaybackPresentation.Page, 0.3f, 0f),
        )
        assertEquals(
            PlaybackPresentation.Fullscreen,
            PlaybackGeometry.settlePresentation(PlaybackPresentation.Page, -0.3f, 0f),
        )
        assertEquals(
            PlaybackPresentation.Page,
            PlaybackGeometry.settlePresentation(PlaybackPresentation.Fullscreen, 0.3f, 0f),
        )
    }

    @Test fun `resize interpolation cannot overshoot or produce negative bounds`() {
        val page = PlaybackRect(0f, 30f, 360f, 202.5f)
        val mini = PlaybackRect(134f, 550f, 214f, 120.375f)
        assertEquals(page, page.interpolate(mini, -1f))
        assertEquals(mini, page.interpolate(mini, 2f))
        val midpoint = page.interpolate(mini, 0.5f)
        assertEquals(287f, midpoint.width)
        assertTrue(midpoint.height > 0)
    }
}
