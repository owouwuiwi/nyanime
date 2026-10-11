package eu.kanade.tachiyomi.data.migration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationInstallPolicyTest {
    @Test fun onlyAnExistingLegacyInstallationReceivesTheUpgradeOffer() {
        assertTrue(MigrationInstallPolicy.shouldOffer(true, true, true, false, false))
        assertFalse(MigrationInstallPolicy.shouldOffer(false, true, true, false, false))
        assertFalse(MigrationInstallPolicy.shouldOffer(true, false, true, false, false))
        assertFalse(MigrationInstallPolicy.shouldOffer(true, true, false, false, false))
    }

    @Test fun deferringOrAcceptingTheOfferPreventsRepeatedPrompts() {
        assertFalse(MigrationInstallPolicy.shouldOffer(true, true, true, true, false))
    }

    @Test fun aVerifiedTransferDoesNotPromptTheUserToTransferAgain() {
        assertFalse(MigrationInstallPolicy.shouldOffer(true, true, true, false, true))
    }

    @Test fun missingMalformedOrOlderPublicationsCannotStartADownload() {
        assertFalse(MigrationInstallPolicy.compatibleRelease(null, "1.2.3.4"))
        assertFalse(MigrationInstallPolicy.compatibleRelease("unknown", "1.2.3.4"))
        assertFalse(MigrationInstallPolicy.compatibleRelease("1.2.3.3", "1.2.3.4"))
    }

    @Test fun anAvailableEqualOrNewerPublicationCanReceiveTheSourceData() {
        assertTrue(MigrationInstallPolicy.compatibleRelease("1.2.3.4", "1.2.3.4"))
        assertTrue(MigrationInstallPolicy.compatibleRelease("v1.2.3.5", "1.2.3.4"))
    }
}
