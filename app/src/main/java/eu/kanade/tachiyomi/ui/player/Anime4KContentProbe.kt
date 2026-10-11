package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class Anime4KImageSample(val width: Int, val height: Int, val pixels: IntArray)

data class Anime4KContentResult(
    val state: Anime4KContentState,
    val assessment: Anime4KContentAssessment = Anime4KContentAssessment(null, 0.0, 0),
)

/** One bounded startup batch. Caller owns lifecycle, generation checks and single-flight access. */
object Anime4KContentProbe {
    suspend fun collect(capture: suspend () -> Anime4KImageSample?): Anime4KContentResult =
        withTimeoutOrNull(Anime4KContentAnalysis.DEADLINE_MILLIS) {
            val features = mutableListOf<Anime4KImageFeatures>()
            val seen = mutableSetOf<Int>()
            var captured = false
            repeat(Anime4KContentAnalysis.MAX_FRAMES) { index ->
                if (index > 0) delay(150)
                val image = capture() ?: return@repeat
                captured = true
                withContext(Dispatchers.Default) {
                    // Static cards and repeated video frames cannot manufacture agreement.
                    if (seen.add(image.pixels.contentHashCode())) {
                        Anime4KContentAnalysis.features(image.pixels, image.width, image.height)?.let(features::add)
                    }
                }
            }
            val assessment = Anime4KContentAnalysis.assess(features)
            Anime4KContentResult(
                when {
                    assessment.treatment != null -> Anime4KContentState.Applied
                    captured -> Anime4KContentState.Uncertain
                    else -> Anime4KContentState.Unavailable
                },
                assessment,
            )
        } ?: Anime4KContentResult(Anime4KContentState.Unavailable)
}
