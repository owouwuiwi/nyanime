package nyanime.panels

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidedViewportTest {
    @Test fun wholePageDoesNotShrinkBelowTheRendererMinimum() {
        val focus = GuidedViewport.fit(PanelRect.PAGE, 1000, 2000, 500, 1200, .5f)
        assertEquals(GuidedViewport(.5f, .5f, .5f), focus)
        assertFalse(focus.isEnlarged(.5f))
    }

    @Test fun magnifyingAnEdgePanelAutomaticallyDoesNotUnlockPanning() {
        val focus = GuidedViewport.fit(PanelRect(0f, .1f, .2f, .3f), 1000, 2000, 500, 1000, .5f)
        assertTrue(focus.scale > .5f)
        assertTrue(focus.centerX < .15f)
        assertFalse(focus.isEnlarged(focus.scale))
        assertTrue(focus.isEnlarged(focus.scale * 1.1f))
    }

    @Test fun olderFloatingBookmarksReturnToTheFittedPanelWithoutDeletingTheBookmark() {
        val focus = GuidedViewport(2f, .2f, .3f)
        val old = GuidedLocation(panel = 4, centerX = .9f, centerY = .8f, zoom = 4f)
        assertEquals(focus, focus.restore(old, .5f))
        assertEquals(4, old.panel)
    }

    @Test fun realManualZoomPreservesScaleAndPersonalPosition() {
        val focus = GuidedViewport(2f, .2f, .3f)
        val old = GuidedLocation(centerX = .4f, centerY = .6f, zoom = 8f)
        assertEquals(GuidedViewport(4f, .4f, .6f), focus.restore(old, .5f))
    }

    @Test fun returningNearOrBelowFitRelocksRatherThanRestoringAnOffset() {
        val focus = GuidedViewport(2f, .2f, .3f)
        for (scale in listOf(.5f, 1.5f, 2f, 2.01f)) {
            val old = GuidedLocation(centerX = .9f, centerY = .9f, zoom = scale / .5f)
            assertFalse(focus.isEnlarged(scale))
            assertEquals(focus, focus.restore(old, .5f))
        }
    }

    @Test fun rotationAndLongPagesRecomputeFitFromTheAvailableViewport() {
        val page = PanelRect(0f, .4f, 1f, .6f)
        val portrait = GuidedViewport.fit(page, 800, 10000, 320, 640, .064f)
        val landscape = GuidedViewport.fit(page, 800, 10000, 640, 320, .032f)
        assertTrue(portrait.scale > landscape.scale)
        assertEquals(.5f, portrait.centerY, .001f)
        assertEquals(portrait.centerY, landscape.centerY)
        assertFalse(landscape.isEnlarged(landscape.scale))
    }

    @Test fun invalidRestoredPositionFallsBackToPage() {
        val focus = GuidedViewport(.5f, .5f, .5f)
        assertEquals(focus, focus.restore(GuidedLocation(centerX = Float.NaN, zoom = 10f), .5f))
        assertEquals(focus, focus.restore(GuidedLocation(zoom = Float.POSITIVE_INFINITY), .5f))
    }

    @Test fun savedAutomaticFocusNeverUnlocksOnAnotherScreenShape() {
        val landscape = GuidedViewport(1f, .2f, .3f)
        val portraitBookmark = GuidedLocation(centerX = .2f, centerY = .3f, zoom = 8f, focusZoom = 1f)
        assertEquals(landscape, landscape.restore(portraitBookmark, .5f))
        val manuallyEnlarged = portraitBookmark.copy(focusZoom = 2f, centerX = .4f)
        assertEquals(GuidedViewport(2f, .4f, .3f), landscape.restore(manuallyEnlarged, .5f))
    }

    @Test fun bookmarksWithoutTheOptionalFocusZoomRemainReadable() {
        val old = Json.decodeFromString<GuidedLocation>(
            """{"panel":3,"centerX":0.4,"centerY":0.6,"zoom":8.0,"overview":false}""",
        )
        assertEquals(null, old.focusZoom)
        assertEquals(GuidedViewport(4f, .4f, .6f), GuidedViewport(2f, .2f, .3f).restore(old, .5f))
    }
}
