package nyanime.panels

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidedPanelOrderTest {
    private val left = PanelRect(.05f, .05f, .45f, .45f)
    private val right = PanelRect(.55f, .05f, .95f, .45f)
    private val bottom = PanelRect(.05f, .55f, .95f, .95f)

    @Test fun oldVerticalReadersStartOnTheRightWithoutChangingTheirPageMode() {
        val oldFlags = GUIDED_READING_FLAG or 3L or 0x18L
        val initialized = GuidedPanelOrder.initialize(oldFlags, explicitlyLeftToRight = false)
        assertEquals(PanelDirection.RTL, GuidedPanelOrder.stored(initialized))
        assertEquals(oldFlags, initialized and GuidedPanelOrder.MASK.inv())
        assertEquals(
            listOf(right, left, bottom),
            PanelGeometry.order(listOf(left, bottom, right), GuidedPanelOrder.resolve(initialized)),
        )
    }

    @Test fun anExplicitWesternOrderIsInitializedOnceRatherThanFollowingLaterPageModes() {
        val initialized = GuidedPanelOrder.initialize(GUIDED_READING_FLAG or 1L, explicitlyLeftToRight = true)
        assertEquals(PanelDirection.LTR, GuidedPanelOrder.resolve(initialized))
        assertEquals(initialized, GuidedPanelOrder.initialize(initialized, explicitlyLeftToRight = false))
        val verticalPageMode = (initialized and 7L.inv()) or 3L
        assertEquals(PanelDirection.LTR, GuidedPanelOrder.resolve(verticalPageMode))
    }

    @Test fun allOrdersPreserveOrientationEnablementAndUnrelatedFlags() {
        for (direction in PanelDirection.entries) {
            for (lowerFlags in 0L..0x7fL) {
                val original = lowerFlags or 0x4000L
                val selected = GuidedPanelOrder.set(original, direction)
                assertEquals(direction, GuidedPanelOrder.stored(selected))
                assertEquals(original, selected and GuidedPanelOrder.MASK.inv())
            }
        }
        assertEquals(0L, GuidedPanelOrder.MASK and 0x7fL)
        assertNull(GuidedPanelOrder.stored(0))
    }

    @Test fun upwardSwipesAdvanceEvenWithRightToLeftPanels() {
        for (direction in PanelDirection.entries) {
            assertEquals(1, GuidedPanelOrder.swipeDelta(-100f, true, direction))
            assertEquals(-1, GuidedPanelOrder.swipeDelta(100f, true, direction))
        }
        assertEquals(1, GuidedPanelOrder.swipeDelta(100f, false, PanelDirection.RTL))
        assertEquals(-1, GuidedPanelOrder.swipeDelta(-100f, false, PanelDirection.RTL))
        assertEquals(1, GuidedPanelOrder.swipeDelta(-100f, false, PanelDirection.LTR))
        assertEquals(0, GuidedPanelOrder.swipeDelta(Float.NaN, true, PanelDirection.RTL))
        assertEquals(0, GuidedPanelOrder.swipeDelta(0f, false, PanelDirection.RTL))
    }

    @Test fun changingOrderPreservesTheFocusedPanelEvenWhenZoomWasPannedOutsideIt() {
        val location = GuidedLocation(0, .7f, .8f, 4f, focusZoom = 2f, focusedPanel = left)
        val restored = requireNotNull(GuidedPanelOrder.restore(location, listOf(right, left, bottom)))
        assertEquals(1, restored.panel)
        assertEquals(left, restored.focusedPanel)
        assertEquals(location.centerX, restored.centerX)
        assertEquals(location.centerY, restored.centerY)
        assertEquals(location.focusZoom, restored.focusZoom)
        assertFalse(restored.overview)
    }

    @Test fun legacyResumeUsesTheSavedCenterInsteadOfTheOldNumber() {
        val old = GuidedLocation(0, .25f, .25f, 2f)
        val restored = requireNotNull(GuidedPanelOrder.restore(old, listOf(right, left, bottom)))
        assertEquals(1, restored.panel)
        assertEquals(left, restored.focusedPanel)
    }

    @Test fun ambiguousOrMissingLegacyGeometryReturnsToTheWholePage() {
        val overlapping = PanelRect(.1f, .1f, .4f, .4f)
        for (center in listOf(.25f to .25f, .5f to .5f)) {
            val old = GuidedLocation(1, center.first, center.second, 5f, focusZoom = 2f)
            val restored = requireNotNull(GuidedPanelOrder.restore(old, listOf(left, overlapping, bottom)))
            assertTrue(restored.overview)
            assertNull(restored.focusedPanel)
            assertEquals(1f, restored.focusZoom)
        }
    }

    @Test fun anExplicitManualSequenceAndAnOverviewStayIntactDuringResume() {
        val manual = listOf(bottom, right, left)
        val location = GuidedLocation(0, bottom.centerX, bottom.centerY, 1f, focusedPanel = bottom)
        assertEquals(0, requireNotNull(GuidedPanelOrder.restore(location, manual)).panel)
        assertEquals(listOf(bottom, right, left), manual)
        val overview = location.copy(overview = true, focusZoom = 3f)
        val restored = requireNotNull(GuidedPanelOrder.restore(overview, manual))
        assertTrue(restored.overview)
        assertEquals(3f, restored.focusZoom)
        assertNull(restored.focusedPanel)
    }

    @Test fun theNewGeometryAnchorRoundTripsAndOldProtobufStillDecodes() {
        val old = LegacyLocation(1, .7f, .25f, 4f, false, 2f)
        val oldBytes = ProtoBuf.encodeToByteArray(LegacyLocation.serializer(), old)
        val decoded = ProtoBuf.decodeFromByteArray(GuidedLocation.serializer(), oldBytes)
        assertEquals(old.panel, decoded.panel)
        assertEquals(old.focusZoom, decoded.focusZoom)
        assertNull(decoded.focusedPanel)
        val modern = decoded.copy(focusedPanel = right)
        val modernBytes = ProtoBuf.encodeToByteArray(GuidedLocation.serializer(), modern)
        assertEquals(modern, ProtoBuf.decodeFromByteArray(GuidedLocation.serializer(), modernBytes))
        assertEquals(old, ProtoBuf.decodeFromByteArray(LegacyLocation.serializer(), modernBytes))
        assertEquals(
            modern,
            Json.decodeFromString<GuidedLocation>(Json.encodeToString(GuidedLocation.serializer(), modern)),
        )
        assertFalse(modern.copy(focusedPanel = PanelRect(1f, 1f, 0f, 0f)).valid())
    }

    @Serializable
    private data class LegacyLocation(
        val panel: Int = 0,
        val centerX: Float = 0.5f,
        val centerY: Float = 0.5f,
        val zoom: Float = 1f,
        val overview: Boolean = false,
        val focusZoom: Float? = null,
    )
}
