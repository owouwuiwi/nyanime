package eu.kanade.tachiyomi.ui.privacy

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivacyDisplayPolicyTest {
    @Test fun incognitoModeRequiresMasterAndKeepsAreaSelection() {
        val policy = PrivacyDisplayPolicy(
            true,
            setOf(PrivacyArea.READER),
            onlyInIncognito = true,
        )
        assertTrue(policy.canRequest(PrivacyArea.READER))
        assertFalse(policy.permits(PrivacyArea.READER))
        assertTrue(policy.copy(incognito = true).permits(PrivacyArea.READER))
        assertFalse(policy.copy(incognito = true).permits(PrivacyArea.VIDEO))
        assertFalse(policy.copy(enabled = false, incognito = true).permits(PrivacyArea.READER))
    }

    @Test fun contentIncognitoDoesNotProtectUnrelatedMixedScreenRegions() {
        val policy = PrivacyDisplayPolicy(true, PrivacyArea.entries.toSet(), onlyInIncognito = true)
        assertTrue(policy.permits(PrivacyArea.DETAILS, incognitoContext = true))
        assertFalse(policy.permits(PrivacyArea.LIBRARY))
        assertFalse(policy.permits(PrivacyArea.DETAILS, incognitoContext = false))
        val privateReader = policy.withIncognitoContext(PrivacyArea.READER, true)
        assertTrue(privateReader.permits(PrivacyArea.READER))
        assertTrue(privateReader.permits(PrivacyArea.NSFW, PrivacyArea.READER))
        assertFalse(privateReader.permits(PrivacyArea.VIDEO))
        assertFalse(privateReader.withIncognitoContext(PrivacyArea.READER, null).permits(PrivacyArea.READER))
    }

    @Test fun temporaryChoiceOverridesIncognitoButDoesNotPersistOrLeakToAnotherScope() {
        val policy = PrivacyDisplayPolicy(true, PrivacyArea.entries.toSet(), onlyInIncognito = true)
        val manual = policy.withTemporaryOverride(PrivacyArea.READER, true)
        assertTrue(manual.permits(PrivacyArea.READER))
        assertFalse(manual.permits(PrivacyArea.VIDEO))
        val off = manual.copy(incognito = true).withTemporaryOverride(PrivacyArea.READER, false)
        assertFalse(off.permits(PrivacyArea.READER))
        assertFalse(off.permits(PrivacyArea.NSFW, PrivacyArea.READER))
        assertTrue(off.permits(PrivacyArea.VIDEO))
        assertFalse(manual.withTemporaryOverride(PrivacyArea.READER, null).permits(PrivacyArea.READER))
        assertTrue(
            policy.copy(enabled = false).withTemporaryOverride(PrivacyArea.READER, true).permits(PrivacyArea.READER),
        )
    }

    @Test fun ordinaryModeIgnoresIncognitoContext() {
        val policy = PrivacyDisplayPolicy(true, setOf(PrivacyArea.DETAILS))
        assertTrue(policy.permits(PrivacyArea.DETAILS, incognitoContext = false))
        assertTrue(policy.permits(PrivacyArea.DETAILS, incognitoContext = true))
    }

    @Test fun detailsAndLibrariesCanBeSelectedIndependently() {
        val policy = PrivacyDisplayPolicy(true, setOf(PrivacyArea.DETAILS))
        assertTrue(policy.permits(PrivacyArea.DETAILS))
        assertFalse(policy.permits(PrivacyArea.LIBRARY))
        assertFalse(policy.permits(PrivacyArea.NSFW, PrivacyArea.DETAILS))
    }

    @Test fun readerOverrideCoversNsfwAndCanReturnToAutomaticWithoutPersisting() {
        val policy = PrivacyDisplayPolicy(true, PrivacyArea.entries.toSet())
        val disabled = policy.withTemporaryOverride(PrivacyArea.READER, false)
        assertFalse(disabled.permits(PrivacyArea.READER))
        assertFalse(disabled.permits(PrivacyArea.NSFW, PrivacyArea.READER))
        assertTrue(disabled.permits(PrivacyArea.VIDEO))
        assertTrue(disabled.withTemporaryOverride(PrivacyArea.READER, null).permits(PrivacyArea.READER))
        val masterOff = policy.copy(enabled = false)
        assertTrue(masterOff.withTemporaryOverride(PrivacyArea.READER, true).permits(PrivacyArea.READER))
        assertFalse(masterOff.permits(PrivacyArea.READER))
    }

    @Test fun libraryProtectionAndNsfwProtectionRemainIndependent() {
        val libraries = PrivacyDisplayPolicy(true, setOf(PrivacyArea.LIBRARY))
        assertTrue(libraries.permits(PrivacyArea.LIBRARY))
        assertFalse(libraries.permits(PrivacyArea.NSFW, PrivacyArea.LIBRARY))
        assertFalse(libraries.copy(enabled = false).permits(PrivacyArea.LIBRARY))

        val nsfwOnly = libraries.copy(selectedAreas = setOf(PrivacyArea.NSFW))
        assertFalse(nsfwOnly.permits(PrivacyArea.LIBRARY))
        assertTrue(nsfwOnly.permits(PrivacyArea.NSFW, PrivacyArea.LIBRARY))

        val temporaryVideoOverride = libraries.copy(temporaryOverrides = mapOf(PrivacyArea.VIDEO to false))
        assertTrue(temporaryVideoOverride.permits(PrivacyArea.LIBRARY))
    }

    @Test fun masterOptInControlsOrdinaryAndNsfwDeclarations() {
        val policy = PrivacyDisplayPolicy(false, PrivacyArea.entries.toSet())
        assertTrue(PrivacyArea.entries.none { policy.permits(it) })
        val active = policy.copy(enabled = true, selectedAreas = setOf(PrivacyArea.NSFW, PrivacyArea.READER))
        assertTrue(active.permits(PrivacyArea.NSFW, PrivacyArea.VIDEO))
        assertTrue(active.permits(PrivacyArea.READER))
        assertFalse(active.permits(PrivacyArea.VIDEO))
    }

    @Test fun temporaryVideoChoiceAlsoControlsItsNsfwRegionAndDoesNotAffectReading() {
        val policy = PrivacyDisplayPolicy(true, PrivacyArea.entries.toSet())
        val disabled = policy.copy(temporaryOverrides = mapOf(PrivacyArea.VIDEO to false))
        assertFalse(disabled.permits(PrivacyArea.VIDEO))
        assertFalse(disabled.permits(PrivacyArea.NSFW, PrivacyArea.VIDEO))
        assertTrue(disabled.permits(PrivacyArea.NSFW, PrivacyArea.READER))
        assertTrue(disabled.permits(PrivacyArea.READER))
        // A new window has no temporary exception and uses the persisted policy.
        assertTrue(policy.permits(PrivacyArea.VIDEO))
    }

    @Test fun explicitTemporaryOptInWorksWithoutChangingTheGlobalPreference() {
        val policy = PrivacyDisplayPolicy(false, PrivacyArea.entries.toSet())
        val enabled = policy.copy(temporaryOverrides = mapOf(PrivacyArea.VIDEO to true))
        assertTrue(enabled.permits(PrivacyArea.VIDEO))
        assertFalse(enabled.permits(PrivacyArea.READER))
        assertFalse(policy.permits(PrivacyArea.VIDEO))
    }
}
