package eu.kanade.tachiyomi.ui.privacy

import nyanime.privacy.display.PrivacyBounds
import nyanime.privacy.display.PrivacyDisplayCapability
import nyanime.privacy.display.PrivacyDisplayState
import nyanime.privacy.display.PrivacyRegion
import nyanime.privacy.display.PrivacyUnavailableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivacyDisplayPresentationTest {
    private val policy = PrivacyDisplayPolicy(true, setOf(PrivacyArea.READER))
    private val available = PrivacyDisplayCapability.Available
    private val applied = PrivacyDisplayState.Applied(PrivacyRegion(PrivacyBounds(20, 20, 200, 400), 0))

    @Test fun staleAppliedStateCannotTurnAnExplicitlyDisabledControlBackOn() {
        val presentation = PrivacyDisplayPresentation.from(
            policy.withTemporaryOverride(PrivacyArea.READER, false),
            available,
            applied,
            PrivacyArea.READER,
        )
        assertFalse(presentation.requested)
        assertEquals(PrivacyDisplayStatus.DISABLED, presentation.status)
    }

    @Test fun incognitoWaitingAndUnselectedAreaRemainDistinct() {
        val privatePolicy = policy.copy(onlyInIncognito = true)
        val waiting = PrivacyDisplayPresentation.from(
            privatePolicy,
            available,
            PrivacyDisplayState.Disabled,
            PrivacyArea.READER,
        )
        assertFalse(waiting.requested)
        assertEquals(PrivacyDisplayStatus.WAITING_FOR_INCOGNITO, waiting.status)
        val excluded = PrivacyDisplayPresentation.from(
            privatePolicy,
            available,
            PrivacyDisplayState.Disabled,
            PrivacyArea.VIDEO,
        )
        assertEquals(PrivacyDisplayStatus.DISABLED, excluded.status)
    }

    @Test fun supportedCallsReportARequestAndSuspensionDoesNotAlterUserIntent() {
        val active = PrivacyDisplayPresentation.from(policy, available, applied, PrivacyArea.READER)
        assertTrue(active.requested)
        assertEquals(PrivacyDisplayStatus.REQUESTED, active.status)
        val suspended = PrivacyDisplayPresentation.from(
            policy,
            available,
            PrivacyDisplayState.Unavailable(PrivacyUnavailableReason.WINDOW_MODE),
            PrivacyArea.READER,
        )
        assertTrue(suspended.requested)
        assertTrue(suspended.canToggle)
        assertEquals(PrivacyDisplayStatus.SUSPENDED, suspended.status)
    }

    @Test fun importedPreferenceCannotClaimSupportAndFailuresRemainVisible() {
        val unsupported = PrivacyDisplayPresentation.from(
            policy,
            PrivacyDisplayCapability.Unavailable(PrivacyUnavailableReason.HARDWARE),
            PrivacyDisplayState.Disabled,
            PrivacyArea.READER,
        )
        assertTrue(unsupported.requested)
        assertFalse(unsupported.canToggle)
        assertEquals(PrivacyDisplayStatus.UNAVAILABLE, unsupported.status)
        val failure = PrivacyDisplayPresentation.from(
            policy,
            PrivacyDisplayCapability.Unavailable(PrivacyUnavailableReason.FIRMWARE),
            PrivacyDisplayState.Disabled,
            PrivacyArea.READER,
            failed = true,
        )
        assertFalse(failure.canToggle)
        assertEquals(PrivacyDisplayStatus.FAILED, failure.status)
    }
}
