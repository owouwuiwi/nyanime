package eu.kanade.tachiyomi.data.migration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationRemovalPolicyTest {
    @Test
    fun `unfinished migration or unverified previous identity cannot request removal`() {
        for (pending in listOf(false, true)) {
            for (verified in listOf(false, true)) {
                assertFalse(MigrationRemovalPolicy.allowed(false, pending, verified))
            }
            assertFalse(MigrationRemovalPolicy.allowed(true, pending, false))
        }
        assertFalse(MigrationRemovalPolicy.allowed(true, true, true))
    }

    @Test
    fun `verified completion without pending transaction enables the Android confirmation`() {
        assertTrue(MigrationRemovalPolicy.allowed(true, false, true))
    }

    @Test
    fun `offer requires a verified transfer and is not repeated after deferring`() {
        assertTrue(MigrationRemovalPolicy.shouldOffer(true, false))
        assertFalse(MigrationRemovalPolicy.shouldOffer(true, true))
        assertFalse(MigrationRemovalPolicy.shouldOffer(false, false))
        assertFalse(MigrationRemovalPolicy.shouldOffer(false, true))
    }
}
