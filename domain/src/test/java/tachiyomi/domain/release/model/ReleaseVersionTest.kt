package tachiyomi.domain.release.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseVersionTest {
    @Test
    fun numericComponentsAreComparedInOrderWithoutContinuingAfterADifference() {
        assertTrue(ReleaseVersion.parse("0.20.0.0")!! > ReleaseVersion.parse("0.19.99.999")!!)
        assertFalse(ReleaseVersion.parse("0.18.99.999")!! > ReleaseVersion.parse("0.19.0.0")!!)
        assertTrue(ReleaseVersion.parse("v0.19.0.10")!! > ReleaseVersion.parse("0.19.0.9")!!)
        assertFalse(ReleaseVersion.parse("0.19.0.0")!! > ReleaseVersion.parse("v0.19.0.0")!!)
    }

    @Test
    fun onlyPublicFourComponentVersionsAreAccepted() {
        listOf(
            "r9000", "tv-r9000", "0.19.0", "0.019.0.0", "0.19.0.0.1", "0.19.0.0-9000", "0.19.0.beta",
            "0.19.0.2147483648", "v0.19.0.0 ", "-1.0.0.0",
        ).forEach { assertNull(ReleaseVersion.parse(it), it) }
        assertTrue(ReleaseVersion.installed("0.18.1.4-9000")!! < ReleaseVersion.parse("0.19.0.0")!!)
    }
}
