package nyanime.tv.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvLiveTest {
    private val sample = TvLiveObservation(100.0, TvClock(100.0, 1_000_000), false, false, true)
    private val remote = TvLiveReference(1, 1_000_000, 2_000_000)

    @Test fun livePauseAndBufferingStayLocal() {
        assertEquals(
            TvLiveAdjustment.LocalHold,
            TvLivePolicy.adjustment(sample.copy(paused = true), remote, 1, 2_000_000, 0),
        )
        assertEquals(
            TvLiveAdjustment.LocalHold,
            TvLivePolicy.adjustment(sample.copy(buffering = true), remote, 1, 2_000_000, 0),
        )
    }

    @Test fun noClockMeansNoInventedSync() {
        assertEquals(
            TvLiveAdjustment.Unverified,
            TvLivePolicy.adjustment(sample.copy(clock = null), remote, 1, 2_000_000, 0),
        )
        assertEquals(TvLiveAdjustment.AwaitingReference, TvLivePolicy.adjustment(sample, remote, 1, 2_000_000))
    }

    @Test fun acceptsThreeSecondDifferenceAndRejectsOldChannelReferences() {
        assertTrue(
            TvLivePolicy.adjustment(
                sample.copy(positionSeconds = 103.0),
                remote,
                1,
                2_000_000,
                0,
            ) is TvLiveAdjustment.Aligned,
        )
        assertEquals(TvLiveAdjustment.AwaitingReference, TvLivePolicy.adjustment(sample, remote, 2, 2_000_000, 0))
    }

    @Test fun adjustsGraduallyOnlyWithAnExplicitCapability() {
        val local = sample.copy(positionSeconds = 110.0)
        val adjustment = TvLivePolicy.adjustment(local, remote, 1, 2_000_000, 0) as TvLiveAdjustment.Gradual
        assertTrue(adjustment.speed in 0.97..1.0)
        assertTrue(
            TvLivePolicy.adjustment(
                local.copy(canChangeSpeed = false),
                remote,
                1,
                2_000_000,
                0,
            ) is TvLiveAdjustment.Limited,
        )
    }

    @Test fun clockOffsetAndTransportAgeAreAccountedFor() {
        assertTrue(
            TvLivePolicy.adjustment(
                sample.copy(positionSeconds = 102.0),
                remote,
                1,
                2_007_000,
                5000,
            ) is TvLiveAdjustment.Aligned,
        )
        assertEquals(TvLiveAdjustment.AwaitingReference, TvLivePolicy.adjustment(sample, remote, 1, 2_020_000, 0))
    }

    @Test fun overflowingClocksCannotProduceAnAlignmentCommand() {
        val malformed = remote.copy(sentUtc = Long.MAX_VALUE)
        assertEquals(TvLiveAdjustment.AwaitingReference, TvLivePolicy.adjustment(sample, malformed, 1, 2_000_000, 50))
        assertEquals(null, sample.copy(clock = TvClock(0.0, Long.MAX_VALUE)).broadcastUtc())
    }
}
