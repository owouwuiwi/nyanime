package it.lorelore.nyanime.attention.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttentionObserveOnlyTest {
    private val silent =
        AttentionOptions(enabled = true, mode = AttentionMode.ObserveOnly, recovery = RecoveryMode.Automatic)
    private val playing = AttentionPlayback("opaque-content", 0, true)

    @Test fun observationOnlyNeverPausesResumesRecoversOrWritesRecoveryPoints() {
        val policy = AttentionPolicy()
        for (signal in AttentionSignal.entries) {
            for (time in 0L..60_000 step 200) {
                assertTrue(
                    policy.tick(
                        time,
                        AttentionObservation(time, signal),
                        playing.copy(positionMs = time),
                        silent,
                    ).isEmpty(),
                )
                assertNull(policy.pauseReason)
            }
        }
    }

    @Test fun switchingFromOwnedAutomaticPauseCannotResumeInObservationOnly() {
        val policy = AttentionPolicy()
        val automatic = silent.copy(mode = AttentionMode.Automatic)
        for (time in 0L..4_000 step 200) {
            policy.tick(
                time,
                AttentionObservation(time, AttentionSignal.Away),
                playing,
                automatic,
            )
        }
        assertNotNull(policy.pauseReason)
        assertTrue(
            policy.tick(
                4_200,
                AttentionObservation(4_200, AttentionSignal.Watching),
                playing.copy(playing = false),
                silent,
            ).isEmpty(),
        )
        assertNull(policy.pauseReason)
        assertFalse(policy.cameraNeeded(4_400, playing.copy(playing = false), silent))
    }

    @Test fun observationOnlyCameraNeedsActualPermittedPlayback() {
        val policy = AttentionPolicy()
        assertTrue(policy.cameraNeeded(0, playing, silent))
        assertFalse(policy.cameraNeeded(0, playing.copy(buffering = true), silent))
        assertFalse(policy.cameraNeeded(0, playing.copy(playing = false), silent))
        assertFalse(policy.cameraNeeded(0, playing.copy(allowed = false), silent))
        assertFalse(policy.cameraNeeded(0, playing, silent.copy(enabled = false)))
    }
}
