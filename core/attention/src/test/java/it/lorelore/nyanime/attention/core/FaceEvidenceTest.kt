package it.lorelore.nyanime.attention.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class FaceEvidenceTest {
    private val open = FaceEvidence(.1f, .3f, .3f, 0f, 0f, 0f, 0f, 0f)

    @Test fun bilateralClosureRequiresBothBlinkAndGeometryEvidence() {
        val classifier = FaceClassifier()
        assertEquals(
            AttentionSignal.EyesClosed,
            classifier.classify(open.copy(leftBlink = .95f, rightBlink = .95f, leftOpen = .05f, rightOpen = .05f), 1f),
        )
        assertEquals(AttentionSignal.Unknown, classifier.classify(open.copy(leftBlink = .95f, leftOpen = .05f), 1f))
        assertEquals(AttentionSignal.Unknown, classifier.classify(open.copy(leftBlink = .95f, rightBlink = .95f), 1f))
    }

    @Test fun lookingAwayIsNotLearnedAsNormalDuringCalibration() {
        val classifier = FaceClassifier()
        classifier.beginCalibration()
        repeat(40) { assertEquals(AttentionSignal.Away, classifier.classify(open.copy(irisX = .4f), 1f)) }
        assertFalse(classifier.calibrated)
        assertEquals(0, classifier.calibrationSamples)
    }

    @Test fun missingImagesBreakTheCalibrationWindowWithoutLearningAnUncertainPose() {
        val classifier = FaceClassifier()
        classifier.beginCalibration()
        repeat(10) { classifier.classify(open, 1f) }
        classifier.discardCalibrationEvidence()
        assertTrue(classifier.calibrating)
        repeat(14) { classifier.classify(open, 1f) }
        assertFalse(classifier.calibrated)
        classifier.classify(open, 1f)
        assertTrue(classifier.calibrated)
    }

    @Test fun calibrationIsOptionalAndDoesNotLearnClosedEyes() {
        val classifier = FaceClassifier()
        assertEquals(AttentionSignal.Watching, classifier.classify(open, 1f))
        classifier.beginCalibration()
        repeat(30) {
            classifier.classify(open.copy(leftBlink = 1f, rightBlink = 1f, leftOpen = .03f, rightOpen = .03f), 1f)
        }
        assertFalse(classifier.calibrated)
        repeat(15) { classifier.classify(open.copy(irisX = .1f), 1f) }
        assertTrue(classifier.calibrated)
        classifier.reset()
        assertFalse(classifier.calibrated)
    }

    @Test fun rotationDoesNotChangeEyeLocalMeasurements() {
        val points = MutableList(478) { FacePoint(.5f, .5f) }
        points[33] = FacePoint(.2f, .4f)
        points[133] = FacePoint(.4f, .4f)
        points[159] = FacePoint(.3f, .37f)
        points[145] = FacePoint(.3f, .43f)
        points[468] = FacePoint(.31f, .4f)
        points[362] = FacePoint(.6f, .4f)
        points[263] = FacePoint(.8f, .4f)
        points[386] = FacePoint(.7f, .37f)
        points[374] = FacePoint(.7f, .43f)
        points[473] = FacePoint(.71f, .4f)
        points[1] = FacePoint(.5f, .6f)
        val reference = checkNotNull(FaceGeometry.measure(points, 0f, 0f))
        for (degrees in listOf(-135, -90, -45, 0, 45, 90, 135, 180)) {
            val radians = Math.toRadians(degrees.toDouble())
            val rotated = points.map { point ->
                val x = point.x - .5
                val y = point.y - .5
                FacePoint(
                    (x * cos(radians) - y * sin(radians) + .5).toFloat(),
                    (x * sin(radians) + y * cos(radians) + .5).toFloat(),
                )
            }
            val measured = checkNotNull(FaceGeometry.measure(rotated, 0f, 0f))
            assertEquals(reference.irisX, measured.irisX, .0001f)
            assertEquals(reference.leftOpen, measured.leftOpen, .0001f)
            assertEquals(reference.yaw, measured.yaw, .0001f)
        }
    }

    @Test fun malformedOrTooSmallFacesAreUnknown() {
        assertNull(FaceGeometry.measure(emptyList(), 0f, 0f))
        assertNull(FaceGeometry.measure(List(478) { FacePoint(.5f, .5f) }, 0f, 0f))
        assertNull(FaceGeometry.measure(List(478) { FacePoint(Float.NaN, .5f) }, 0f, 0f))
    }
}
