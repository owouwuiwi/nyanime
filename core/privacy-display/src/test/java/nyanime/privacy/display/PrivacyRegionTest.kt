package nyanime.privacy.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivacyRegionTest {
    @Test fun fractionalBoundsAreRoundedOutwards() {
        assertEquals(PrivacyBounds(-1, 2, 31, 41), PrivacyBounds.enclosing(-0.2f, 2.8f, 30.1f, 40.9f))
        assertNull(PrivacyBounds.enclosing(Float.NaN, 0f, 10f, 20f))
        assertNull(PrivacyBounds.enclosing(0f, 0f, 0f, 10f))
    }

    @Test fun movingRegionsAreConservativelyCombinedAndClipped() {
        val viewport = PrivacyRegion(PrivacyBounds(0, 0, 280, 600), 0)
        val result = PrivacyRegion.enclosing(
            listOf(
                PrivacyRegion(PrivacyBounds(-20, 40, 160, 180), 0),
                PrivacyRegion(PrivacyBounds(140, 10, 300, 400), 0),
                PrivacyRegion(PrivacyBounds(0, 0, 100, 100), 2),
            ),
            viewport,
        )
        assertEquals(PrivacyRegion(PrivacyBounds(0, 10, 280, 400), 0), result)
    }

    @Test fun offscreenAndOtherDisplayRegionsDoNotActivate() {
        val viewport = PrivacyRegion(PrivacyBounds(0, 0, 280, 600), 0)
        assertNull(PrivacyRegion.enclosing(listOf(PrivacyRegion(PrivacyBounds(300, 0, 400, 200), 0)), viewport))
        assertNull(PrivacyRegion.enclosing(listOf(viewport.copy(displayId = 3)), viewport))
        assertNull(PrivacyRegion.enclosing(emptyList(), viewport))
    }

    @Test fun windowOriginIsAppliedOnce() {
        val bounds = PrivacyBounds.enclosing(10.2f, 20.1f, 90.5f, 100.3f)!!
        assertEquals(PrivacyBounds(110, 70, 191, 151), bounds.translate(100, 50))
    }

    @Test fun manufacturerAndApiLevelAloneDoNotClaimHardware() {
        assertFalse(PrivacyDisplayDevice("Samsung", "SM-F741B", 36).samsungPrivacyHardware)
        assertFalse(PrivacyDisplayDevice("other", "SM-S948B", 36).samsungPrivacyHardware)
        assertFalse(PrivacyDisplayDevice("Samsung", "SM-S948B", 35).samsungPrivacyHardware)
        assertTrue(PrivacyDisplayDevice("samsung", "SM-S948B", 36).samsungPrivacyHardware)
    }
}
