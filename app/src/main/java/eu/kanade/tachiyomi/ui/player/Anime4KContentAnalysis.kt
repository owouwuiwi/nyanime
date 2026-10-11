package eu.kanade.tachiyomi.ui.player

import kotlin.math.abs

enum class Anime4KContentState { Disabled, Sampling, Unavailable, Uncertain, Applied }

data class Anime4KImageFeatures(
    val edgeDensity: Double,
    val edgeStrength: Double,
    val curvature: Double,
    val flatNoise: Double,
    val variance: Double,
)

data class Anime4KContentAssessment(
    val treatment: Anime4KMode?,
    val confidence: Double,
    val usableFrames: Int,
)

/** Conservative, local heuristics. Disabled by default until paired perceptual/device validation. */
object Anime4KContentAnalysis {
    const val DEFAULT_ENABLED = false
    const val MAX_FRAMES = 3
    const val MAX_DIMENSION = 512
    const val DEADLINE_MILLIS = 1_000L

    /** Receives a bounded, original rendered frame, never an image processed by Anime4K. */
    fun features(pixels: IntArray, width: Int, height: Int): Anime4KImageFeatures? {
        if (width !in 64..MAX_DIMENSION || height !in 36..MAX_DIMENSION || pixels.size != width * height) return null
        val luma = IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            (((pixel shr 16 and 255) * 54 + (pixel shr 8 and 255) * 183 + (pixel and 255) * 19) shr 8)
        }
        // Ignore subtitles near the bottom, black bars, and almost empty opening/fade frames.
        val left = (width / 12).coerceAtLeast(2)
        val right = width - left
        val top = (height / 10).coerceAtLeast(2)
        val bottom = height - height / 5
        var count = 0
        var sum = 0.0
        var squares = 0.0
        var edges = 0
        var edgeStrength = 0.0
        var curvature = 0.0
        var flatPixels = 0
        var flatNoise = 0.0
        for (y in top until bottom) {
            for (x in left until right) {
                val i = y * width + x
                val value = luma[i]
                if (value < 12 || value > 244) continue
                val gx = abs(luma[i + 1] - luma[i - 1]) / 2.0
                val gy = abs(luma[i + width] - luma[i - width]) / 2.0
                val gradient = gx + gy
                val laplacian = abs(
                    4 * value - luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width],
                ).toDouble()
                count++
                sum += value
                squares += value.toDouble() * value
                if (gradient >= 8) {
                    edges++
                    edgeStrength += gradient
                    curvature += laplacian
                } else {
                    flatPixels++
                    flatNoise += laplacian / 4.0
                }
            }
        }
        val area = (right - left) * (bottom - top)
        if (count < area / 4 || edges < 40) return null
        val variance = (squares / count - (sum / count) * (sum / count)).coerceAtLeast(0.0)
        if (variance < 100) return null
        return Anime4KImageFeatures(
            edgeDensity = edges.toDouble() / count,
            edgeStrength = edgeStrength / edges,
            curvature = curvature / edgeStrength.coerceAtLeast(1.0),
            flatNoise = if (flatPixels > 0) flatNoise / flatPixels else 0.0,
            variance = variance,
        )
    }

    fun assess(frames: List<Anime4KImageFeatures>): Anime4KContentAssessment {
        val bounded = frames.take(MAX_FRAMES).filter { feature ->
            listOf(feature.edgeDensity, feature.edgeStrength, feature.curvature, feature.flatNoise, feature.variance)
                .all { it.isFinite() && it >= 0 } &&
                feature.edgeDensity <= 1 &&
                feature.variance >= 100
        }
        val suggestions = bounded.mapNotNull { features ->
            when {
                features.flatNoise >= 4.0 && features.edgeDensity < 0.25 -> Anime4KMode.ModeC
                features.edgeStrength in 8.0..24.0 &&
                    features.curvature < 0.85 &&
                    features.edgeDensity >= 0.04 -> Anime4KMode.ModeA
                features.curvature > 1.8 &&
                    features.edgeStrength >= 28.0 &&
                    features.edgeDensity in 0.02..0.3 &&
                    features.flatNoise < 2.0 -> Anime4KMode.ModeB
                else -> null
            }
        }
        val agreement = suggestions.groupingBy { it }.eachCount().maxByOrNull { it.value }
        // Two distinct frames are required; disagreement and low-information images abstain.
        val accepted = bounded.size >= 2 &&
            agreement != null &&
            agreement.value >= 2 &&
            agreement.value == suggestions.size &&
            agreement.value.toDouble() / bounded.size >= 2.0 / 3.0
        return Anime4KContentAssessment(
            treatment = agreement?.key?.takeIf { accepted },
            confidence = if (accepted) 0.85 + 0.1 * (agreement.value - 2) else 0.0,
            usableFrames = bounded.size,
        )
    }
}
