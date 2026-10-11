package it.lorelore.nyanime.attention.core

import kotlin.math.abs
import kotlin.math.hypot

data class FacePoint(val x: Float, val y: Float)
data class FaceEvidence(
    val eyeWidth: Float,
    val leftOpen: Float,
    val rightOpen: Float,
    val leftBlink: Float,
    val rightBlink: Float,
    val irisX: Float,
    val irisY: Float,
    val yaw: Float,
)

/** Eye-local coordinates remove head roll: lying sideways is not itself distraction. */
object FaceGeometry {
    fun measure(points: List<FacePoint>, blinkLeft: Float, blinkRight: Float): FaceEvidence? {
        if (points.size < 478 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        fun eye(a: Int, b: Int, top: Int, bottom: Int, iris: Int): FloatArray {
            val p = points[a]
            val q = points[b]
            val dx = q.x - p.x
            val dy = q.y - p.y
            val square = dx * dx + dy * dy
            val width = kotlin.math.sqrt(square).coerceAtLeast(0.00001f)
            val ix = points[iris].x - (p.x + q.x) / 2
            val iy = points[iris].y - (p.y + q.y) / 2
            val aperture = hypot(points[top].x - points[bottom].x, points[top].y - points[bottom].y) / width
            return floatArrayOf(width, aperture, (ix * dx + iy * dy) / square, (-ix * dy + iy * dx) / square)
        }
        val left = eye(33, 133, 159, 145, 468)
        val right = eye(362, 263, 386, 374, 473)
        val a = points[33]
        val b = points[263]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val width = dx * dx + dy * dy
        if (width < 0.0025f || minOf(left[0], right[0]) < 0.025f) return null
        val nose = points[1]
        val yaw = ((nose.x - (a.x + b.x) / 2) * dx + (nose.y - (a.y + b.y) / 2) * dy) / width
        if (minOf(left[0], right[0]) / maxOf(left[0], right[0]) < 0.48f) return null
        return FaceEvidence(
            minOf(left[0], right[0]),
            left[1],
            right[1],
            blinkLeft,
            blinkRight,
            (left[2] + right[2]) / 2,
            (left[3] + right[3]) / 2,
            yaw,
        )
    }
}

class FaceClassifier {
    private var baseline: FaceEvidence? = null
    private val calibration = mutableListOf<FaceEvidence>()
    var calibrating = false
        private set

    @get:Synchronized
    val calibrated: Boolean get() = baseline != null
    val calibrationSamples: Int get() = calibration.size

    @Synchronized fun beginCalibration() {
        calibration.clear()
        calibrating = true
    }

    @Synchronized fun cancelCalibration() {
        calibration.clear()
        calibrating = false
    }

    /** Missing/ambiguous images cannot join two otherwise separate calibration windows. */
    @Synchronized fun discardCalibrationEvidence() {
        if (calibrating) calibration.clear()
    }

    @Synchronized fun reset() {
        baseline = null
        calibration.clear()
        calibrating = false
    }

    @Synchronized fun classify(face: FaceEvidence, sensitivity: Float): AttentionSignal {
        if (face.leftBlink > 0.72f &&
            face.rightBlink > 0.72f &&
            face.leftOpen < 0.16f &&
            face.rightOpen < 0.16f
        ) {
            if (calibrating) calibration.clear()
            return AttentionSignal.EyesClosed
        }
        // A wink, strong profile or hidden eye is insufficient evidence for any automatic action.
        if (minOf(face.leftOpen, face.rightOpen) < 0.1f || maxOf(face.leftBlink, face.rightBlink) > 0.6f) {
            if (calibrating) calibration.clear()
            return AttentionSignal.Unknown
        }
        val normal = baseline
        val yaw = abs(face.yaw - (normal?.yaw ?: 0f))
        val horizontal = abs(face.irisX - (normal?.irisX ?: 0f))
        val vertical = abs(face.irisY - (normal?.irisY ?: 0f))
        val away = yaw > 0.24f / sensitivity || horizontal > 0.28f / sensitivity || vertical > 0.27f / sensitivity
        if (calibrating && away) calibration.clear()
        if (calibrating && !away) {
            calibration += face
            if (calibration.size >= 15) {
                fun median(block: (FaceEvidence) -> Float) = calibration.map(block).sorted()[calibration.size / 2]
                baseline = face.copy(irisX = median { it.irisX }, irisY = median { it.irisY }, yaw = median { it.yaw })
                calibrating = false
                calibration.clear()
            }
        }
        return if (away) AttentionSignal.Away else AttentionSignal.Watching
    }
}
