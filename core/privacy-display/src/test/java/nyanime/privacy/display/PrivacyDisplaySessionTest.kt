package nyanime.privacy.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrivacyDisplaySessionTest {
    private val region = PrivacyRegion(PrivacyBounds(12, 20, 180, 290), 0)
    private class Backend : PrivacyDisplayBackend<String> {
        override var capability: PrivacyDisplayCapability = PrivacyDisplayCapability.Available
        var applies = 0
        var clears = 0
        var failApply = false
        var failClear = false
        var noApplicablePixels = false
        var previousRegions = mutableListOf<PrivacyRegion?>()
        var accepted: PrivacyRegion? = null
        override fun apply(target: String, region: PrivacyRegion, previous: PrivacyRegion?): Result<PrivacyRegion?> {
            applies++
            previousRegions += previous
            return when {
                failApply -> Result.failure(IllegalStateException("apply"))
                noApplicablePixels -> Result.success(null)
                else -> Result.success(accepted ?: region)
            }
        }
        override fun clear(target: String): Result<Unit> {
            clears++
            return if (failClear) Result.failure(IllegalStateException("clear")) else Result.success(Unit)
        }
    }

    @Test fun unchangedGeometryDoesNotCallTheBackendAgain() {
        val backend = Backend()
        val session = PrivacyDisplaySession("window", backend)
        repeat(100) { session.update(region, true) }
        assertEquals(1, backend.applies)
        assertEquals(PrivacyDisplayState.Applied(region), session.state)
        session.update(region.copy(bounds = region.bounds.translate(1, 0)), true)
        assertEquals(2, backend.applies)
    }

    @Test fun disablingClearsOnceAndAllowsAWindowToResume() {
        val backend = Backend()
        val session = PrivacyDisplaySession("window", backend)
        session.update(region, true)
        repeat(10) { session.update(null, false) }
        assertEquals(1, backend.clears)
        assertEquals(PrivacyDisplayState.Disabled, session.state)
        session.update(region, true)
        assertEquals(2, backend.applies)
    }

    @Test fun unsupportedBackendIsNeverInvoked() {
        val backend = Backend().apply {
            capability = PrivacyDisplayCapability.Unavailable(PrivacyUnavailableReason.HARDWARE)
        }
        val session = PrivacyDisplaySession("window", backend)
        session.update(region, true)
        session.close()
        assertEquals(0, backend.applies)
        assertEquals(0, backend.clears)
    }

    @Test fun pipSuspendsAndFullscreenReapplies() {
        val backend = Backend()
        val session = PrivacyDisplaySession("window", backend)
        session.update(region, true)
        session.update(region, true, PrivacyUnavailableReason.WINDOW_MODE)
        assertEquals(PrivacyDisplayState.Unavailable(PrivacyUnavailableReason.WINDOW_MODE), session.state)
        assertEquals(1, backend.clears)
        session.update(region, true)
        assertEquals(2, backend.applies)
    }

    @Test fun partialActivationFailureRollsBackAndDoesNotLoop() {
        val backend = Backend().apply { failApply = true }
        val session = PrivacyDisplaySession("window", backend)
        repeat(30) { session.update(region, true) }
        assertEquals(1, backend.applies)
        assertEquals(1, backend.clears)
        assertEquals(PrivacyDisplayState.Failed(PrivacyDisplayState.Operation.APPLY), session.state)
    }

    @Test fun cleanupFailureIsNeverReportedAsDisabled() {
        val backend = Backend().apply {
            failApply = true
            failClear = true
        }
        val session = PrivacyDisplaySession("window", backend)
        session.update(region, true)
        assertEquals(PrivacyDisplayState.Failed(PrivacyDisplayState.Operation.CLEAR), session.state)
        session.close()
        session.close()
        assertEquals(2, backend.clears)
        assertEquals(PrivacyDisplayState.Failed(PrivacyDisplayState.Operation.CLEAR), session.state)
    }

    @Test fun releasedSessionNeverInvokesTheTargetAgain() {
        val backend = Backend()
        val session = PrivacyDisplaySession("window", backend)
        session.update(region, true)
        session.close()
        session.close()
        repeat(10) { session.update(region, true) }
        assertEquals(1, backend.applies)
        assertEquals(1, backend.clears)
    }

    @Test fun fittedGeometryDoesNotCauseRepeatedApplicationAndIsReportedAccurately() {
        val accepted = region.copy(bounds = region.bounds.translate(1, 0))
        val backend = Backend().apply { this.accepted = accepted }
        val session = PrivacyDisplaySession("window", backend)
        repeat(100) { session.update(region, true) }
        assertEquals(1, backend.applies)
        assertEquals(PrivacyDisplayState.Applied(accepted), session.state)
        session.update(region.copy(bounds = region.bounds.translate(2, 0)), true)
        assertEquals(listOf(null, accepted), backend.previousRegions)
        session.update(null, false)
        session.update(region, true)
        assertEquals(null, backend.previousRegions.last())
    }

    @Test fun clippingOutOfTheSafePanelClearsWithoutFaultingOrLooping() {
        val backend = Backend()
        val session = PrivacyDisplaySession("window", backend)
        session.update(region, true)
        backend.noApplicablePixels = true
        val clipped = region.copy(bounds = PrivacyBounds(0, 0, 1, 1))
        repeat(100) { session.update(clipped, true) }
        assertEquals(1, backend.clears)
        assertEquals(2, backend.applies)
        assertEquals(PrivacyDisplayState.Disabled, session.state)
        backend.noApplicablePixels = false
        session.update(region, true)
        assertEquals(PrivacyDisplayState.Applied(region), session.state)
        assertEquals(null, backend.previousRegions.last())
    }

    @Test fun anInitiallyClippedRegionCanBeRetriedAfterWindowResume() {
        val backend = Backend().apply { noApplicablePixels = true }
        val session = PrivacyDisplaySession("window", backend)
        session.update(region, true)
        assertEquals(0, backend.clears)
        session.update(null, false)
        backend.noApplicablePixels = false
        session.update(region, true)
        assertEquals(PrivacyDisplayState.Applied(region), session.state)
        assertEquals(2, backend.applies)
    }
}
