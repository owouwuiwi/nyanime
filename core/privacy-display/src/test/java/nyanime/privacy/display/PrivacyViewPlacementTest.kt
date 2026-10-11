package nyanime.privacy.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PrivacyViewPlacementTest {
    private val expansion = PrivacyPanelExpansion(1, 1, 2, 2)

    @Test fun fullWidthRegionRemainsInsideThePanelAfterVendorExpansion() {
        val panel = PrivacyBounds(0, 0, 1440, 3120)
        val placement = PrivacyViewPlacement.resolve(PrivacyRegion(panel, 0), panel, expansion, 0, 0)!!
        assertEquals(PrivacyBounds(1, 1, 1438, 3118), placement.displayRegion.bounds)
        val bounds = placement.displayRegion.bounds
        assertEquals(panel, PrivacyBounds(bounds.left - 1, bounds.top - 1, bounds.right + 2, bounds.bottom + 2))
    }

    @Test fun positioningUsesTheDedicatedHostsOriginWithoutMovingTheHost() {
        val requested = PrivacyRegion(PrivacyBounds(40, 140, 600, 900), 0)
        val placement = PrivacyViewPlacement.resolve(requested, PrivacyBounds(0, 0, 1440, 3120), expansion, 10, 80)!!
        assertEquals(requested, placement.displayRegion)
        assertEquals(PrivacyBounds(30, 60, 590, 820), placement.localBounds)
    }

    @Test fun rotationUsesCurrentPanelDimensions() {
        val panel = PrivacyBounds(0, 0, 3120, 1440)
        val placement = PrivacyViewPlacement.resolve(PrivacyRegion(panel, 0), panel, expansion, 0, 0)!!
        assertEquals(PrivacyBounds(1, 1, 3118, 1438), placement.localBounds)
    }

    @Test fun invalidOrOffscreenRegionsCannotProduceAnAnchor() {
        val region = PrivacyRegion(PrivacyBounds(30, 30, 50, 50), 0)
        assertNull(PrivacyViewPlacement.resolve(region, PrivacyBounds(0, 0, 2, 2), expansion, 0, 0))
        assertNull(PrivacyViewPlacement.resolve(region, PrivacyBounds(100, 100, 200, 200), expansion, 0, 0))
    }

    @Test fun strictPanelEdgesRemainInteriorAfterExpansionAndTransitionRounding() {
        val panel = PrivacyBounds(0, 0, 1440, 3120)
        val interior = PrivacyBounds(16, 16, 1424, 3104)
        val fitted = PrivacyViewPlacement.resolve(PrivacyRegion(panel, 0), interior, expansion, 0, 0)!!
        assertEquals(PrivacyBounds(17, 17, 1422, 3102), fitted.displayRegion.bounds)
        val bounds = fitted.displayRegion.bounds
        for (rounding in -1..1) {
            val expanded = PrivacyBounds(
                bounds.left - expansion.left + rounding,
                bounds.top - expansion.top + rounding,
                bounds.right + expansion.right + rounding,
                bounds.bottom + expansion.bottom + rounding,
            )
            org.junit.jupiter.api.Assertions.assertTrue(expanded.left >= 13 && expanded.top >= 13)
            org.junit.jupiter.api.Assertions.assertTrue(expanded.right <= panel.right - 13)
            org.junit.jupiter.api.Assertions.assertTrue(expanded.bottom <= panel.bottom - 13)
        }
        assertNull(
            PrivacyViewPlacement.resolve(PrivacyRegion(PrivacyBounds(0, 0, 2, 2), 0), interior, expansion, 0, 0),
        )
    }
}
