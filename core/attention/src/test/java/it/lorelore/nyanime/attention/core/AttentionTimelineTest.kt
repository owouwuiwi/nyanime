package it.lorelore.nyanime.attention.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttentionTimelineTest {
    private val frame = AttentionPlayback("opaque-item", 0, true)
    private fun play(
        timeline: AttentionTimeline,
        from: Long = 0,
        until: Long = 4_000,
        signal: AttentionSignal = AttentionSignal.Watching,
        base: AttentionPlayback = frame,
    ) {
        for (time in from..until step 200) {
            timeline.tick(time, AttentionObservation(time, signal), base.copy(positionMs = time))
        }
    }

    @Test fun emptySessionHasNoInventedCoverage() {
        val timeline = AttentionTimeline()
        assertNull(timeline.stop())
        assertNull(timeline.tick(0, null, frame.copy(playing = false)))
    }

    @Test fun sustainedWatchingProducesEstimatedWatchingAndInitialUncertainty() {
        val timeline = AttentionTimeline()
        play(timeline)
        val chunk = timeline.stop()!!
        assertEquals(frame.key, chunk.key)
        assertEquals(1, chunk.masks.size)
        assertEquals(9, chunk.masks[0].toInt())
    }

    @Test fun briefBlinkNeverMarksProlongedClosure() {
        val timeline = AttentionTimeline()
        play(timeline, until = 2_000)
        play(timeline, 2_200, 2_600, AttentionSignal.EyesClosed)
        play(timeline, 2_800, 4_800)
        assertFalse(timeline.stop()!!.masks.any { it.toInt() and 4 != 0 })
    }

    @Test fun prolongedEyeClosureAndLookingAwayStayDifferentEvidence() {
        val timeline = AttentionTimeline()
        play(timeline, until = 3_000, signal = AttentionSignal.EyesClosed)
        play(timeline, 3_200, 7_000, AttentionSignal.Away)
        val masks = timeline.stop()!!.masks
        assertTrue(masks.any { it.toInt() and 4 != 0 })
        assertTrue(masks.any { it.toInt() and 2 != 0 })
        assertFalse(masks.any { it.toInt() and 1 != 0 })
    }

    @Test fun staleAndFutureMeasurementsOnlyProduceUncertainty() {
        for (offset in listOf(-2_000L, 1L)) {
            val timeline = AttentionTimeline()
            for (time in 0L..3_000 step 200) {
                timeline.tick(
                    time,
                    AttentionObservation(time + offset, AttentionSignal.Watching),
                    frame.copy(positionMs = time),
                )
            }
            assertArrayEquals(byteArrayOf(8), timeline.stop()!!.masks)
        }
    }

    @Test fun seekRevisionCannotFillTheSkippedPart() {
        val timeline = AttentionTimeline()
        play(timeline, until = 2_000)
        val previous = timeline.tick(
            2_200,
            AttentionObservation(2_200, AttentionSignal.Watching),
            frame.copy(positionMs = 600_000, revision = 1),
        )!!
        assertEquals(1, previous.masks.size)
        assertNull(timeline.stop())
    }

    @Test fun unsignalledJumpAndLifecycleGapAreNotWatchedIntervals() {
        val timeline = AttentionTimeline()
        timeline.tick(0, AttentionObservation(0, AttentionSignal.Watching), frame)
        timeline.tick(200, AttentionObservation(200, AttentionSignal.Watching), frame.copy(positionMs = 600_000))
        timeline.tick(5_000, AttentionObservation(5_000, AttentionSignal.Watching), frame.copy(positionMs = 800_000))
        assertNull(timeline.stop())
    }

    @Test fun backgroundBufferingAndManualPauseFlushButDoNotAppend() {
        for (blocked in listOf(
            frame.copy(allowed = false),
            frame.copy(buffering = true),
            frame.copy(playing = false),
        )) {
            val timeline = AttentionTimeline()
            play(timeline, until = 2_000)
            val chunk = timeline.tick(
                2_200,
                AttentionObservation(2_200, AttentionSignal.Watching),
                blocked.copy(positionMs = 600_000),
            )!!
            assertEquals(1, chunk.masks.size)
            assertNull(timeline.stop())
        }
    }

    @Test fun privacyChangeKeepsOrdinaryAndPrivateChunksSeparate() {
        val timeline = AttentionTimeline()
        play(timeline, until = 2_000)
        val ordinary = timeline.tick(
            2_200,
            AttentionObservation(2_200, AttentionSignal.Watching),
            frame.copy(positionMs = 2_200, incognito = true),
        )!!
        assertFalse(ordinary.incognito)
        play(timeline, 2_400, 4_000, base = frame.copy(incognito = true))
        assertTrue(timeline.stop()!!.incognito)
    }

    @Test fun coarsePositionClockAndDoubleSpeedAreSupported() {
        for (speed in listOf(1f, 2f)) {
            val timeline = AttentionTimeline()
            for (time in 0L..4_000 step 200) {
                val position = (time / 1_000 * 1_000 * speed).toLong()
                timeline.tick(
                    time,
                    AttentionObservation(time, AttentionSignal.Watching),
                    frame.copy(positionMs = position, playbackSpeed = speed),
                )
            }
            assertTrue(timeline.stop()!!.masks.any { it.toInt() and 1 != 0 })
        }
    }

    @Test fun periodicChunksAreIndependentAndMergingNeverErasesPreviousEvidence() {
        val timeline = AttentionTimeline()
        var first: AttentionTraceChunk? = null
        for (time in 0L..16_000 step 200) {
            timeline.tick(
                time,
                AttentionObservation(time, AttentionSignal.Watching),
                frame.copy(positionMs = time),
            )?.let {
                first =
                    it
            }
        }
        assertTrue(first != null)
        val final = timeline.stop()!!
        val combined = AttentionTraceCodec.merge(first!!.masks, final.masks)
        assertTrue(combined.size >= first!!.masks.size)
        assertArrayEquals(first!!.masks, AttentionTraceCodec.merge(first!!.masks, ByteArray(0)))
        assertEquals(3, AttentionTraceCodec.merge(byteArrayOf(1), byteArrayOf(2))[0].toInt())
    }

    @Test fun malformedOversizedAndUnknownVersionsAreRejected() {
        for (value in listOf(
            "",
            "2:5000:1",
            "1:1:1",
            "1:5000:z",
            "1:5000:" + "f".repeat(AttentionTimeline.MAX_BINS + 1),
        )) {
            assertNull(AttentionTraceCodec.decode(value))
        }
        val masks = byteArrayOf(0, 1, 2, 4, 8, 15)
        assertArrayEquals(masks, AttentionTraceCodec.decode(AttentionTraceCodec.encode(masks)))
    }

    @Test fun impossiblePositionsCannotAllocateUnboundedBuffers() {
        for (position in listOf(-1L, Long.MAX_VALUE)) {
            val timeline = AttentionTimeline()
            assertNull(timeline.tick(0, null, frame.copy(positionMs = position)))
            assertNull(timeline.stop())
        }
    }
}
