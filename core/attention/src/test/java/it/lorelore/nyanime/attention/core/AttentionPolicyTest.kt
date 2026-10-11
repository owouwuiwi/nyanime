package it.lorelore.nyanime.attention.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AttentionPolicyTest {
    @BeforeEach fun enableCommandOwnership() {
        AttentionControl.setActive(true)
    }

    @AfterEach fun disableCommandOwnership() {
        AttentionControl.setActive(false)
    }
    private val enabled = AttentionOptions(enabled = true)
    private val playing = AttentionPlayback("opaque-content", 100_000, true)

    private fun signal(
        policy: AttentionPolicy,
        signal: AttentionSignal,
        from: Long,
        until: Long,
        frame: AttentionPlayback = playing,
        options: AttentionOptions = enabled,
    ): List<AttentionAction> = (from..until step 200).flatMap { now ->
        policy.tick(now, AttentionObservation(now, signal), frame, options)
    }

    @Test fun offNeverActsOrNeedsCamera() {
        val policy = AttentionPolicy()
        assertTrue(signal(policy, AttentionSignal.Away, 0, 20_000, options = AttentionOptions()).isEmpty())
        assertFalse(policy.cameraNeeded(1, playing, AttentionOptions()))
    }

    @Test fun sustainedDistractionPausesOnceAndAnchorsAtItsBeginning() {
        val policy = AttentionPolicy()
        val actions = signal(policy, AttentionSignal.Away, 0, 3_200)
        assertEquals(listOf(AttentionAction.Pause(AttentionReason.Distraction, 97_000)), actions)
        assertTrue(signal(policy, AttentionSignal.Away, 3_400, 5_000, playing.copy(playing = false)).isEmpty())
    }

    @Test fun briefBlinkDoesNotPause() {
        val policy = AttentionPolicy()
        assertTrue(signal(policy, AttentionSignal.EyesClosed, 0, 800).isEmpty())
        assertTrue(signal(policy, AttentionSignal.Watching, 1_000, 5_000).isEmpty())
    }

    @Test fun prolongedClosurePausesButNeverResumesByDefault() {
        val policy = AttentionPolicy()
        assertEquals(
            AttentionReason.EyesClosed,
            (signal(policy, AttentionSignal.EyesClosed, 0, 10_000).single() as AttentionAction.Pause).reason,
        )
        assertFalse(policy.cameraNeeded(10_200, playing.copy(playing = false), enabled))
        assertTrue(signal(policy, AttentionSignal.Watching, 10_200, 13_000, playing.copy(playing = false)).isEmpty())
    }

    @Test fun ownedDistractionResumesOnlyAfterStableReturn() {
        val policy = AttentionPolicy()
        signal(policy, AttentionSignal.Away, 0, 3_000)
        assertTrue(signal(policy, AttentionSignal.Watching, 3_200, 4_600, playing.copy(playing = false)).isEmpty())
        assertEquals(
            listOf(AttentionAction.Resume(null)),
            signal(policy, AttentionSignal.Watching, 4_800, 4_800, playing.copy(playing = false)),
        )
    }

    @Test fun manualPauseWhileAlreadyPausedRevokesOwnership() {
        val policy = AttentionPolicy()
        signal(policy, AttentionSignal.Away, 0, 3_000)
        assertTrue(
            signal(
                policy,
                AttentionSignal.Watching,
                3_200,
                8_000,
                playing.copy(playing = false, revision = 1),
            ).isEmpty(),
        )
        assertNull(policy.pauseReason)
    }

    @Test fun changingMediaCannotResumeOrSeekTheNextVideo() {
        val policy = AttentionPolicy()
        signal(policy, AttentionSignal.Away, 0, 3_000)
        assertTrue(
            signal(
                policy,
                AttentionSignal.Watching,
                3_200,
                8_000,
                playing.copy(key = "another-content", playing = false),
            ).isEmpty(),
        )
    }

    @Test fun cameraTimeoutCannotBeRevivedByLaterObservations() {
        val policy = AttentionPolicy()
        signal(policy, AttentionSignal.Away, 0, 3_000)
        signal(policy, AttentionSignal.Unknown, 3_200, 63_200, playing.copy(playing = false))
        assertTrue(signal(policy, AttentionSignal.Watching, 63_400, 66_000, playing.copy(playing = false)).isEmpty())
        assertFalse(policy.cameraNeeded(66_000, playing.copy(playing = false), enabled))
    }

    @Test fun unknownBreaksAnUnconfirmedDistraction() {
        val policy = AttentionPolicy()
        signal(policy, AttentionSignal.Away, 0, 2_800)
        signal(policy, AttentionSignal.Unknown, 3_000, 4_000)
        assertTrue(signal(policy, AttentionSignal.Away, 4_200, 7_000).isEmpty())
        assertEquals(1, signal(policy, AttentionSignal.Away, 7_200, 7_200).size)
    }

    @Test fun missingOrFutureSamplesCannotPause() {
        val policy = AttentionPolicy()
        for (now in 0L..20_000 step 200) {
            assertTrue(policy.tick(now, null, playing, enabled).isEmpty())
            assertTrue(
                policy.tick(
                    now + 1,
                    AttentionObservation(now + 5_000, AttentionSignal.Away),
                    playing,
                    enabled,
                ).isEmpty(),
            )
        }
    }

    @Test fun bufferingAndLargeTimeGapsDoNotAccumulateEvidence() {
        val policy = AttentionPolicy()
        signal(policy, AttentionSignal.Away, 0, 2_800)
        assertTrue(policy.tick(10_000, AttentionObservation(10_000, AttentionSignal.Away), playing, enabled).isEmpty())
        assertTrue(signal(policy, AttentionSignal.Away, 10_200, 20_000, playing.copy(buffering = true)).isEmpty())
        assertTrue(signal(policy, AttentionSignal.Away, 20_200, 23_000).isEmpty())
    }

    @Test fun unsupportedContextsAreAlwaysSuspended() {
        val policy = AttentionPolicy()
        assertTrue(signal(policy, AttentionSignal.Away, 0, 20_000, playing.copy(allowed = false)).isEmpty())
        assertFalse(policy.cameraNeeded(20_000, playing.copy(allowed = false), enabled))
    }

    @Test fun recoveryWorksWithoutAutoPauseAndIsRememberedOnce() {
        val policy = AttentionPolicy()
        val options = enabled.copy(pauseAway = false)
        assertEquals(
            listOf(AttentionAction.Remember(97_000)),
            signal(policy, AttentionSignal.Away, 0, 6_000, options = options),
        )
        assertTrue(signal(policy, AttentionSignal.Watching, 6_200, 8_000, options = options).isEmpty())
    }

    @Test fun automaticRecoveryRewindsOnceWhilePlaybackContinues() {
        val policy = AttentionPolicy()
        val options = enabled.copy(pauseAway = false, recovery = RecoveryMode.Automatic)
        signal(policy, AttentionSignal.Away, 0, 4_000, options = options)
        assertEquals(
            listOf(AttentionAction.Recover(97_000)),
            signal(policy, AttentionSignal.Watching, 4_200, 7_000, options = options),
        )
    }

    @Test fun nonSeekablePlaybackNeverCreatesRecoverySeek() {
        val policy = AttentionPolicy()
        assertNull(
            (
                signal(
                    policy,
                    AttentionSignal.Away,
                    0,
                    3_000,
                    playing.copy(seekable = false),
                ).single() as AttentionAction.Pause
                ).recoveryMs,
        )
    }

    @Test fun commandsOwnedByAttentionDoNotInvalidateThemselves() {
        val revision = AttentionControl.revision()
        AttentionControl.ownedCommand {
            AttentionControl.externalCommand()
            AttentionControl.ownedCommand { AttentionControl.externalCommand() }
        }
        assertEquals(revision, AttentionControl.revision())
        AttentionControl.externalCommand()
        assertEquals(revision + 1, AttentionControl.revision())
    }

    @Test fun ownershipScopeIsRestoredAfterAnException() {
        runCatching { AttentionControl.ownedCommand { error("synthetic failure") } }
        val before = AttentionControl.revision()
        AttentionControl.externalCommand()
        assertEquals(before + 1, AttentionControl.revision())
    }

    @Test fun invalidOptionsHaveConservativeBounds() {
        val options = enabled.copy(
            awayMs = -1,
            returnMs = 0,
            cameraWaitMs = Long.MAX_VALUE,
            sensitivity = Float.NaN,
        ).safe()
        assertEquals(2_000, options.awayMs)
        assertEquals(1_000, options.returnMs)
        assertEquals(180_000, options.cameraWaitMs)
        assertEquals(1f, options.sensitivity)
    }

    @Test fun asynchronousNativeAcknowledgementDoesNotRevokeAnOwnedPause() {
        AttentionControl.externalCommand()
        val before = AttentionControl.revision()
        AttentionControl.ownedCommand { AttentionControl.playbackCommand("test-engine", true, true) }
        AttentionControl.nativePauseObserved("test-engine", true)
        assertEquals(before, AttentionControl.revision())
        AttentionControl.ownedCommand { AttentionControl.playbackCommand("test-engine", true, false) }
        assertEquals(before, AttentionControl.revision())
    }

    @Test fun aManualCommandInvalidatesPendingAcknowledgementsToo() {
        AttentionControl.ownedCommand { AttentionControl.playbackCommand("test-engine", true, true) }
        AttentionControl.externalCommand()
        val afterManual = AttentionControl.revision()
        AttentionControl.nativePauseObserved("test-engine", true)
        assertEquals(afterManual + 1, AttentionControl.revision())
    }

    @Test fun nativeNotificationsCannotBeConsumedByAnotherEngine() {
        AttentionControl.externalCommand()
        AttentionControl.ownedCommand { AttentionControl.playbackCommand("first-engine", true, true) }
        val before = AttentionControl.revision()
        AttentionControl.nativePauseObserved("second-engine", true)
        assertEquals(before + 1, AttentionControl.revision())
    }

    @Test fun disabledHooksDoNotTrackCommandsOrNativeEvents() {
        AttentionControl.setActive(false)
        val before = AttentionControl.revision()
        AttentionControl.externalCommand()
        AttentionControl.playbackCommand("test-engine", true, true)
        AttentionControl.nativePauseObserved("test-engine", true)
        AttentionControl.ownedCommand { AttentionControl.externalCommand() }
        assertEquals(before, AttentionControl.revision())
        assertFalse(AttentionControl.isActive)
    }
}
